#!/usr/bin/env python3
"""Mandatory required definitions + both-APK j$ type closure; flat report separate.
Reports historical non-j$ test type gaps, NOT a replacement for independently
reviewed executable reachability. Requires all j$ type_ids from BOTH APKs defined.
Not runtime, reflection, native-name or classloader acceptance.
"""
import importlib.util,sys
from pathlib import Path
spec=importlib.util.spec_from_file_location('flat',Path(__file__).with_name('lab-dex-closure.py'));f=importlib.util.module_from_spec(spec);spec.loader.exec_module(f)
app,test,jar=sys.argv[1:];at,ad=f.parse_apk(app);tt,td=f.parse_apk(test);platform=f.jar_classes(jar);defs=ad|td
required_t=set(f.REQUIRED_TEST_DEFS)|{'Lcom/cyclecare/app/RustCalendarLabInstrumentedTest;'}
required_a=set(f.REQUIRED_APP_DEFS)|{'Lcom/cyclecare/app/lab/RustCalendarLabActivity;'}
missing_req=sorted((required_t-td)|(required_a-ad))
elem=lambda x:x.lstrip('[')
missing_nonj=sorted(x for x in {elem(t) for t in tt}-defs-platform-f.ALLOWLIST if x.startswith('L') and not x.startswith('Lj$/'))
missing_j=sorted(x for x in {elem(t) for t in at|tt}-defs if x.startswith('Lj$/'))
for label,values in [('required-def',missing_req),('test-nonj-type',missing_nonj),('both-j-type',missing_j)]:
 print(label,len(values))
 for x in values:print('MISSING',x)
print('nonj$ type-only gaps are diagnostic, subject to separate executable reachability review')
raise SystemExit(bool(missing_req or missing_j))
