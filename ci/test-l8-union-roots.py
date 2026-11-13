#!/usr/bin/env python3
"""Selected scanner fixtures plus actual DEX parser index-resolution controls.
Does not substitute for actual-pair root equality or strict both-order gate.
"""
import importlib.util,io,struct
from types import SimpleNamespace as N
from pathlib import Path
from androguard.core.dex import EncodedValue
spec=importlib.util.spec_from_file_location('roots',Path(__file__).with_name('lab-l8-union-roots.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class Obj:
 def __init__(self,**kwargs):self.__dict__.update(kwargs)
 def __getattr__(self,n):
  if n.startswith('get_') and n[4:] in self.__dict__:return lambda:self.__dict__[n[4:]]
  raise AttributeError(n)
class CM:
 packer={'B':struct.Struct('B')}
 def get_type(self,i):return ['Lj$/fixture/Catch;','Lj$/fixture/Literal;'][i]
 def get_raw_string(self,i):return 'Lj$/fixture/Generic<Ljava/lang/String;>;'
 def get_field(self,i):return ['Lj$/fixture/FieldOwner;','Lj$/fixture/Enum;','VALUE']
 def get_method(self,i):return ['Lj$/fixture/MethodOwner;','method','()Lj$/fixture/Return;']
cm=CM()
def ev(kind):return EncodedValue(io.BytesIO(bytes([kind,1])),cm)
def annotation(values):return Obj(type_idx=1,elements=[Obj(value=v) for v in values])
def aset(values):return Obj(annotation_off_item=[Obj(annotation_item=Obj(annotation=annotation(values)))])
values=[ev(t) for t in (0x17,0x18,0x19,0x1a,0x1b)]
for kind,v in zip((0x17,0x18,0x19,0x1a,0x1b),values):assert not isinstance(v.get_value(),int),(kind,v.get_value())
sets={1:aset(values),2:aset(values),3:aset(values),4:aset(values)}
cm.get_annotation_set_item=lambda i:sets[i]
cm.get_obj_by_offset=lambda i:Obj(list=[Obj(annotations_off=4)])
cm.get_encoded_array_item=lambda i:Obj(value=Obj(values=values))
code=Obj(bc=Obj(instructions=[Obj(output='Lj$/fixture/Operand;')]),handlers=Obj(list=[Obj(handlers=[Obj(type_idx=0)])]))
c=Obj(name='Lfixture/Test;',superclassname='Lj$/fixture/Super;',interfaces=['Lj$/fixture/Interface;'],fields=[Obj(descriptor='Lj$/fixture/Declared;')],static_values_off=1,annotations_directory_item=Obj(class_annotations_off=1,field_annotations=[Obj(annotations_off=2)],method_annotations=[Obj(annotations_off=3)],parameter_annotations=[Obj(annotations_off=5)]),methods=[Obj(descriptor='()Lj$/fixture/MethodReturn;',code=code)])
roots,counts=m.scan_classes([c],cm)
expected={'Catch','Literal','Generic','FieldOwner','Enum','MethodOwner','Return','Operand','Super','Interface','Declared','MethodReturn'}
assert roots=={'Lj$/fixture/'+n+';' for n in expected},roots
assert counts['catch-type']==1 and counts['annotation-or-static-value']>0
# Removing catch or annotation traversal causes the above equality to fail.
for kind in (0x18,0x19,0x1a,0x1b):
 cm.get_encoded_array_item=lambda i,k=kind:Obj(value=Obj(values=[Obj(value_type=k,value=1)]))
 try:m.scan_classes([c],cm);raise AssertionError('raw index accepted')
 except ValueError as e:assert 'Unresolved' in str(e)
for root in ('Lj$/sun/nio/cs/Encoder;','Lj$/sun/misc/DesugarUnsafe;','Lj$/util/Base64$Encoder;'):
 try:m.validate({root},{'Lj$/time/Clock;'});raise AssertionError('unsafe accepted')
 except ValueError:pass
try:m.validate(set(),{'Lj$/time/Clock;'});raise AssertionError('empty accepted')
except ValueError:pass
for bad in ('-dontshrink','-keep class j$.** { *; }','-keep class j$.time.Clock { *; }\n-keep class j$.time.Clock { *; }'):
 try:m.keep_set(bad);raise AssertionError('bad keep file accepted')
 except ValueError:pass
assert m.keep_set('-keep class j$.time.Clock { *; }')=={'Lj$/time/Clock;'}
print('PASS selected fixtures: catch, hierarchy, fields, method metadata, operand, class/field/method/parameter annotations, generic Signature string, static TYPE/FIELD/METHOD/ENUM resolved by actual EncodedValue parser, raw indexes rejected, unsafe/empty/malformed guards. Not full scanner/runtime proof.')
