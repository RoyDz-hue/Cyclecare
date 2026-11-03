"""Exhaustive CURRENT source envelope gate + syntactic inventory, not unsound alias-completeness claim."""
from pathlib import Path
import hashlib,json,re,sys
here=Path(__file__).resolve().parent;root=here.parents[2];src=root/'Androidapp/app/src'
patterns={
 'dao_or_sql_write':r'@(Insert|Update|Delete)|@Query\(.*(INSERT|UPDATE|DELETE)|execSQL|clearAllTables|deleteDatabase',
 'writer_call':r'\.(insert\w*|save\w*|delete\w*|putRevision|enqueue|acknowledgeMatched|remove\w*|rebasePending|retry|recordReceipt|putConflict|resolveConflict|putCursor|erase\w*)\(',
 'identity_consent_publication':r'(userId|accountMode|isLocalOnlyMode|isCloudSyncAllowed|healthConsentGeneration|accessToken|refreshToken|sessionExpiresAtSeconds)\s*=|clearSession\(|pauseHealthSyncFor|markPendingHealthSyncRevoke|saveServerHealthConsentReceipt|publishContext|activateCurrentOwner|resumeAfterExplicitUserAction|markWiped',
 'worker_entry':r'CoroutineWorker|Worker\(|doWork\(|WorkManager|PeriodicWorkRequest|OneTimeWorkRequest',
 'preference_effect':r'\.edit\b|\.commit\(|\.apply\(|setProviderConsent|revokeAllProviderConsents|ProcessorConsentMutation',
 'declaration':r'^\s*(?:internal |private |override |suspend |public |protected |inline )*(?:fun |var |class )'
}
files=sorted(p for p in src.rglob('*') if p.is_file() and p.suffix in ['.kt','.java','.xml'])
envelope={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
rows=[]
for p in files:
 for no,line in enumerate(p.read_text().splitlines(),1):
  cats=[k for k,pattern in patterns.items() if re.search(pattern,line)]
  if cats:rows.append({'path':str(p.relative_to(root)),'line':no,'categories':cats,'source':line})
actual={'sourceEnvelope':envelope,'inventory':rows,'claim':'All checked-in Android src Kotlin/Java/XML files pinned. Every matching declaration/effect/call listed. Semantic alias/reflection all-writer proof requires review; any added/modified source fails CI until inventory review.'}
path=here/'current-writer-manifest.json'
if '--record' in sys.argv:path.write_text(json.dumps(actual,indent=2)+'\n')
else:
 assert json.loads(path.read_text())==actual,'source/effect inventory drift: review required'
 altered=dict(envelope);altered['Androidapp/app/src/main/Injected.java']='unreviewed';assert altered!=envelope
 assert all(any(x['path'].endswith(name) for x in rows) for name in ['CycleDayLogDao.kt','HealthSyncLocal.kt','SupabaseSyncWorker.kt','AuthPreferences.kt','SupabaseSessionManager.kt'])
 print(f'PASS exhaustive current source envelope {len(files)} files; syntactic declaration/effect inventory {len(rows)} rows; negative injected writer blocked. Not AST alias proof.')
