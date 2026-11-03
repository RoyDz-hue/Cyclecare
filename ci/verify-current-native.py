#!/usr/bin/env python3
"""Strict diagnostic assertions. No credentials, network, signing or deployment."""
import hashlib,json,pathlib,sys,zipfile,subprocess,os,struct,re
# 0cf66c7 rebuild (parser + phase FFI contracts): reproducible across two clean
# local builds, NDK r27c, API 24, 16 KB LOAD alignment (see commit message).
EXPECTED='5c1c72d4631cd38f7a866fb3950cbbac375b910494d103dbddd2537bcb5f7691'
# Packaged form: AGP stripSlimDebugSymbols ran `llvm-strip --strip-unneeded`. Offline, NDK r27c
# `llvm-strip --strip-unneeded` on the committed input reproduces these exact bytes.
# Offline-derived packaged prediction: llvm-strip --strip-unneeded (NDK r27c)
# of the 0cf66c7 rebuild. Transform equivalence is PROVEN on the previous
# generation: strip-unneeded of dcb37aa4 reproduces the AGP-packaged 8a5b90c0/
# 908984 byte-for-byte. Matches the reviewer's independent derivation. AGP
# still confirms on the next diagnostic run; assert_strip_only bounds any
# difference to .symtab/.strtab.
STRIPPED='7d9b58f07e1fdf21acb396e4f27c7c8381babd7bf63055080e9d4cd3fa9b7947'
STRIPPED_LEN=2469184
REMOVED={'.symtab','.strtab'}
SO=pathlib.Path('Androidapp/rust/core-jni/jniLibs/arm64-v8a/libcyclecare_core.so')
BRIDGE='Lcom/cyclecare/app/rust/CycleCareCoreBridge;'
METHODS={'nativeCall','nativeCoreHandshake'}
def verify(data,packaged=False):
    actual=hashlib.sha256(data).hexdigest()
    want,length=(STRIPPED,STRIPPED_LEN) if packaged else (EXPECTED,3598312)
    assert actual==want,(actual,want)
    assert len(data)==length,len(data)
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

def elf_layout(data):
    shoff=struct.unpack_from('<Q',data,40)[0];size,count,strndx=struct.unpack_from('<HHH',data,58)
    phoff=struct.unpack_from('<Q',data,32)[0];phsize,phnum=struct.unpack_from('<HH',data,54)
    sh=[struct.unpack_from('<IIQQQQIIQQ',data,shoff+i*size) for i in range(count)]
    st=sh[strndx];tab=data[st[4]:st[4]+st[5]]
    secs={}
    for s in sh:
        name=tab[s[0]:tab.index(b'\0',s[0])].decode()
        assert name not in secs,'duplicate section '+name
        secs[name]=(s[1],s[2],s[3],hashlib.sha256(b'' if s[1]==8 else data[s[4]:s[4]+s[5]]).hexdigest())
    # Mask only the three ELF header fields that strip legitimately rewrites (e_shoff, e_shnum, e_shstrndx).
    masked=bytearray(data);masked[40:48]=bytes(8);masked[60:64]=bytes(4);data=bytes(masked)
    ph=[struct.unpack_from('<IIQQQQQQ',data,phoff+i*phsize) for i in range(phnum)]
    segs=[(p[0],p[1],p[3],p[4],p[5],p[6],p[7],hashlib.sha256(data[p[2]:p[2]+p[5]]).hexdigest()) for p in ph]
    return secs,segs

