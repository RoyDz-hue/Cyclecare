import os, pgserver, sys, psycopg2
srv = pgserver.get_server('/tmp/econ/pgdata')
db = psycopg2.connect(srv.get_uri())
db.autocommit = True
cur = db.cursor()
def run(path):
    cur.execute(open(path).read())
run('/tmp/econ/stubs.sql')
cur.execute(open(os.path.join(os.path.dirname(os.path.abspath(__file__)),'..','migrations','20261031180000_economy_supply_engine.sql')).read())
print("migration applied OK")
fails = []
def check(name, cond, extra=""):
    print(("PASS " if cond else "FAIL ") + name, extra)
    if not cond: fails.append(name)
def q1(sql, args=None):
    cur.execute(sql, args or ())
    r = cur.fetchone()
    return r[0] if r else None

# seed prior state: two existing members with earnings history
cur.execute("INSERT INTO public.economy_profiles(user_id, coin_balance, lifetime_earned) VALUES ('u-old1', 300, 300), ('u-old2', 50, 80)")
cur.execute(open('/tmp/econ/reset_issuance.sql').read() if False else "SELECT 1")
# re-init issuance to reflect seeded history (migration ran before seed; emulate fresh deploy order)
cur.execute("UPDATE public.economy_issuance SET opening_count = (SELECT COALESCE(SUM(lifetime_earned),0) FROM public.economy_profiles), total_minted = (SELECT COALESCE(SUM(lifetime_earned),0) FROM public.economy_profiles) WHERE id=1")
cur.execute("UPDATE public.economy_supply_lines SET allowance = GREATEST(0, 4000000 - (SELECT opening_count FROM public.economy_issuance WHERE id=1)) WHERE line_key='reserve'")
check("opening count is seeded sum 380", q1("SELECT opening_count FROM public.economy_issuance") == 380)
check("reserve = 4M - opening", q1("SELECT allowance FROM public.economy_supply_lines WHERE line_key='reserve'") == 3999620)
check("schedule step0 base 30", q1("SELECT (public.current_claim_schedule()->>'base_rate')::numeric") == 30)

# daily claim for a new user: starter 120 + claim 30
uid = "11111111-1111-1111-1111-111111111111"
cur.execute("INSERT INTO auth.users(id, email) VALUES (%s, 'new@x.com')", (uid,))
cur.execute("SELECT set_config('test.uid', %s, false)", (uid,))
r = q1("SELECT public.verify_and_mine_coins(%s, 'UTC')", (uid,))
check("first claim succeeds", r['success'] is True, str(r))
check("awarded 30", float(r['awarded']) == 30)
check("balance 150", float(r['new_balance']) == 150)
check("starter line issued 120", q1("SELECT issued FROM public.economy_supply_lines WHERE line_key='starter'") == 120)
check("claims line issued 30", q1("SELECT issued FROM public.economy_supply_lines WHERE line_key='claims'") == 30)
r = q1("SELECT public.verify_and_mine_coins(%s, 'UTC')", (uid,))
check("second claim same day refused", r['success'] is False)

# milestone schedule transitions
for issued, base, open_ in [(2500000,'15',True),(3750000,'7.5',True),(4375000,'3.75',True),(4687500,'1.875',True),(4843750,'0.938',True),(4921875,'0.469',True),(4960937.5,'0.234',True),(4980468.75,'0.117',True),(4990234.375,'0.059',False)]:
    cur.execute("UPDATE public.economy_supply_lines SET issued=%s WHERE line_key='claims'", (issued,))
    s = q1("SELECT public.current_claim_schedule()")
    check(f"issued {issued} -> base {base} open={open_}", float(s['base_rate'])==float(base) and s['claims_open']==open_, str(s))
cur.execute("UPDATE public.economy_supply_lines SET issued=30 WHERE line_key='claims'")

# purchase routes spend to treasury, price factor 1 at step 0
cur.execute("INSERT INTO public.cosmetic_catalog(item_id, kind, name, price_coins, is_active) VALUES ('test_item','ARTIFACT','Test',100,true)")
r = q1("SELECT public.purchase_economy_item('test_item')")
check("purchase succeeds", r['success'] is True and float(r['paid_price_coins'])==100, str(r))
check("buyer balance 50", q1("SELECT coin_balance FROM public.economy_profiles WHERE user_id=%s", (uid,)) == 50)
check("treasury holds 100", q1("SELECT balance FROM public.economy_treasury") == 100)
check("treasury movement logged", q1("SELECT count(*) FROM public.economy_supply_movements WHERE line_key='treasury' AND action='TREASURY_IN'") == 1)
check("total_minted unchanged by purchase", q1("SELECT total_minted FROM public.economy_issuance") == 530)

