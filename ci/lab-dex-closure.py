#!/usr/bin/env python3
"""Lab DEX closure preflight (gates any Firebase Test Lab matrix submission).

Proves, on the actual CI-built artifacts, that the instrumented lab run can
resolve every type it needs BEFORE a device run is attempted:

  1. The test APK defines the instrumentation runner and the lab test class.
  2. The app APK defines the kept lab entry point under its original name.
  3. FAIL-CLOSED closure: every type referenced by the test APK is defined in
     the test APK, the app APK, or the compile-time platform (android.jar).
     An exact-name allowlist exists for individually reviewed residuals and is
     seeded EMPTY; every flagged reference must be reviewed, never wildcarded.

The app APK reference set is REPORTED only: apps normally reference runtime-
provided libcore internals (sun/misc/Unsafe, conscrypt internals, ...) that
are absent from android.jar but present on the boot classpath.

DEX parsing is byte-identical to the reviewer's independent preflight
(lab-dex-preflight): type_ids are the reference universe, class_def
class_idx entries are the definition universe.
"""
import struct, sys, zipfile

def parse_dex_bytes(b, types, defs):
    sid_size, sid_off = struct.unpack_from('<II', b, 56)
    tid_size, tid_off = struct.unpack_from('<II', b, 64)
    cd_size, cd_off = struct.unpack_from('<II', b, 96)
    def uleb(o):
        r = 0; s = 0
        while True:
            x = b[o]; o += 1; r |= (x & 0x7f) << s; s += 7
            if x < 0x80: return r, o
    strs = []
    for i in range(sid_size):
        (so,) = struct.unpack_from('<I', b, sid_off + 4 * i)
        n, p = uleb(so); e = b.index(b'\0', p)
        strs.append(b[p:e].decode('utf8', 'replace'))
    tl = []
    for i in range(tid_size):
        (si,) = struct.unpack_from('<I', b, tid_off + 4 * i)
        tl.append(strs[si])
    types |= set(tl)
    for i in range(cd_size):
        (ci,) = struct.unpack_from('<I', b, cd_off + 32 * i)
        defs.add(tl[ci])

def parse_apk(path):
    types = set(); defs = set()
    with zipfile.ZipFile(path) as z:
        for name in z.namelist():
            if name.startswith('classes') and name.endswith('.dex'):
                parse_dex_bytes(z.read(name), types, defs)
    return types, defs

def jar_classes(path):
    out = set()
    with zipfile.ZipFile(path) as z:
        for name in z.namelist():
            if name.endswith('.class'):
                out.add('L' + name[:-6] + ';')
    return out

# Exact-name residuals, each verified against direct evidence on 2026-11-03
# (referrer + why it is never resolved on device). Never add wildcards.
ALLOWLIST = {
    # Referenced only from androidx.test runner's junit3-legacy support
    # package (androidx/test/internal/runner/junit3/*, 19 classes verified
    # in runner-1.6.2.aar classes.jar). The lab suite is pure JUnit4
    # (@RunWith(AndroidJUnit4)), so those classes are never loaded and ART
    # never resolves the references (lazy resolution).
    'Landroid/test/AndroidTestCase;',
    'Landroid/test/InstrumentationTestCase;',
    # Referenced only by org.hamcrest bean-matcher classes (signatures
    # against org/hamcrest/Description confirmed via dexdump -f). Android's
    # libcore omits java.beans introspection; the lab test uses only
    # assertEquals/assertTrue, so these methods are never invoked.
    'Ljava/beans/BeanInfo;',
    'Ljava/beans/IntrospectionException;',
    'Ljava/beans/Introspector;',
    'Ljava/beans/PropertyDescriptor;',
    # Referenced only from com.google.errorprone.annotation method payloads
    # ('()[Ljavax/lang/model/element/Modifier;' via dexdump -f), a
    # compile-time-only annotation surface never reflectively queried on
    # device.
    'Ljavax/lang/model/element/Modifier;',
}

REQUIRED_TEST_DEFS = [
    'Landroidx/test/runner/AndroidJUnitRunner;',
    'Lcom/cyclecare/app/RustLabInstrumentedTest;',
]
REQUIRED_APP_DEFS = [
    'Lcom/cyclecare/app/lab/RustLabSelfCheck;',
]

def main():
    app_apk, test_apk, android_jar = sys.argv[1], sys.argv[2], sys.argv[3]
    t_types, t_defs = parse_apk(test_apk)
    a_types, a_defs = parse_apk(app_apk)
    platform = jar_classes(android_jar)
    print('test defs %d app defs %d platform classes %d'
          % (len(t_defs), len(a_defs), len(platform)))

    failed = False
    for req in REQUIRED_TEST_DEFS:
        ok = req in t_defs
        print(('OK   ' if ok else 'FAIL ') + 'test APK defines ' + req)
        failed |= not ok
    for req in REQUIRED_APP_DEFS:
        ok = req in a_defs
        print(('OK   ' if ok else 'FAIL ') + 'app APK defines ' + req)
        failed |= not ok

    elem = lambda x: x.lstrip('[')
    t_missing = sorted(set(elem(x) for x in t_types)
                       .__sub__(t_defs) - a_defs - platform - ALLOWLIST
                       - {''})
    t_missing = [x for x in t_missing if x.startswith('L')]
    print('test APK references defined in neither APK nor platform: %d'
          % len(t_missing))
    for m in t_missing:
        print('  MISSING ' + m)

    a_missing = [x for x in sorted(set(elem(x) for x in a_types) - a_defs
                                   - platform)
                 if x.startswith('L')]
    print('app APK references outside app+platform (report only, '
          'runtime-provided libcore internals expected): %d' % len(a_missing))
    for m in a_missing:
        print('  APP-REF ' + m)

    if t_missing:
        print('FAIL: test APK closure incomplete; review each MISSING entry')
        failed = True
    sys.exit(1 if failed else 0)

if __name__ == '__main__':
    main()
