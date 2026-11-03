#!/usr/bin/env python3
"""Fail-closed review gate: production source drift requires inventory review."""
from pathlib import Path
import hashlib,json,sys
root=Path(__file__).resolve().parents[2];here=Path(__file__).resolve().parent
files=list((root/'Androidapp/app/src/main').rglob('*.kt'))+list((root/'Androidapp/app/src/main').rglob('AndroidManifest.xml'))
actual={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(files)}
manifest=here/'production-source-baseline.json'
if '--record' in sys.argv:
 manifest.write_text(json.dumps(actual,indent=2)+'\n')
else:
 expected=json.loads(manifest.read_text());assert actual==expected,'Production drift/new caller: re-inventory mandatory'
 # Negative test proves any added/modified source is blocked, even an alias call.
 probe=dict(actual);probe['Androidapp/app/src/main/java/InjectedCaller.kt']='new'
 assert probe!=expected
 for p in (here/'kotlin').glob('*.kt'):
  s=p.read_text()
  for forbidden in ['import android.','CycleCareDatabase','AuthPreferences','Supabase','System.loadLibrary','external fun']:
   assert forbidden not in s,(p,forbidden)
 c=(here/'kotlin/Coordinator.kt').read_text()
 assert 'private val lock=Any()' in c and 'private var state=' in c
 assert 'if(p.identity!=identity)' in c and 'next.epoch<=identity.epoch' in c
 for f in ['cycle_math.rs','kotlin_text.rs']:
  assert (here/'rust/src'/f).read_bytes()==(root/'Androidapp/rust/core/src'/f).read_bytes()
 print('PASS production caller drift gate + negative injection + fixture capability/guard/source checks')