# treasury draw funds rewards without new minting
r = q1("SELECT public.treasury_fund_reward(%s, 40, 'test')", (uid,))
check("treasury draw ok", r['success'] is True and float(r['treasury_balance'])==60, str(r))
check("member received 40", q1("SELECT coin_balance FROM public.economy_profiles WHERE user_id=%s", (uid,)) == 90)
check("still no new minting", q1("SELECT total_minted FROM public.economy_issuance") == 530)
try:
    cur.execute("SELECT public.treasury_fund_reward(%s, 1000, 'too much')", (uid,))
    check("overdraw refused", False)
except Exception:
    pass
    check("overdraw refused", True)

# bulk gifts draw from gifts line; over-budget run refused
cur.execute("INSERT INTO public.profiles(user_id) VALUES (%s), (%s)", (uid, "22222222-2222-2222-2222-222222222222"))
r = q1("SELECT public.admin_gift_all_coins(10, 'promo', 'run-1')")
check("gift run ok", r['success'] is True and r['recipients']==2 and float(r['total_coins'])==20, str(r))
check("gifts line issued 20", q1("SELECT issued FROM public.economy_supply_lines WHERE line_key='gifts'") == 20)
r = q1("SELECT public.admin_gift_all_coins(10, 'promo', 'run-1')")
check("gift run replay is duplicate", r.get('duplicate') is True)
try:
    cur.execute("SELECT public.admin_gift_all_coins(10000, 'huge', 'run-2', ARRAY[%s])", (uid,))
    check("tiny case ok?", True)
except Exception as e:
    pass; check("unexpected gift failure", False, str(e))
cur.execute("UPDATE public.economy_supply_lines SET allowance=25 WHERE line_key='gifts'")
try:
    cur.execute("SELECT public.admin_gift_all_coins(10, 'promo', 'run-3')")
    check("over-budget gift run refused", False)
except Exception:
    pass; check("over-budget gift run refused", True)
cur.execute("UPDATE public.economy_supply_lines SET allowance=500000 WHERE line_key='gifts'")

# admin single adjustment draws from gifts line
r = q1("SELECT public.admin_apply_action('COINS', %s, '{\"delta\": 100}')", (uid,))
check("admin credit ok", r['success'] is True and float(r['details']['delta'])==100, str(r))
check("gifts issued 10120 (20 bulk + 10000 targeted + 100 admin)", q1("SELECT issued FROM public.economy_supply_lines WHERE line_key='gifts'") == 10120)
r = q1("SELECT public.admin_apply_action('COINS', %s, '{\"delta\": -50}')", (uid,))
check("admin debit ok", r['success'] is True and float(r['details']['delta'])==-50, str(r))

# lifetime cap clipping: set total near cap, claim clips to remainder
cur.execute("UPDATE public.economy_issuance SET total_minted = 19999995 WHERE id=1")
got = q1("SELECT public.mint_from_supply_line('claims', %s, 30, 'TEST_CLIP')", (uid,))
check("gate clips to remaining cap (5)", float(got) == 5, str(got))
got = q1("SELECT public.mint_from_supply_line('claims', %s, 30, 'TEST_CLIP2')", (uid,))
check("gate returns 0 at cap", float(got) == 0)
cur.execute("UPDATE public.economy_issuance SET total_minted = opening_count + (SELECT COALESCE(SUM(issued),0) FROM public.economy_supply_lines) WHERE id=1")

# transfer creates recipient wallet with starter from starter line
cur.execute("INSERT INTO auth.users(id, email) VALUES ('33333333-3333-3333-3333-333333333333', 'rcpt@x.com')")
r = q1("SELECT public.transfer_coins_atomic(%s, 'rcpt@x.com', 25)", (uid,))
check("transfer ok", r['success'] is True, str(r))
check("recipient got 120 starter + 25", q1("SELECT coin_balance FROM public.economy_profiles WHERE user_id='33333333-3333-3333-3333-333333333333'") == 145)

