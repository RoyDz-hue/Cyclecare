from pathlib import Path
import sqlite3,json,tempfile,hashlib
here=Path(__file__).resolve().parent
count=0
def check(x):
 global count
 assert x
 count+=1
with tempfile.TemporaryDirectory(prefix='synthetic-receipt-') as d:
 p=Path(d)/'synthetic.sqlite';c=sqlite3.connect(p)
 # Minimal synthetic version9 sentinel, not claimed full production schema.
 c.executescript('CREATE TABLE synthetic_existing(record TEXT PRIMARY KEY,payload TEXT); INSERT INTO synthetic_existing VALUES("sentinel","synthetic-only"); PRAGMA user_version=9;')
 c.executescript((here/'receipt-9-to-10.sql').read_text());c.execute('PRAGMA user_version=10');c.commit()
 check(c.execute('SELECT payload FROM synthetic_existing').fetchone()[0]=='synthetic-only')
 payload=json.dumps({'version':1,'outcome':'Committed','records':[{'record':'2024-03-01','revision':1,'hash':'a'*64,'tombstone':False}]},separators=(',',':'))
 receipt=('synthetic-A','op-1','b'*64,1,payload,1234)
 c.execute('INSERT INTO local_commit_receipts VALUES(?,?,?,?,?,?)',receipt);c.commit();c.close()
 c=sqlite3.connect(p);check(tuple(c.execute('SELECT * FROM local_commit_receipts').fetchone())==receipt)
 try:c.execute('INSERT INTO local_commit_receipts VALUES(?,?,?,?,?,?)',receipt);raise AssertionError('duplicate accepted')
 except sqlite3.IntegrityError:c.rollback();check(True)
 c.execute('INSERT INTO local_commit_receipts VALUES(?,?,?,?,?,?)',('synthetic-B',*receipt[1:]));c.commit();check(c.execute('SELECT count(*) FROM local_commit_receipts').fetchone()[0]==2)
 for fault in range(1,8):
  try:
   c.execute('BEGIN');c.execute('UPDATE synthetic_existing SET payload="changed"')
   if fault==1:raise RuntimeError()
   c.execute('INSERT INTO local_commit_receipts VALUES(?,?,?,?,?,?)',('synthetic-A',f'fault-{fault}','c'*64,1,payload,1234))
   raise RuntimeError()
  except RuntimeError:c.rollback()
  check(c.execute('SELECT payload FROM synthetic_existing').fetchone()[0]=='synthetic-only');check(c.execute('SELECT count(*) FROM local_commit_receipts WHERE operationId=?',(f'fault-{fault}',)).fetchone()[0]==0)
 check(c.execute('PRAGMA user_version').fetchone()[0]==10)
 cols=c.execute('PRAGMA table_info(local_commit_receipts)').fetchall();check([x[1] for x in cols]==['ownerNamespace','operationId','intentDigest','resultVersion','resultJson','committedAt'])
 c.close()
 print(json.dumps({'status':'PASS','assertions':count,'sqlite':sqlite3.sqlite_version,'scope':'synthetic standard SQLite schema draft roundtrip/rollback only, NOT Room/SQLCipher/schema9 migration acceptance','ddlSHA256':hashlib.sha256((here/'receipt-9-to-10.sql').read_bytes()).hexdigest()},indent=2))
