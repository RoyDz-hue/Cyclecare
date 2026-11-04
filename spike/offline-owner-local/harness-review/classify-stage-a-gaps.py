"""Site classification for exact Stage A 47 gaps, NOT future binary acceptance."""
import json,re,zipfile,sys
from pathlib import Path
from loguru import logger
from androguard.core.dex import DEX
logger.remove()
p=Path(sys.argv[1]);gaps=re.findall(r'^MISSING (L\S+;)',(p/'bundle/build-logs/lab-current-type-closure.txt').read_text(),re.M)
assert len(gaps)==47
gapset=set(gaps);sites={g:[] for g in gaps}
for side in ['app','test']:
 z=zipfile.ZipFile(p/(side+'.apk'))
 for n in z.namelist():
  if not(n.startswith('classes') and n.endswith('.dex')):continue
  for c in DEX(z.read(n)).get_classes():
   for m in c.get_methods():
    code=m.get_code()
    if code:
     for ins in code.get_bc().get_instructions():
      text=ins.get_output()
      for g in gapset:
       if g in text:sites[g].append({'side':side,'class':c.get_name(),'method':m.get_name(),'opcode':ins.get_name(),'operand':text})
metadata=('Landroidx/annotation/','Ldalvik/annotation/','Lorg/jetbrains/annotations/','Lcom/google/errorprone/','Lkotlin/ReplaceWith;','Lkotlin/jvm/JvmName;','Lkotlin/jvm/JvmStatic;','Lkotlin/jvm/internal/SourceDebugExtension;','Landroidx/compose/runtime/internal/StabilityInferred;')
report=[]
for g in gaps:
 label='metadata-only type gap' if g.startswith(metadata) and not sites[g] else ('API-dependent PixelCopy instruction sites' if 'PixelCopy' in g else 'executable/helper gap: callsites need reachability review')
 report.append({'type':g,'classification':label,'instructionSites':sites[g]})
out={'artifact':'c52af723f58793e3e6cf3ba16efcde52d12384d1','gapCount':47,'gaps':report,'newHarnessBoundary':'New Java test calls only public LabEncryptedHostProof.run(Context,String,String):String. No Kotlin/coroutine/Room return types cross APK boundary. New app facade calls coroutine/Room/SQLCipher internally; no newly built binary exists. Source/API compile and old binary site classification cannot establish optimized new executable closure. Mandatory new artifact callsite/member/reachability review before any device.'}
# Class-level conservative test-DEX graph from unchanged runner/JUnit roots.
# The NEW Java test adds only JDK/Android IO and app-facade edge; no capture edge.
refs={};tok=re.compile(r'L[A-Za-z0-9_$/\-\.]+;')
z=zipfile.ZipFile(p/'test.apk')
for n in z.namelist():
 if not(n.startswith('classes') and n.endswith('.dex')):continue
 for c in DEX(z.read(n)).get_classes():
  dest=set();refs[c.get_name()]=dest
  for v in [c.get_superclassname()]+list(c.get_interfaces() or []):
   if v:dest.update(tok.findall(v))
  for f in c.get_fields():dest.update(tok.findall(f.get_descriptor()))
  for m in c.get_methods():
   dest.update(tok.findall(m.get_descriptor()))
   code=m.get_code()
   if code:
    for ins in code.get_bc().get_instructions():dest.update(tok.findall(ins.get_output()))
roots=['Landroidx/test/runner/AndroidJUnitRunner;','Landroidx/test/ext/junit/runners/AndroidJUnit4;'];seen=set();pending=list(roots)
while pending:
 c=pending.pop()
 if c in seen:continue
 seen.add(c);pending.extend(t for t in refs.get(c,set()) if t in refs and t not in seen)
for row in out['gaps']:
 row['unchangedRunnerRootInstructionSites']=[x for x in row['instructionSites'] if x['side']=='test' and x['class'] in seen]
out['unchangedRunnerClassClosureCount']=len(seen)
out['runnerExecutableGapTypes']=[r['type'] for r in out['gaps'] if r['unchangedRunnerRootInstructionSites']]
print(json.dumps(out,indent=2))
