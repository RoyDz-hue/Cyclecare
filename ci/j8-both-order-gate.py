#!/usr/bin/env python3
"""Core-desugar (j$) cross-APK gate for an app + androidTest APK pair.
Usage: j8-both-order-gate.py APP.apk TEST.apk [--json OUT]
Static only. Does not prove runtime, loader order, reflection or method bodies.
Checks (all must pass, exit 1 otherwise):
 1. Both classpath orders (first definition of a descriptor wins): no class
    implements a non-interface, no class has an interface as superclass, no
    j$ member reference (invoke/field) from ANY class of either APK fails to
    resolve through the effective hierarchy.
 2. Structural identity of every duplicate j$ descriptor: same kind, super and
    interfaces; test copy methods/fields are a SUPERSET of the app copy.
 3. Reports duplicate counts and, for information, class-kind flips.
Only java.lang.Object/Enum members that are genuinely inherited from the platform
(clone, getClass, hashCode, equals, toString, finalize, wait, notify, notifyAll,
and Enum ordinal/name/compareTo/getDeclaringClass) are not counted as unresolved,
and only when the hierarchy walk reaches a platform superclass.
"""
import sys,re,zipfile,json
from loguru import logger; logger.remove()
from androguard.core.dex import DEX
ref=re.compile(r'(Lj\$/[^;\s]+;)->([^\(\s:]+)(\([^\)]*\)\S+)?')
OBJ={'clone','getClass','hashCode','equals','toString','finalize','wait','notify','notifyAll','ordinal','name','compareTo','getDeclaringClass'}
def load(p):
    out=[]
    z=zipfile.ZipFile(p)
    for n in sorted(z.namelist()):
        if n.startswith('classes') and n.endswith('.dex'):
            for c in DEX(z.read(n)).get_classes():
                refs=[]
                for m in c.get_methods():
                    code=m.get_code()
                    if code:
                        for i in code.get_bc().get_instructions():
                            if i.get_name().startswith(('invoke','sget','sput','iget','iput')):
                                r=ref.search(i.get_output())
                                if r:refs.append((r.group(1),r.group(2),(r.group(3) or '').replace(' ',''),i.get_name().startswith('invoke')))
                out.append((c.get_name(),dict(flags=c.get_access_flags(),sup=c.get_superclassname(),ifs=sorted(c.get_interfaces() or []),
                  ms={(m.get_name(),m.get_descriptor().replace(' ','')) for m in c.get_methods()},
                  fs={(f.get_name(),f.get_descriptor()) for f in c.get_fields()},refs=refs)))
    return out
A=load(sys.argv[1]);T=load(sys.argv[2])
Ad={n:d for n,d in A if n.startswith('Lj$/')};Td={n:d for n,d in T if n.startswith('Lj$/')}
report={'app_j$_defs':len(Ad),'test_j$_defs':len(Td)}
dups=sorted(set(Ad)&set(Td));report['duplicates']=len(dups)
viol=[]
flips=[n for n in dups if (Ad[n]['flags']&0x200)!=(Td[n]['flags']&0x200)]
report['kind_flips']=flips
for n in dups:
    a,t=Ad[n],Td[n]
    if (a['flags']&0x200)!=(t['flags']&0x200): viol.append(('identity:kind differs',n))
    if a['sup']!=t['sup'] or a['ifs']!=t['ifs']: viol.append(('identity:hierarchy differs',n))
    if not a['ms']<=t['ms']: viol.append(('identity:test missing app methods',n,len(a['ms']-t['ms'])))
    if not a['fs']<=t['fs']: viol.append(('identity:test missing app fields',n,len(a['fs']-t['fs'])))
for label,order in (('test-first',T+A),('app-first',A+T)):
    eff={}
    for n,d in order: eff.setdefault(n,d)
    for n,d in eff.items():
        if d['sup'] in eff and eff[d['sup']]['flags']&0x200: viol.append((label+':superclass is interface',n,d['sup']))
        for i in d['ifs']:
            if i.startswith('Lj$/') and i in eff and not eff[i]['flags']&0x200: viol.append((label+':implements non-interface',n,i))
    for n,d in A+T:
        for owner,name,desc,inv in d['refs']:
            if owner not in eff or name=='<init>' and False: continue
            seen=set();st=[owner];ok=False;platform=False
            while st and not ok:
                x=st.pop()
                if x in seen:continue
                seen.add(x)
                if x not in eff:
                    platform=True;continue
                e=eff[x]
                if inv and (name,desc) in e['ms']:ok=True
                if not inv and any(f[0]==name for f in e['fs']):ok=True
                st.append(e['sup']);st+=e['ifs']
            if not ok and platform and name in OBJ: ok=True
            if not ok: viol.append((label+':unresolved j$ member',n,owner+'.'+name+desc))
report['violations']=len(viol)
from collections import Counter
report['by_kind']=dict(Counter(v[0] for v in viol))
report['samples']=[list(v) for v in viol[:25]]
print(json.dumps({k:v for k,v in report.items() if k!='samples'},indent=1))
for v in viol[:8]:print(' ',v)
if '--json' in sys.argv:
    json.dump(dict(report,all=[list(v) for v in viol]),open(sys.argv[sys.argv.index('--json')+1],'w'),indent=1)
sys.exit(1 if viol else 0)