def assert_strip_only(committed,packaged):
    """Packaged lib may differ from the committed lib ONLY by removal of .symtab/.strtab
    (and the rewritten .shstrtab). Every other section and every program header segment
    (type, flags, vaddr, paddr, sizes, align, content) must be identical."""
    a,sa=elf_layout(committed);b,sb=elf_layout(packaged)
    assert set(a)-set(b)==REMOVED,('removed sections differ from approved strip',sorted(set(a)-set(b)))
    assert not set(b)-set(a),('new sections',sorted(set(b)-set(a)))
    for name in b:
        if name=='.shstrtab':continue
        assert a[name]==b[name],('section changed',name)
    assert sa==sb,'program header segments changed'
    mc=bytearray(committed[:64]);mp=bytearray(packaged[:64])
    for m in (mc,mp):m[40:48]=bytes(8);m[60:64]=bytes(4)
    assert mc==mp,'ELF header changed beyond section-table fields'

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
    apkdir=os.environ.get('APK_DIR','Androidapp/app/build/outputs/apk/slim')
    apks=list(pathlib.Path(apkdir).rglob('*.apk'));assert len(apks)==1,apks
    apk=apks[0];raw=apk.read_bytes()
    with zipfile.ZipFile(apk) as z:
        assert z.testzip() is None
        names=z.namelist();assert len(names)==len(set(names)),'duplicate entries'
        if os.environ.get('APK_SIGNED')=='1':
            print(json.dumps({'signedLabApk':True,'note':'unsigned-structure checks skipped; ephemeral debug-cert signature expected'}))
        else:
            unsigned_structure(raw,names)
        packaged=z.read('lib/arm64-v8a/libcyclecare_core.so')
        digest=verify(packaged,packaged=True)
        assert_strip_only(SO.read_bytes(),packaged)
        found=set()
        for name in names:
            if re.fullmatch(r'classes(?:\d+)?\.dex',name):found|=dex_native_methods(z.read(name))
        assert METHODS<=found,('JNI names missing from actual DEX',found)
    # Structural checks above are mandatory even if SDK tooling is unavailable.
    struct_msg='signed lab APK: mandatory structure checks passed (signature expected)' if os.environ.get('APK_SIGNED')=='1' else 'PASS - no v1 files or APK Signing Block'
    print(json.dumps({'structuralNativeChecks':'PASS','packagedNativeSha256':digest,'stripOnlyVsCommitted':'PASS','nativeDexNames':sorted(found),'unsignedStructure':struct_msg}),flush=True)
    sdkpath=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    assert sdkpath,'SDK location missing: ANDROID_HOME/ANDROID_SDK_ROOT'
    sdk=pathlib.Path(sdkpath)
    tools=list((sdk/'build-tools').glob('*/apksigner'))
    def version(p):return tuple(int(x) for x in re.findall(r'\d+',p.parent.name))
    assert tools,'missing apksigner'
    tool=max(tools,key=version)
    result=subprocess.run([str(tool),'verify','--verbose',str(apk)],capture_output=True,text=True)
    print('apksigner:',tool,'returncode:',result.returncode);print(result.stdout+result.stderr)
    output=result.stdout+result.stderr
    if os.environ.get('APK_SIGNED')=='1':
        # Lab APKs carry the AGP ephemeral debug cert: require a VALID
        # signature with exactly one signer. The workflow separately proves
        # the signer cert DN is Android Debug via --print-certs.
        assert result.returncode==0,('signed lab APK must verify',result.returncode,output)
        assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in output,('expected valid v2 signature',output)
        assert 'Number of signers: 1' in output,('expected exactly one (debug) signer',output)
        print(json.dumps({'apk':str(apk),'packagedNativeSha256':digest,'committedNativeSha256':EXPECTED,'nativeTransform':'strip-only (.symtab/.strtab removed; all other sections and segments identical)','apkSha256':hashlib.sha256(raw).hexdigest(),'signature':'valid v2, exactly 1 signer (AGP debug cert; DN verified in workflow)','nativeDexNames':sorted(found),'apksignerReturnCode':result.returncode}))
    else:
        assert result.returncode==1,('unexpected apksigner failure, cannot accept',result.returncode)
        assert 'DOES NOT VERIFY' in output and 'Missing META-INF/MANIFEST.MF' in output,('unclassified apksigner failure, not unsigned proof',output)
        # Unsigned classification is structural, NOT inferred from tool failure.
        print(json.dumps({'apk':str(apk),'packagedNativeSha256':digest,'committedNativeSha256':EXPECTED,'nativeTransform':'strip-only (.symtab/.strtab removed; all other sections and segments identical)','apkSha256':hashlib.sha256(raw).hexdigest(),'unsignedStructure':'no v1 signature files and no APK signing block','nativeDexNames':sorted(found),'apksignerReturnCode':result.returncode}))
else:raise SystemExit('usage: verify-current-native.py input|apk')
