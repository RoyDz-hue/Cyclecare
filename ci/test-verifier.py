import pathlib,runpy,sys,tempfile,zipfile,struct
# Load helpers without executing either action.
source=pathlib.Path(__file__).with_name('verify-current-native.py').read_text().split("if sys.argv[1]=='input':")[0]
n={};exec(compile(source,'verify-current-native.py','exec'),n)
with tempfile.TemporaryDirectory() as d:
 p=pathlib.Path(d)/'test.apk'
 with zipfile.ZipFile(p,'w') as z:z.writestr('classes.dex',b'placeholder')
 raw=p.read_bytes();n['unsigned_structure'](raw,['classes.dex'])
 # Simulate v2/v3 signing-block placement, updating central-dir offset.
 ep=raw.rfind(b'PK\x05\x06');cd=struct.unpack_from('<I',raw,ep+16)[0]
 block=struct.pack('<Q',24)+struct.pack('<Q',24)+b'APK Sig Block 42'
 signed=bytearray(raw[:cd]+block+raw[cd:]);struct.pack_into('<I',signed,ep+len(block)+16,cd+len(block))
 try:n['unsigned_structure'](signed,['classes.dex']);raise RuntimeError('accepted signing block')
 except AssertionError:pass
 try:n['unsigned_structure'](raw,['META-INF/CERT.RSA']);raise RuntimeError('accepted v1 signature')
 except AssertionError:pass
 try:n['unsigned_structure'](raw+b'junk',['classes.dex']);raise RuntimeError('accepted trailing bytes')
 except AssertionError:pass
# Minimal structural DEX fixture with exact class,methods and ACC_NATIVE.
strings=['Lcom/cyclecare/app/rust/CycleCareCoreBridge;','nativeCall','nativeCoreHandshake']
d=bytearray(112+12+4+16+32);d[:8]=b'dex\n035\0'
struct.pack_into('<II',d,56,3,112);struct.pack_into('<II',d,64,1,124)
struct.pack_into('<II',d,88,2,128);struct.pack_into('<II',d,96,1,144)
for i,s in enumerate(strings):struct.pack_into('<I',d,112+i*4,len(d));d.extend(bytes([len(s)])+s.encode()+b'\0')
struct.pack_into('<HHI',d,128,0,0,1);struct.pack_into('<HHI',d,136,0,0,2)
struct.pack_into('<8I',d,144,0,1,0xffffffff,0,0xffffffff,0,len(d),0)
d.extend(bytes([0,0,2,0,0,0x82,2,0,1,0x82,2,0]))
assert n['dex_native_methods'](d)=={'nativeCall','nativeCoreHandshake'}
print('VERIFIER SELECTED FIXTURES PASS: unsigned accepted; signingblock/v1/trailingbytes rejected; exact DEX native names parsed. Not real APK/apksigner acceptance.')
