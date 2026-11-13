#!/usr/bin/env python3
"""Runtime dependency-resolution gate for the CycleCare stage-F build (v2).

Semantics adjudicated by the reviewer from run 6aedc3409b1c36804de2f8e2's
ACTUAL report (the v1 blanket-FAILED + requested==selected-exact checks were
wrong; the original CI terminal FAIL is preserved in the run evidence):

  - FINAL SELECTED versions are asserted against the reviewed pins
    (runner=1.7.0, ext-junit=1.3.0, junit=4.13.2): in every checked classpath
    section, every occurrence of a pinned coordinate must resolve to the
    pinned version, whether declared directly or as "requested -> selected".
    A requested!=selected divergence is NOT itself a failure.
  - Pin PRESENCE is per-section, never union: each --require-pin-in
    group:name=section must have at least one occurrence of the coordinate in
    that exact section (reviewer: all 3 pins individually in BOTH
    debugAndroidTestCompileClasspath and debugAndroidTestRuntimeClasspath;
    removal from the runtime section alone must fail).
  - Lower-request provenance is REQUIRED and recorded, section-bound: each
    --requested-provenance group:name=version@section must be observed at
    least once as a requested (left-hand) version diverging to a different
    selected version in THAT section (runtime-bound, not union). Absence
    fails closed: the resolution chain changed and the adjudication no
    longer applies (re-review).
  - FAILED-entry checks are scoped to the checked classpath sections ONLY.
    Declarable-only "(n)" buckets (e.g. debugImplementation) render
    versionless BOM-managed coordinates (ui-tooling, ui-test-manifest) as
    FAILED even though the real classpaths resolve them; those are recorded
    as notes, never asserted.
  - The androidTestUtil graph is asserted by the utility-artifact gate
    (actual resolved APKs), not from this text report; its sections are
    recorded only. Transitives (monitor/storage/core/tracing/test-services)
    are recorded with requested->selected pairs, not asserted.
"""
import argparse
import re
import sys

RECORD_COORDS = [
    ("androidx.test", "monitor"),
    ("androidx.test.services", "storage"),
    ("androidx.test.services", "test-services"),
    ("androidx.test", "core"),
    ("androidx.tracing", "tracing"),
]


def fail(msg):
    print(f"DEP GRAPH GATE FAIL: {msg}", file=sys.stderr)
    sys.exit(1)


def parse_sections(text):
    sections = {}
    current = None
    for line in text.splitlines():
        header = re.match(r"^([A-Za-z0-9_]+) - ", line)
        if header:
            current = header.group(1)
            sections[current] = []
        elif current is not None:
            sections[current].append(line)
    return sections


def occurrences(lines, group, name):
    """All (requested, selected) pairs for group:name in a section's lines."""
    pat = re.compile(rf"{re.escape(group)}:{re.escape(name)}:([^\s(]+)(?: -> ([^\s(]+))?")
    out = []
    for line in lines:
        m = pat.search(line)
        if m:
            req = m.group(1)
            out.append((req, m.group(2) or req))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("report")
    ap.add_argument("--classpath", action="append", required=True,
                    help="resolvable classpath section to check (repeatable); "
                         "FAILED scan and pin assertions are scoped to these")
    ap.add_argument("--selected-pin", action="append", required=True,
                    help="group:name=version, FINAL SELECTED version in every checked classpath")
    ap.add_argument("--require-pin-in", action="append", default=[],
                    help="group:name=section, coordinate must occur in this exact checked "
                         "classpath section (repeatable; per-section presence, not union)")
    ap.add_argument("--requested-provenance", action="append", default=[],
                    help="group:name=version@section, lower requested version that must be "
                         "observed diverging to the selected pin in that exact section "
                         "(repeatable)")
    args = ap.parse_args()

    text = open(args.report, encoding="utf-8", errors="replace").read()
    sections = parse_sections(text)
    if not sections:
        fail("no configuration sections parsed - report format unrecognized")
    for cp in args.classpath:
        if cp not in sections:
            fail(f"checked classpath section '{cp}' missing; sections: {sorted(sections)}")

    checked = set(args.classpath)
    for cp in args.classpath:
        for line in sections[cp]:
            if "FAILED" in line:
                fail(f"FAILED entry in checked classpath '{cp}': {line.strip()}")
    for name, lines in sections.items():
        if name in checked:
            continue
        outside = [l.strip() for l in lines if "FAILED" in l]
        if outside:
            print(f"note: FAILED entries outside checked classpaths (recorded, "
                  f"non-resolvable/declarable bucket): section {name}: {outside}")

    for req in args.selected_pin:
        coord, version = req.rsplit("=", 1)
        group, name = coord.split(":", 1)
        occ = [(cp, r, s) for cp in args.classpath
               for (r, s) in occurrences(sections[cp], group, name)]
        if not occ:
            fail(f"pinned coordinate {coord} absent from all checked classpaths {args.classpath}")
        for cp, r, s in occ:
            if s != version:
                fail(f"pin {coord} selected {s} in '{cp}' (requested {r}); "
                     f"pinned final-selected {version}")
            if r != s:
                print(f"record: {coord} requested {r} -> selected {s} in '{cp}'")
        where = sorted({cp for cp, _, _ in occ})
        print(f"record: {coord} final-selected {version} verified in {where}")

    for req in args.require_pin_in:
        coord, section = req.rsplit("=", 1)
        group, name = coord.split(":", 1)
        if section not in checked:
            fail(f"--require-pin-in section '{section}' is not a checked classpath {args.classpath}")
        occ = occurrences(sections[section], group, name)
        if not occ:
            fail(f"pinned coordinate {coord} ABSENT from required section '{section}' "
                 f"(per-section presence, not union)")
        print(f"record: {coord} present in required section '{section}' ({len(occ)} occurrence(s))")

    for req in args.requested_provenance:
        try:
            coord, rest = req.rsplit("=", 1)
            version, section = rest.rsplit("@", 1)
        except ValueError:
            fail(f"--requested-provenance must be group:name=version@section, got: {req}")
        group, name = coord.split(":", 1)
        if section not in checked:
            fail(f"--requested-provenance section '{section}' is not a checked classpath {args.classpath}")
        hits = [(r, s) for (r, s) in occurrences(sections[section], group, name)
                if r == version and s != r]
        if not hits:
            fail(f"requested-provenance {coord} requested={version} not observed diverging "
                 f"in required section '{section}' - resolution chain changed, adjudication void, re-review")
        for r, s in hits:
            print(f"record: provenance {coord} requested {r} -> selected {s} observed in '{section}'")

    rt = "debugAndroidTestRuntimeClasspath"
    if rt in sections:
        for group, name in RECORD_COORDS:
            occ = occurrences(sections[rt], group, name)
            print(f"record: {group}:{name} runtime (requested, selected): "
                  f"{occ if occ else 'ABSENT'}")
    util_sections = [n for n in sections if "androidtestutil" in n.lower()]
    print(f"note: androidTestUtil report sections present (recorded only, asserted via "
          f"utility-artifact gate): {util_sections}")

    print(f"DEP GRAPH OK: final-selected pins {args.selected_pin} verified across "
          f"{args.classpath}; provenance {args.requested_provenance} observed; "
          f"no FAILED in checked classpaths; transitives recorded")


if __name__ == "__main__":
    main()