# paid reservations
cur.execute("SELECT set_config('test.role', 'service_role', false)")
r = q1("SELECT public.reserve_paid_coins(%s, 1000)", (uid,))
check("reservation held", r['success'] is True, str(r))
resid = r['reservation_id']
check("paid reserved 1000", q1("SELECT reserved FROM public.economy_supply_lines WHERE line_key='paid'") == 1000)
r = q1("SELECT public.release_paid_reservation(%s)", (resid,))
check("reservation released", r['success'] is True and q1("SELECT reserved FROM public.economy_supply_lines WHERE line_key='paid'") == 0)

# verified payment draws from paid line
cur.execute("INSERT INTO public.transactions(user_id, provider, currency, amount, metadata) VALUES (%s, 'payhero', 'KES', 100, '{\"coins_to_credit\": 500}') RETURNING id", (uid,))
txid = cur.fetchone()[0]
r = q1("SELECT public.apply_verified_payment(%s, 'payhero', 100, 'KES', 'ref-1')", (txid,))
check("payment credited from paid line", r['success'] is True and float(r['coins_credited'])==500, str(r))
check("paid line issued 500", q1("SELECT issued FROM public.economy_supply_lines WHERE line_key='paid'") == 500)
r = q1("SELECT public.apply_verified_payment(%s, 'payhero', 100, 'KES', 'ref-1')", (txid,))
check("payment replay is duplicate", r.get('duplicate') is True)
cur.execute("SELECT set_config('test.role', 'authenticated', false)")

# config guards
cur.execute("INSERT INTO public.app_config(key, value) VALUES ('payment_gateway', '{\"mpesaEnabled\": true, \"okxEnabled\": true, \"minDepositKes\": 49}') ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value")
try:
    cur.execute("SELECT public.admin_update_app_config('economy_multipliers', '{\"free\": 1}')")
    check("multiplier config retired", False)
except Exception:
    pass; check("multiplier config retired", True)
try:
    cur.execute("SELECT public.admin_update_app_config('payment_gateway', '{\"mpesaEnabled\": true}')")
    check("checkout enable refused", False)
except Exception:
    pass; check("checkout enable refused", True)

# --- extended boundary tests ---
# permission: non-admin treasury draw refused
cur.execute("SELECT set_config('test.is_admin', 'false', false)")
try:
    cur.execute("SELECT public.treasury_fund_reward(%s, 1, 'x')", (uid,))
    check("non-admin treasury draw refused", False)
except Exception:
    check("non-admin treasury draw refused", True)
cur.execute("SELECT set_config('test.is_admin', 'true', false)")

# permission: authenticated cannot call the gate or reservation functions
cur.execute("SET ROLE authenticated")
try:
    cur.execute("SELECT public.mint_from_supply_line('claims', %s, 1, 'X')", (uid,))
    check("authenticated cannot call mint gate", False)
except Exception:
    check("authenticated cannot call mint gate", True)
try:
    cur.execute("SELECT public.reserve_paid_coins(%s, 1)", (uid,))
    check("authenticated cannot reserve paid coins", False)
except Exception:
    check("authenticated cannot reserve paid coins", True)
try:
    cur.execute("SELECT issued FROM public.economy_supply_lines")
    check("authenticated cannot read supply lines", False)
except Exception:
    check("authenticated cannot read supply lines", True)
cur.execute("RESET ROLE")

# payment blocked when paid line cannot cover: no credit, stays pending, audited
cur.execute("UPDATE public.economy_supply_lines SET allowance = issued + 10 WHERE line_key='paid'")
cur.execute("SELECT set_config('test.role', 'service_role', false)")
cur.execute("INSERT INTO public.transactions(user_id, provider, currency, amount, metadata) VALUES (%s, 'payhero', 'KES', 100, '{\"coins_to_credit\": 50}') RETURNING id", (uid,))
tx2 = cur.fetchone()[0]
bal_before = q1("SELECT coin_balance FROM public.economy_profiles WHERE user_id=%s", (uid,))
r = q1("SELECT public.apply_verified_payment(%s, 'payhero', 100, 'KES', 'ref-2')", (tx2,))
check("blocked payment returns failure", r['success'] is False and r.get('credit_blocked') is True, str(r))
check("blocked payment credited nothing", q1("SELECT coin_balance FROM public.economy_profiles WHERE user_id=%s", (uid,)) == bal_before)
check("blocked payment stays pending", q1("SELECT status FROM public.transactions WHERE id=%s", (tx2,)) == 'pending')
check("blocked payment audited", q1("SELECT count(*) FROM public.admin_audit_logs WHERE admin_action='PAYMENT_CREDIT_BLOCKED' AND target_user_id=%s", (uid,)) == 1)
cur.execute("UPDATE public.economy_supply_lines SET allowance = 10000000 WHERE line_key='paid'")
cur.execute("SELECT set_config('test.role', 'authenticated', false)")

