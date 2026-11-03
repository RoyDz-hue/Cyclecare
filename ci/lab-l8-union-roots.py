#!/usr/bin/env python3
"""Lab APK root audit. No runtime/reflection/JNI proof. Pin baseline and dependencies.
Generate: SCRIPT APP TEST --write OUT; verify drift: SCRIPT APP TEST --check KEEP
Scans non-j$ hierarchy, fields, methods, operands, catches, static values and
class/field/method/parameter annotations (including generic Signature strings).
Rejects unsafe-chain roots, empty sets, and unparsed annotation encodings.
"""
import argparse,re,zipfile,json
from loguru import logger;logger.remove()
from androguard.core.dex import DEX,EncodedAnnotation,EncodedArray
tok=re.compile(r'Lj\$/[A-Za-z0-9_$/]+(?=[;<])')
bad=('Lj$/sun/nio/cs','Lj$/sun/misc/DesugarUnsafe','Lj$/util/Base64')
def scan_classes(classes,cm):
 out=set();counts={}
 def text(x,kind):
  found={n+';' for n in tok.findall(str(x))};out.update(found);counts[kind]=counts.get(kind,0)+len(found)
 def ann(x,cm):
  text(cm.get_type(x.get_type_idx()),'annotation-type')
  for e in x.get_elements(): value(e.get_value(),cm)
 def value(x,cm):
  v=x.get_value(); t=x.get_value_type()
  if t==0x1c:
   for y in v.get_values():value(y,cm)
  elif t==0x1d:ann(v,cm)
  elif t in (0x17,0x18,0x19,0x1a,0x1b):
   # Pinned androguard resolves these indexes in EncodedValue.__init__.
   # Reject unexpected raw indexes instead of silently dropping future drift.
   if isinstance(v,int):raise ValueError('Unresolved annotation/static index '+hex(t))
   text(v,'annotation-or-static-value')
  elif t not in (0,2,3,4,6,0x10,0x11,0x1e,0x1f):raise ValueError('Unparsed encoded value '+hex(t))
 def aset(off,cm):
  if off:
   for x in cm.get_annotation_set_item(off).get_annotation_off_item():ann(x.get_annotation_item().get_annotation(),cm)
 for c in classes:
  if c.get_name().startswith('Lj$/'):continue
  text(c.get_superclassname(),'hierarchy');text(c.get_interfaces(),'hierarchy')
  for f in c.get_fields():text(f.get_descriptor(),'field-descriptor')
  if c.get_static_values_off():
   for v in cm.get_encoded_array_item(c.get_static_values_off()).get_value().get_values():value(v,cm)
  ad=c.annotations_directory_item
  if ad:
   aset(ad.get_class_annotations_off(),cm)
   for x in ad.get_field_annotations()+ad.get_method_annotations():aset(x.get_annotations_off(),cm)
   for x in ad.get_parameter_annotations():
    for y in cm.get_obj_by_offset(x.get_annotations_off()).get_list():aset(y.get_annotations_off(),cm)
  for m in c.get_methods():
   text(m.get_descriptor(),'method-descriptor');code=m.get_code()
   if code:
    for i in code.get_bc().get_instructions():text(i.get_output(),'operand')
    h=code.get_handlers()
    if h:
     for ch in h.get_list():
      for pair in ch.get_handlers():text(cm.get_type(pair.get_type_idx()),'catch-type')
 return out,counts
def refs(path):
 out=set();counts={}
 with zipfile.ZipFile(path) as z:
  dexnames=[n for n in sorted(z.namelist()) if re.fullmatch(r'classes(?:[2-9][0-9]*)?\.dex',n)]
  if not dexnames:raise ValueError('No DEX files: '+str(path))
  for n in dexnames:
   d=DEX(z.read(n));roots,cs=scan_classes(d.get_classes(),d.CM);out.update(roots)
   for k,v in cs.items():counts[k]=counts.get(k,0)+v
 return out,counts

def keep_set(text):
 out=set()
 for line in text.splitlines():
  if not line.strip() or line.startswith('#'):continue
  m=re.fullmatch(r'-keep class (j\$\.[A-Za-z0-9_.$]+) \{ \*; \}',line)
  if not m:raise ValueError('Unexpected keep-file content: '+line)
  root='L'+m[1].replace('.','/')+';'
  if root in out:raise ValueError('Duplicate root '+root)
  out.add(root)
 return out

def validate(A,T):
 if not A or not T:raise ValueError('FAIL empty APK root set')
 if any(x.startswith(bad) for x in A|T):raise ValueError('FAIL unsafe charset/Unsafe/Base64 root')
 return A|T

def main():
 p=argparse.ArgumentParser();p.add_argument('app');p.add_argument('test');g=p.add_mutually_exclusive_group(required=True);g.add_argument('--write');g.add_argument('--check');a=p.parse_args()
 A,ac=refs(a.app);T,tc=refs(a.test);U=A|T
 U=validate(A,T)
 lines=['# LAB ONLY. Generated from actual app+test non-j$ references; reviewed baseline only.']+['-keep class %s { *; }'%x[1:-1].replace('/','.') for x in sorted(U)]
 if a.write:open(a.write,'w').write('\n'.join(lines)+'\n')
 else:
  expected=keep_set(open(a.check).read())
  if U!=expected:raise SystemExit('FAIL root drift '+json.dumps({'added':sorted(U-expected),'removed':sorted(expected-U)}))
 print(json.dumps({'root_count':len(U),'app_count':len(A),'test_count':len(T),'test_only':sorted(T-A),'app_scan':ac,'test_scan':tc},indent=2))

if __name__=='__main__':main()
