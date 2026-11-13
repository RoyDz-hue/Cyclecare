#!/usr/bin/env python3
"""Structural DEX test inventory for the CycleCare stage-F instrumented build gate.

Pure-stdlib DEX parser: walks EVERY classes*.dex entry of the test APK, iterating
class_def / class_data / annotations_directory structures (no substring matching).

Correctness rules enforced (all fail closed):
  - method_idx delta accumulators RESET between the direct and virtual method
    arrays of each class_data_item (DEX spec: each array starts from 0);
  - a witnessed @Test method must satisfy the JUnit 4 runnable contract:
    public, non-static, void return, zero parameters;
  - the Lorg/junit/Test; annotation must be RUNTIME-visible
    (annotation_item visibility == VISIBILITY_RUNTIME (1));
  - each encoded method's owner (method_ids class_idx) must equal the class
    being inspected;
  - duplicate class definitions (same descriptor, any dex) are rejected;
  - parsing must be non-vacuous: >0 classes and >0 methods walked.

The sorted "class#method" set of RUNTIME-visible @Test methods under the given
package prefix must equal the pinned expected inventory exactly (missing, extra
or duplicate identities all fail). A separate --anchor-only mode anchors a
non-test class that carries no @Test methods.

MUTF-8 note: identifiers here are ASCII; utf-8-with-replacement decoding is
exact for ASCII and adequate for fail-closed identity comparison.
"""
import argparse
import struct
import sys
import zipfile

VISIBILITY_RUNTIME = 1
ACC_PUBLIC = 0x1
ACC_STATIC = 0x8
TEST_ANNOTATION = "Lorg/junit/Test;"