# floor: claims closed at step 9 -> daily claim refuses warmly, wallet untouched
cur.execute("UPDATE public.economy_supply_lines SET issued = 4990234.375 WHERE line_key='claims'")
cur.execute("DELETE FROM public.mining_records WHERE user_id=%s", (uid,))
cur.execute("UPDATE public.economy_profiles SET last_mined_date = NULL WHERE user_id=%s", (uid,))
r = q1("SELECT public.verify_and_mine_coins(%s, 'UTC')", (uid,))
check("claim refused at floor", r['success'] is False and 'resting' in r['message'], str(r))
check("no claim minted at floor", q1("SELECT issued FROM public.economy_supply_lines WHERE line_key='claims'") == 4990234.375)
cur.execute("UPDATE public.economy_supply_lines SET issued = 30 WHERE line_key='claims'")

# concurrency: two sessions race the same user's daily claim; exactly one wins
import threading
results = []
cur.execute("DELETE FROM public.mining_records WHERE user_id=%s", (uid,))
cur.execute("UPDATE public.economy_profiles SET last_mined_date = NULL WHERE user_id=%s", (uid,))
def race_claim(tag):
    c2 = psycopg2.connect(srv.get_uri()); c2.autocommit = True
    cur2 = c2.cursor()
    cur2.execute("SELECT set_config('test.uid', %s, false)", (uid,))
    cur2.execute("SELECT public.verify_and_mine_coins(%s, 'UTC')", (uid,))
    results.append(cur2.fetchone()[0]['success'])
    c2.close()
claims_before = q1("SELECT issued FROM public.economy_supply_lines WHERE line_key='claims'")
ta = threading.Thread(target=race_claim, args=('a',)); tb = threading.Thread(target=race_claim, args=('b',))
ta.start(); tb.start(); ta.join(); tb.join()
check("racing claims: exactly one success", sorted(results) == [False, True], str(results))
check("racing claims minted exactly one award", q1("SELECT issued FROM public.economy_supply_lines WHERE line_key='claims'") == claims_before + 30)

# reservation consumed exactly once by its fulfillment, and the held coins are drawable
cur.execute("SELECT set_config('test.role', 'service_role', false)")
cur.execute("UPDATE public.economy_supply_lines SET issued = 10000000 - 500, reserved = 0 WHERE line_key='paid'")
r = q1("SELECT public.reserve_paid_coins(%s, 500)", (uid,))
check("reserve last 500 of paid line", r['success'] is True, str(r))
resid2 = r['reservation_id']
cur.execute("INSERT INTO public.transactions(user_id, provider, currency, amount, metadata) VALUES (%s, 'payhero', 'KES', 100, %s) RETURNING id",
            (uid, '{\"coins_to_credit\": 500, \"reservation_id\": \"' + resid2 + '\"}'))
tx3 = cur.fetchone()[0]
r = q1("SELECT public.apply_verified_payment(%s, 'payhero', 100, 'KES', 'ref-3')", (tx3,))
check("fulfillment draws the reserved coins", r['success'] is True and float(r['coins_credited']) == 500, str(r))
check("reservation marked fulfilled", q1("SELECT status FROM public.economy_paid_reservations WHERE reservation_id=%s", (resid2,)) == 'FULFILLED')
check("reserved released after fulfill", q1("SELECT reserved FROM public.economy_supply_lines WHERE line_key='paid'") == 0)
check("paid line fully issued", q1("SELECT issued FROM public.economy_supply_lines WHERE line_key='paid'") == 10000000)
# second payment with same reservation id cannot reuse it
cur.execute("INSERT INTO public.transactions(user_id, provider, currency, amount, metadata) VALUES (%s, 'payhero', 'KES', 100, %s) RETURNING id",
            (uid, '{\"coins_to_credit\": 100, \"reservation_id\": \"' + resid2 + '\"}'))
tx4 = cur.fetchone()[0]
r = q1("SELECT public.apply_verified_payment(%s, 'payhero', 100, 'KES', 'ref-4')", (tx4,))
check("reservation cannot be reused (credit blocked)", r['success'] is False and r.get('credit_blocked') is True, str(r))
cur.execute("UPDATE public.economy_supply_lines SET issued = 500 WHERE line_key='paid'")

