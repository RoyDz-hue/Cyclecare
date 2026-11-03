#!/usr/bin/env python3
"""Strict diagnostic assertions. No credentials, network, signing or deployment."""
import hashlib,json,pathlib,sys,zipfile,subprocess,os,struct,re
EXPECTED='dcb37aa43dc2931be81de10090be3fdd2967bbc58f8285cef60ba21e6de48521'
SO=pathlib.Path('Androidapp/rust/core-jni/jniLibs/arm64-v8a/libcyclecare_core.so')
BRIDGE='Lcom/cyclecare/app/rust/CycleCareCoreBridge;'
METHODS={'nativeCall','nativeCoreHandshake'}
def verify(data):
    actual=hashlib.sha256(data).hexdigest()
    assert actual==EXPECTED,(actual,EXPECTED)
    assert len(data)==1363624,len(data)
    # ELF64 little-endian AArch64 dynamic exports, not arbitrary byte strings.
    assert data[:6]==b'\x7fELF\x02\x01' and struct.unpack_from('<H',data,18)[0]==183
    shoff=struct.unpack_from('<Q',data,40)[0];size,count=struct.unpack_from('<HH',data,58)
    sections=[struct.unpack_from('<IIQQQQIIQQ',data,shoff+i*size) for i in range(count)]
    exports=set()
    for sh in sections:
        if sh[1]!=11:continue
        strings=sections[sh[6]];table=data[strings[4]:strings[4]+strings[5]]
        for off in range(sh[4],sh[4]+sh[5],sh[9]):
            name,info,other,index,value,length=struct.unpack_from('<IBBHQQ',data,off)
            if index and info>>4 in (1,2):exports.add(table[name:table.index(b'\0',name)].decode())
    for method in METHODS:assert 'Java_com_cyclecare_app_rust_CycleCareCoreBridge_'+method in exports
    return actual

def uleb(d,p):
    n=0;shift=0
    while True:
        b=d[p];p+=1;n|=(b&127)<<shift
        if b<128:return n,p
        shift+=7
        assert shift<35

def dex_native_methods(d):
    assert d[:4]==b'dex\n'
    count,off=struct.unpack_from('<II',d,56)
    strings=[]
    for i in range(count):
        p=struct.unpack_from('<I',d,off+4*i)[0];_,p=uleb(d,p)
        strings.append(d[p:d.index(b'\0',p)].decode('utf-8',errors='replace'))
    count,off=struct.unpack_from('<II',d,64)
    types=[strings[struct.unpack_from('<I',d,off+4*i)[0]] for i in range(count)]
    count,off=struct.unpack_from('<II',d,88)
    methods=[struct.unpack_from('<HHI',d,off+8*i) for i in range(count)]
    count,off=struct.unpack_from('<II',d,96)
    found=set()
    for i in range(count):
        cls=struct.unpack_from('<8I',d,off+32*i)
        if types[cls[0]]!=BRIDGE or not cls[6]:continue
        p=cls[6];sizes=[]
        for _ in range(4):n,p=uleb(d,p);sizes.append(n)
        for _ in range(sizes[0]+sizes[1]):_,p=uleb(d,p);_,p=uleb(d,p)
        for amount in sizes[2:]:
            idx=0
            for _ in range(amount):
                diff,p=uleb(d,p);idx+=diff;flags,p=uleb(d,p);_,p=uleb(d,p)
                m=methods[idx]
                if flags&0x100 and types[m[0]]==BRIDGE:found.add(strings[m[2]])
    return found

def unsigned_structure(raw,names):
    # EOCD locates the central directory. APK v2/v3/v3.1 signatures must live in
    # the APK Signing Block immediately before it. Reject ANY such block,
    # including unknown IDs, instead of confusing verification failure with unsigned.
    p=raw.rfind(b'PK\x05\x06',max(0,len(raw)-65557));assert p>=0,'missing EOCD'
    fields=struct.unpack_from('<4s4H2IH',raw,p)
    assert p+22+fields[-1]==len(raw),'trailing bytes/EOCD mismatch'
    assert fields[1]==fields[2]==0 and fields[3]==fields[4],'split zip'
    cdsize,cdoff=fields[5:7]
    assert cdsize!=0xffffffff and cdoff!=0xffffffff,'ZIP64 unsupported'
    assert cdoff+cdsize==p and raw[cdoff:cdoff+4]==b'PK\x01\x02','central directory mismatch'
    assert raw[cdoff-16:cdoff]!=b'APK Sig Block 42','APK signing block present'
    assert not any(n.upper().startswith('META-INF/') and n.upper().endswith(('.RSA','.DSA','.EC','.SF')) for n in names),'v1 signature present'

if sys.argv[1]=='input':
    print(json.dumps({'native':str(SO),'sha256':verify(SO.read_bytes()),'bytes':SO.stat().st_size,'elfExports':'verified'}))
elif sys.argv[1]=='apk':
    apks=list(pathlib.Path('Androidapp/app/build/outputs/apk/slim').rglob('*.apk'));assert len(apks)==1,apks
    apk=apks[0];raw=apk.read_bytes()
    with zipfile.ZipFile(apk) as z:
        assert z.testzip() is None
        names=z.namelist();assert len(names)==len(set(names)),'duplicate entries'
        unsigned_structure(raw,names)
        digest=verify(z.read('lib/arm64-v8a/libcyclecare_core.so'))
        found=set()
        for name in names:
            if re.fullmatch(r'classes(?:\d+)?\.dex',name):found|=dex_native_methods(z.read(name))
        assert METHODS<=found,('JNI names missing from actual DEX',found)
    # Structural checks above are mandatory even if SDK tooling is unavailable.
    print(json.dumps({'structuralNativeChecks':'PASS','nativeSha256':digest,'nativeDexNames':sorted(found),'unsignedStructure':'PASS - no v1 files or APK Signing Block'}),flush=True)
    sdkpath=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    assert sdkpath,'SDK location missing: ANDROID_HOME/ANDROID_SDK_ROOT'
    sdk=pathlib.Path(sdkpath)
    tools=list((sdk/'build-tools').glob('*/apksigner'))
    def version(p):return tuple(int(x) for x in re.findall(r'\d+',p.parent.name))
    assert tools,'missing apksigner'
    tool=max(tools,key=version)
    result=subprocess.run([str(tool),'verify','--verbose',str(apk)],capture_output=True,text=True)
    print('apksigner:',tool,'returncode:',result.returncode);print(result.stdout+result.stderr)
    assert result.returncode==1,('unexpected apksigner failure, cannot accept',result.returncode)
    output=result.stdout+result.stderr
    assert 'DOES NOT VERIFY' in output and 'Missing META-INF/MANIFEST.MF' in output,('unclassified apksigner failure, not unsigned proof',output)
    # Unsigned classification is structural, NOT inferred from tool failure.
    print(json.dumps({'apk':str(apk),'nativeSha256':digest,'apkSha256':hashlib.sha256(raw).hexdigest(),'unsignedStructure':'no v1 signature files and no APK signing block','nativeDexNames':sorted(found),'apksignerReturnCode':result.returncode}))
else:raise SystemExit('usage: verify-current-native.py input|apk')
