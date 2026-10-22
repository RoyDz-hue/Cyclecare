"""Isolated live regression. Requires explicit authorized test project credentials.
Always cleans every fixture in finally, including failures. Never approves a plan.
"""
import json,os,uuid,urllib.request
base=os.environ['TEST_SUPABASE_URL'];mgmt=os.environ['TEST_SUPABASE_QUERY_URL'];key=os.environ['TEST_SUPABASE_PUBLIC_KEY'];token=open(os.environ['TEST_SUPABASE_TOKEN_FILE']).read().strip();users=[]
def post(url,data,headers):
 return json.load(urllib.request.urlopen(urllib.request.Request(url,data=json.dumps(data).encode(),headers={**headers,'Content-Type':'application/json'}),timeout=45))
def sql(q):return post(mgmt,{'query':q},{'Authorization':'Bearer '+token})
try:
 for name in ['SelectionKevin Alpha','SelectionKevin Beta']:
  u=post(base+'/auth/v1/signup',{'email':'instinct-selection-'+uuid.uuid4().hex[:10]+'@example.com','password':uuid.uuid4().hex+'A!'},{'apikey':key});users.append(u);uid=u['user']['id'];sql(f"insert into profiles(user_id,display_name,account_mode) values('{uid}','{name}','EMAIL')")
 uid=users[0]['user']['id'];sql(f"insert into cyclecare_admin_roles(user_id) values('{uid}')")
 def cmd(text):return post(base+'/functions/v1/admin-command',{'action':'message','prompt':text},{'Authorization':'Bearer '+users[0]['access_token']})
 for choice in ['2','number 2','the second one','Beta']:
  cmd('cancel');r=cmd('Deduct 1000 coins from SelectionKevin');assert r['type']=='clarify' and 'SelectionKevin Beta' in r['message'];r=cmd(choice);assert 'SelectionKevin Beta' in r['message'];r=cmd('Help find a reason');assert r['requires_approval'] and r['plan']['steps'][0]['args']['targets']==[users[1]['user']['id']];print(choice,'selected Beta, reason Admin adjustment, approval held')
 ids=','.join("'"+u['user']['id']+"'" for u in users);count=sql(f"select count(*) as n from coin_transactions where user_id in ({ids})")[0]['n'];assert count==0;print('No coin transactions:',count)
finally:
 for u in users:
  uid=u['user']['id'];qs=[]
  for table,col in [('admin_coin_dialog_state','admin_user_id'),('admin_command_messages','admin_user_id'),('admin_command_runs','admin_user_id'),('ai_runs','user_id'),('user_notifications','user_id'),('economy_profiles','user_id'),('profiles','user_id'),('cyclecare_admin_roles','user_id')]:qs.append(f"delete from {table} where {col}::text='{uid}'")
  qs.append(f"delete from auth.users where id='{uid}'");sql(';'.join(qs))
 print('Fixture cleanup completed')