# concurrent reserves cannot over-book the paid line
cur.execute("UPDATE public.economy_supply_lines SET reserved = 0, allowance = issued + 1000 WHERE line_key='paid'")
print("DBG paid line:", q1("SELECT allowance FROM public.economy_supply_lines WHERE line_key='paid'"), q1("SELECT issued FROM public.economy_supply_lines WHERE line_key='paid'"), q1("SELECT reserved FROM public.economy_supply_lines WHERE line_key='paid'"), "HELD:", q1("SELECT count(*) FROM public.economy_paid_reservations WHERE status='HELD'"), q1("SELECT COALESCE(SUM(coins),0) FROM public.economy_paid_reservations WHERE status='HELD'"))
res_results = []
def race_reserve(tag):
    c2 = psycopg2.connect(srv.get_uri()); c2.autocommit = True
    cur2 = c2.cursor()
    cur2.execute("SELECT set_config('test.role', 'service_role', false)")
    cur2.execute("SELECT public.reserve_paid_coins(%s, 1000)", (uid,))
    res_results.append(cur2.fetchone()[0]['success'])
    c2.close()
ta = threading.Thread(target=race_reserve, args=('a',)); tb = threading.Thread(target=race_reserve, args=('b',))
ta.start(); tb.start(); ta.join(); tb.join()
check("racing reserves: exactly one succeeds", sorted(res_results) == [False, True], str(res_results))
cur.execute("UPDATE public.economy_paid_reservations SET status='RELEASED' WHERE status='HELD'")
cur.execute("UPDATE public.economy_supply_lines SET reserved = 0, allowance = 10000000 WHERE line_key='paid'")

# concurrent purchases of the last coins: never negative balance
cur.execute("UPDATE public.economy_profiles SET coin_balance = 100 WHERE user_id=%s", (uid,))
cur.execute("INSERT INTO public.cosmetic_catalog(item_id, kind, name, price_coins, is_active) VALUES ('race_item','FRAME','Race',100,true) ON CONFLICT (item_id) DO UPDATE SET is_active=true")
pur_results = []
def race_purchase(tag):
    c2 = psycopg2.connect(srv.get_uri()); c2.autocommit = True
    cur2 = c2.cursor()
    cur2.execute("SELECT set_config('test.uid', %s, false)", (uid,))
    cur2.execute("SELECT public.purchase_economy_item('race_item')")
    r2 = cur2.fetchone()[0]
    pur_results.append(r2['success'])
    c2.close()
# second wallet to make it a real race on different rows too
uid2 = "44444444-4444-4444-4444-444444444444"
cur.execute("INSERT INTO public.economy_profiles(user_id, coin_balance) VALUES (%s, 100) ON CONFLICT (user_id) DO UPDATE SET coin_balance=100", (uid2,))
cur.execute("SELECT set_config('test.uid', %s, false)", (uid,))
pa = threading.Thread(target=race_purchase, args=('a',)); pb = threading.Thread(target=race_purchase, args=('b',))
pa.start(); pb.start(); pa.join(); pb.join()
bal = q1("SELECT coin_balance FROM public.economy_profiles WHERE user_id=%s", (uid,))
check("racing same-wallet purchases: one wins, balance never negative", sorted(pur_results) == [False, True] and bal >= 0, str(pur_results)+" bal="+str(bal))

# concurrent treasury draws cannot overdraw
cur.execute("UPDATE public.economy_treasury SET balance = 50 WHERE id=1")
dr_results = []
def race_draw(tag):
    c2 = psycopg2.connect(srv.get_uri()); c2.autocommit = True
    cur2 = c2.cursor()
    cur2.execute("SELECT set_config('test.uid', %s, false)", (uid,))
    try:
        cur2.execute("SELECT public.treasury_fund_reward(%s, 50, 'race')", (uid,))
        dr_results.append(cur2.fetchone()[0]['success'])
    except Exception:
        dr_results.append(False)
    c2.close()
da = threading.Thread(target=race_draw, args=('a',)); db2 = threading.Thread(target=race_draw, args=('b',))
da.start(); db2.start(); da.join(); db2.join()
check("racing treasury draws: exactly one wins", sorted(dr_results) == [False, True], str(dr_results))
check("treasury never negative", q1("SELECT balance FROM public.economy_treasury") >= 0)
cur.execute("SELECT set_config('test.role', 'authenticated', false)")

db.commit()
print("\n%d failures" % len(fails))
sys.exit(1 if fails else 0)
