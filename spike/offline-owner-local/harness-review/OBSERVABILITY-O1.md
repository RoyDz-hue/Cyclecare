# O1 local observability candidate

Base eed78e389441e2545e66f487365fdb55cda0268c. No build, push, device, runtime, key or DB effects. Original StageB failed with only a generic FAIL; actual failing stage remains UNKNOWN.

Facade now returns bounded fixed protocol:
FAIL|stage=PACKAGE_AUDIT|check=1|kind=CHECK|checks=1|cleanup=false|evidence=WRITTEN|close=true
Example only, NOT observed failure.

Stages are closed enum constants. Every verification call uses a fixed numeric ID1..60, map attached; no labels interpolated from loop/input/payload. Categories are allowlisted CHECK/SECURITY/ARGUMENT/STATE/IO/CANCELLED/LINKAGE/OTHER from type predicates. No exception text/toString/classname/stack/cause is read or exported. No raw rows, UUID, alias, envelope, prefs, filenames or crypto bytes in returned report. JUnit asserts with this safe returned string, so standard XML/logcat exposes the fixed diagnosis. No privatefiles export added.

Freeze original stage/category/check before close/evidence callbacks. Either callback may fail; original result stays unchanged and only fixed close/evidence state changes. Evidence=UNAVAILABLE for setup before writer exists, WRITTEN only when writer returns, FAILED on writer error. Outer guard covers setup/runBlocking/linkage failures without leaking stack from facade. All diagnostics catch Throwable by deliberate test-harness policy; VM-fatal failures may still prevent any report, no guarantee claimed. Private evidence preserves existing typed synthetic snapshots, but no longer adds arbitrary exception class names. Existing success stays PASS.

Stage factory-open is set immediately before actual factory, restored on success, retained on failure. Verification argument evaluation failures can be located by coarse stage; numeric check is set only once verify executes, so check0 means no failed verification ID recorded. This is explicit and not attributed to a different check. Stage routing does not introspect inside SQLCipher factory; a future FACTORY_OPEN category still doesn't distinguish every internal key/schema step.

## Earliest paths inspected, not observed cause

Original setup (hard package, UUID, filesDir/File, JSON collections) ran before its inner catch. Returned FAIL rather than unhandled error is evidence only that the inner caught-Exception path ran. Inner order: PackageManager.getPackageInfo plus no-network/providers/worker checks, ActivityManager.getRunningServices audit, initialization event, fresh config/KeyStore preflight, deliberate invalid factory rejection probes, then first actual factory.open. Later corpus is reachable only if those pass. Four-millisecond XML/zero-second matrix timing cannot reliably identify which path failed. No observed causal claim from timing. No stack/check data for original caught exception exists in standard artifacts. Potential API/permission/provider/Keystore/factory failures remain hypotheses, not fixes.

## Validation

2182 pure hostile cases across every stage, category and close/save combinations; hostile exception messages and toString accessors cannot leak; original CHECK42 preserved through both callbacks failing; invalid ID rejected; unavailable writer tested. No Android effects executed. Kotlin facade/store compile against cached actual Android36/Room/SQLCipher API succeeds. Java selected instrumentation compiles with only source11 module-path warning. Prior57 validator/secret/router mocks and144 mapper/snapshot/deferred-router mocks pass. Drift inventory explicitly updated, production hashes unchanged. Previous full169/725/87/262/etc protocol regressions unchanged by diagnostic-only source, no new result claimed for unrerun suites.
