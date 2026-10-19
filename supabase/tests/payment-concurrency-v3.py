#!/usr/bin/env python3
"""Run two concurrent create calls and assert one intent/transaction is returned.
Requires psql and PAYMENT_TEST_DSN (or the documented local follow-up socket).
"""
import os, subprocess, threading, json, sys
DSN=os.environ.get("PAYMENT_TEST_DSN", "host=/workspace/cyclecare-fixes/release-hardening-20261019 port=55439 user=ubuntu dbname=cyclecare_payment_followup")
SQL="""SET ROLE service_role; SELECT public.create_payment_intent('concurrency-owner','paypal','CC_concurrency-owner_same-request',699,'USD',jsonb_build_object('package_id','pkg_sanctuary','coins_to_credit',850));"""
out=[None,None]
def run(i):
    p=subprocess.run(["psql",DSN,"-X","-Atq","-v","ON_ERROR_STOP=1","-c",SQL],text=True,capture_output=True)
    out[i]=(p.returncode,p.stdout.strip(),p.stderr.strip())
ts=[threading.Thread(target=run,args=(i,)) for i in range(2)]
for t in ts:t.start()
for t in ts:t.join()
if any(code!=0 for code,_,_ in out):
    raise SystemExit("concurrent create failed: "+repr(out))
rows=[json.loads(x[1]) for x in out]
ids={r["payment_intent_id"] for r in rows}; txs={r["transaction_id"] for r in rows}
if len(ids)!=1 or len(txs)!=1 or not all(r["decision"] in ("created","duplicate") for r in rows):
    raise SystemExit("idempotency invariant failed: "+repr(rows))
print(json.dumps({"ok":True,"intent_id":next(iter(ids)),"transaction_id":next(iter(txs)),"results":rows}))
