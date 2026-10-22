"""Isolated atomic migration regression, definitions and all test writes rolled back."""
import json,os,uuid,urllib.request
base='https://apjqcocxtylsofgxhetb.supabase.co';query='https://api.supabase.com/v1/projects/apjqcocxtylsofgxhetb/database/query';key='sb_publishable_ziAlyPmKSv7kQZjbpbIuwA_6er-NufM';token=open('/home/sandbox/.vault/supabase-access-token-cyclecare-project-f83c16/password').read().strip();users=[]
def post(url,data,headers):return json.load(urllib.request.urlopen(urllib.request.Request(url,data=json.dumps(data).encode(),headers={**headers,'Content-Type':'application/json'}),timeout=45))
def sql(q):return post(query,{'query':q},{'Authorization':'Bearer '+token})
try:
 for i in range(2):
  u=post(base+'/auth/v1/signup',{'email':'instinct-ledger-'+uuid.uuid4().hex[:10]+'@example.com','password':uuid.uuid4().hex+'A!'},{'apikey':key});users.append(u)
 a,b=[u['user']['id'] for u in users];email=users[1]['user']['email'];tx=str(uuid.uuid4())
 migration=open('supabase/migrations/20261022233000_wallet_complete_ledger.sql').read() if os.environ.get('TEST_MIGRATION')=='1' else ''
 q=f"""BEGIN; {migration}
 INSERT INTO public.economy_profiles(user_id,coin_balance,lifetime_earned,lifetime_spent,unlocked_items) VALUES('{a}',2000,2000,0,'[]'),('{b}',120,120,0,'[]');
 SELECT set_config('request.jwt.claim.sub','{a}',true),set_config('request.jwt.claim.role','authenticated',true);
 DO $$ DECLARE r JSONB; n INTEGER; BEGIN
 r:=public.purchase_economy_item('artifact_moon'); IF r->>'success'<>'true' THEN RAISE EXCEPTION 'purchase failed %',r; END IF;
 r:=public.purchase_economy_item('artifact_moon'); IF r->>'success'<>'false' THEN RAISE EXCEPTION 'duplicate purchase accepted'; END IF;
 SELECT count(*) INTO n FROM public.coin_transactions WHERE user_id='{a}' AND action='PURCHASE'; IF n<>1 THEN RAISE EXCEPTION 'purchase receipt count %',n; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.coin_transactions WHERE user_id='{a}' AND action='PURCHASE' AND delta=-250 AND balance_after=1750 AND metadata->>'item_id'='artifact_moon' AND metadata->>'paid_price_coins'='250') THEN RAISE EXCEPTION 'purchase details mismatch'; END IF;
 r:=public.transfer_coins_atomic('{a}','{email}',99); IF r->>'success'<>'true' THEN RAISE EXCEPTION 'transfer failed %',r; END IF;
 IF (SELECT count(*) FROM public.coin_transactions WHERE user_id IN ('{a}','{b}') AND action IN ('TRANSFER_SENT','TRANSFER_RECEIVED'))<>2 THEN RAISE EXCEPTION 'transfer receipt count'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.coin_transactions WHERE user_id='{a}' AND action='TRANSFER_SENT' AND delta=-99 AND balance_after=1651 AND metadata->>'sender_id'='{a}' AND metadata->>'receiver_id'='{b}') THEN RAISE EXCEPTION 'sender receipt mismatch'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.coin_transactions WHERE user_id='{b}' AND action='TRANSFER_RECEIVED' AND delta=99 AND balance_after=219) THEN RAISE EXCEPTION 'receiver receipt mismatch'; END IF;
 IF (SELECT count(distinct metadata->>'transfer_id') FROM public.coin_transactions WHERE user_id IN ('{a}','{b}') AND action LIKE 'TRANSFER_%')<>1 THEN RAISE EXCEPTION 'transfer pair mismatch'; END IF;
 IF (SELECT sum(delta) FROM public.coin_transactions WHERE user_id IN ('{a}','{b}') AND action LIKE 'TRANSFER_%')<>0 THEN RAISE EXCEPTION 'transfer not balanced'; END IF;
 END $$;
 SELECT set_config('request.jwt.claim.role','service_role',true);
 INSERT INTO public.transactions(id,user_id,external_reference,provider,amount,currency,status,metadata) VALUES('{tx}','{a}','test-{tx}','paypal',1,'USD','pending','{{"coins_to_credit":100}}');
 DO $$ DECLARE r JSONB; BEGIN
 r:=public.apply_verified_payment('{tx}','paypal',1,'USD','test-receipt'); IF r->>'success'<>'true' THEN RAISE EXCEPTION 'payment failed %',r; END IF;
 r:=public.apply_verified_payment('{tx}','paypal',1,'USD','test-receipt'); IF r->>'duplicate'<>'true' THEN RAISE EXCEPTION 'payment replay failed'; END IF;
 IF (SELECT count(*) FROM public.coin_transactions WHERE user_id='{a}' AND action='PAYMENT_CREDIT')<>1 THEN RAISE EXCEPTION 'payment receipt count'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.coin_transactions WHERE user_id='{a}' AND action='PAYMENT_CREDIT' AND delta=100 AND balance_after=1751 AND metadata->>'transaction_id'='{tx}') THEN RAISE EXCEPTION 'payment receipt mismatch'; END IF;
 IF (SELECT coin_balance FROM public.economy_profiles WHERE user_id='{a}')<>1751 OR (SELECT coin_balance FROM public.economy_profiles WHERE user_id='{b}')<>219 THEN RAISE EXCEPTION 'wallet mismatch'; END IF;
 IF EXISTS(SELECT 1 FROM public.coin_transactions c JOIN public.economy_profiles e USING(user_id) WHERE c.user_id IN ('{a}','{b}') AND c.id=(SELECT id FROM public.coin_transactions WHERE user_id=c.user_id ORDER BY created_at DESC,id DESC LIMIT 1) AND c.balance_after<0) THEN RAISE EXCEPTION 'invalid receipt'; END IF;
 END $$;
 SELECT user_id,action,delta,balance_after,metadata FROM public.coin_transactions WHERE user_id IN ('{a}','{b}') ORDER BY user_id,action;
 ROLLBACK;"""
 # Management API returns the final result set only. Assertions raise on failure.
 sql(q);print('PASS purchase once, duplicate purchase no extra row; paired transfer; payment once and replay; transaction rolled back')
finally:
 for u in users:
  uid=u['user']['id'];sql(f"delete from public.economy_profiles where user_id='{uid}'; delete from public.profiles where user_id='{uid}'; delete from auth.users where id='{uid}'")
 print('Fixture cleanup completed')