def uleb(data, off):
    result = 0
    shift = 0
    while True:
        b = data[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, off
        shift += 7


def read_mutf8(data, off):
    _, off = uleb(data, off)
    end = data.index(0, off)
    return data[off:end].decode("utf-8", errors="replace")


class Dex:
    def __init__(self, data, name):
        if data[:4] != b"dex\n":
            raise ValueError(f"{name}: not a DEX (bad magic)")
        self.data = data
        self.name = name
        u4 = lambda o: struct.unpack_from("<I", data, o)[0]
        n_str, off_str = u4(56), u4(60)
        n_typ, off_typ = u4(64), u4(68)
        n_pro, off_pro = u4(72), u4(76)
        n_met, off_met = u4(88), u4(92)
        n_cls, off_cls = u4(96), u4(100)
        self.strings = [read_mutf8(data, u4(off_str + 4 * i)) for i in range(n_str)]
        self.types = [self.strings[u4(off_typ + 4 * i)] for i in range(n_typ)]
        self.protos = []
        for i in range(n_pro):
            _, ret_idx, params_off = struct.unpack_from("<III", data, off_pro + 12 * i)
            nparams = u4(params_off) if params_off else 0
            self.protos.append((self.types[ret_idx], nparams))
        self.methods = [struct.unpack_from("<HHI", data, off_met + 8 * i) for i in range(n_met)]
        self.class_defs = [struct.unpack_from("<8I", data, off_cls + 32 * i) for i in range(n_cls)]

    def class_methods(self, class_def):
        """Return [(method_idx, access_flags)] for direct+virtual arrays.

        The method_idx delta accumulator resets between the two arrays per the
        DEX specification."""
        class_data_off = class_def[6]
        if not class_data_off:
            return []
        data = self.data
        sf, o = uleb(data, class_data_off)
        inf, o = uleb(data, o)
        dm, o = uleb(data, o)
        vm, o = uleb(data, o)
        for _ in range(sf + inf):
            _, o = uleb(data, o)
            _, o = uleb(data, o)
        out = []
        for count in (dm, vm):
            idx = 0  # reset between direct and virtual arrays
            for _ in range(count):
                diff, o = uleb(data, o)
                access, o = uleb(data, o)
                _, o = uleb(data, o)  # code_off
                idx += diff
                out.append((idx, access))
        return out

    def method_annotations(self, annotations_off):
        """Map method_idx -> {(annotation_type_descriptor, visibility)}."""
        data = self.data
        u4 = lambda o: struct.unpack_from("<I", data, o)[0]
        n_fields = u4(annotations_off + 4)
        n_methods = u4(annotations_off + 8)
        base = annotations_off + 16 + 8 * n_fields
        out = {}
        for i in range(n_methods):
            method_idx, set_off = struct.unpack_from("<II", data, base + 8 * i)
            size = u4(set_off)
            anns = set()
            for j in range(size):
                ann_off = u4(set_off + 4 + 4 * j)
                visibility = data[ann_off]
                type_idx, _ = uleb(data, ann_off + 1)
                anns.add((self.types[type_idx], visibility))
            out[method_idx] = anns
        return out


def fail(msg):
    print(f"DEX INVENTORY GATE FAIL: {msg}", file=sys.stderr)
    sys.exit(1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk")
    ap.add_argument("expected_inventory", nargs="?", default=None)
    ap.add_argument("output")
    ap.add_argument("--anchor-only", action="store_true")
    ap.add_argument("--require-class", action="append", default=[])
    ap.add_argument("--prefix", default="Lcom/cyclecare/")
    args = ap.parse_args()

    zf = zipfile.ZipFile(args.apk)
    dex_names = sorted(
        n for n in zf.namelist() if n.startswith("classes") and n.endswith(".dex") and "/" not in n
    )
    if not dex_names:
        fail("no classes*.dex entries in APK")

    seen_descriptors = {}
    witnessed = []
    total_classes = 0
    total_methods = 0
    total_annotated_methods = 0
    per_dex = {}
    for dex_name in dex_names:
        dex = Dex(zf.read(dex_name), dex_name)
        count = 0
        for cd in dex.class_defs:
            class_desc = dex.types[cd[0]]
            if class_desc in seen_descriptors:
                fail(f"duplicate class definition: {class_desc} in {seen_descriptors[class_desc]} and {dex_name}")
            seen_descriptors[class_desc] = dex_name
            total_classes += 1
            ann_off = cd[5]
            manns = dex.method_annotations(ann_off) if ann_off else {}
            for method_idx, access in dex.class_methods(cd):
                total_methods += 1
                owner_idx, proto_idx, name_idx = dex.methods[method_idx]
                if owner_idx != cd[0]:
                    fail(f"owner mismatch in {dex_name}: {class_desc} encodes method "
                         f"{dex.strings[name_idx]} owned by {dex.types[owner_idx]}")
                anns = manns.get(method_idx)
                if not anns:
                    continue
                total_annotated_methods += 1
                if (TEST_ANNOTATION, VISIBILITY_RUNTIME) not in anns:
                    continue
                if not class_desc.startswith(args.prefix):
                    continue
                mname = dex.strings[name_idx]
                ret, nparams = dex.protos[proto_idx]
                if not (access & ACC_PUBLIC) or (access & ACC_STATIC):
                    fail(f"@Test method {class_desc}#{mname} violates JUnit contract: "
                         f"access_flags=0x{access:x} (must be public, non-static)")
                if ret != "V" or nparams != 0:
                    fail(f"@Test method {class_desc}#{mname} violates JUnit contract: "
                         f"return={ret} params={nparams}")
                witnessed.append(f"{class_desc}#{mname}")
                count += 1
        per_dex[dex_name] = count

    if total_classes == 0 or total_methods == 0:
        fail(f"vacuous parse: {total_classes} classes, {total_methods} methods")

    dupes = sorted({w for w in witnessed if witnessed.count(w) > 1})
    if dupes:
        fail(f"duplicate @Test identities across dex entries: {dupes}")

    for req in args.require_class:
        if req not in seen_descriptors:
            fail(f"required anchor class {req} absent from all {total_classes} classes")

    if args.anchor_only:
        with open(args.output, "w") as f:
            f.write(f"apk={args.apk}\n")
            f.write(f"dex_entries={','.join(dex_names)}\n")
            f.write(f"total_classes={total_classes}\n")
            f.write(f"total_methods={total_methods}\n")
            f.write(f"total_annotated_methods={total_annotated_methods}\n")
            for req in args.require_class:
                f.write(f"anchor_present={req}\n")
        print(f"ANCHOR GATE OK: {len(args.require_class)} anchor class(es) present across "
              f"{total_classes} classes / {total_methods} methods in {len(dex_names)} dex entries")
        return

    if args.expected_inventory is None:
        fail("expected inventory file required unless --anchor-only")

    actual = sorted(witnessed)
    expected = sorted(
        l.strip() for l in open(args.expected_inventory) if l.strip() and not l.startswith("#")
    )
    with open(args.output, "w") as f:
        f.write(f"apk={args.apk}\n")
        f.write(f"dex_entries={','.join(dex_names)}\n")
        for d, c in per_dex.items():
            f.write(f"{d}: {c} prefix-matched @Test methods\n")
        f.write(f"total_classes={total_classes}\n")
        f.write(f"total_methods={total_methods}\n")
        f.write(f"total_annotated_methods={total_annotated_methods}\n")
        f.write(f"count={len(actual)}\n")
        for line in actual:
            f.write(line + "\n")

    if actual != expected:
        missing = [e for e in expected if e not in actual]
        extra = [a for a in actual if a not in expected]
        fail(f"identity set mismatch: missing={missing} extra={extra} "
             f"(actual {len(actual)} vs expected {len(expected)})")

    print(f"DEX INVENTORY OK: {len(actual)} exact @Test identities across {len(dex_names)} dex "
          f"entries ({total_classes} classes / {total_methods} methods / "
          f"{total_annotated_methods} annotated methods walked); inventory saved to {args.output}")


if __name__ == "__main__":
    main()
