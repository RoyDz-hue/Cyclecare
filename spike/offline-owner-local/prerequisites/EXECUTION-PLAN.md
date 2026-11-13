# Next execution plan ONLY, R2 remains unexecuted

No build, device, Keystore, DB open, main push or activation occurs under this document. Main evaluates existing user grants+engineering review gates before each stage; this is not an automatic request to Roy.

## Source gate R1, current local fix

Router publish holds shared router mutex then asks target.capturedIdentity() under adapter mutex. Requires exact full identity==next (namespace/effective/instance/epoch/session/consent/mode), not only namespace/instance. This establishes router->adapter order for publish/commit; no adapter callback may acquire router. Target validation before lock is preflight, exact captured check under lock owns publication. Mock target with correct namespace/instance but wrong session rejects without wedging router, old A capture/commit remains good. Never publish next into router while adapter still old identity. Prepared target must already have desired identity, no implicit session mutation.

Closed adapter APIs deliberately: capturedIdentity/capture return null; transition false; validatePrepared false; commit RejectedClosed; close idempotent. validateOpen is internal assertion API and throws closed-state error, caught by validatePrepared. No implicit reopen after explicitclose. To reopen construct a fresh factoryvalidated adapter, re-unwrap test key into a new retained factory-owned copy. Internal helper may reopen while handle alive using retainedsecret. All real lifetime effects still untested.

## Stage A: separately approved build/KSP verification

- Freeze exact isolated branch/source SHA and five labsource hashes, production flags/source hashes unchanged.
- One bounded lab variant compilation after reviewer gate, KSP generates new LabReceiptDatabase v1 implementation/schema; inspect actual SQLite DDL/identity hash and DAO generated transaction methods. No production migration9->10 applied; actualproduction schema compatibility remains open.
- Inspect KSP variant source isolation: no lab classes in production artifact/source graph; no Android build/grant silently broadened.
- Decode exact newly built app/test mergedmanifest, package lab/test, no INTERNET/provider/worker startup, component/process topology; inspect dependency generated sources/nativeSQLCipher packaging and source/API version.
- Sourcekeep/shrinking test boundary review before runtime. Existing a4d9a8d artifact manifests do not prove new artifact.

## Stage B: separately approved encrypted synthetic adapter tests

Fresh generated runUUID/config, testonly namespace/alias/prefs privatefixture registry. Validate rejection of productiondefaults and wrongapplicationId BEFORE any creation. No production sentinel reads beyond authorized test inspection, no productionalias lookup.

- ActualKeystore create/wrap/unwrap testsecret and SQLCipherRoomv1 open: force actualdecrypt/schema/integrity/metadata/receiptvalidation before token/capture. Reopen same syntheticfile in freshadapter and compare typed rows/revisions/journal/receipt.
- Retainedsecret lifetime: actual helperclose/reopen while adapteralive must work; explicitadapterclose zeros retainedcopy and blocks APIs; newfactory unwrap/reopen works. Avoid logging bytes or returning secret in JSON/JNI. Wrongkey/missingenvelope/keyinvalidation leaves files unchanged, no recreation/fallback.
- Run corpusmapper33Track/anchor/delete and8settings/profile through ACTUALRoomadapter. Type/hash/canonical validation must reject noncanonicaldouble/signedzero contradictions/nonfinite bits. Raw float/storage semantics explicit because lab stores payloadstring, not productionFLOATcolumns.
- RouteractualA/B preparedcaptures exactfullidentity: corruptB validation rollback oldA stillwritable, mismatchedtarget session/epoch rejects, sameownerABA and crossownerABA reject staleplans, bothnamespaces frozen, oldprojection receipt cannot paint newowner.
- Fault7steps at realRoom domainlog/domainanchor/revisionlog/journallog/revisionanchor/journalanchor/receipt. Newindependent DAOtransaction after injectederror proves rollback. Foursteps delete/nonanchor, settings/profile7. Actual processstillalive but newadapter recovers postcommitUnknown SAMEoperationID fromencryptedFILE, differentdigest rejects, perrecord/settingsbase races conflict.
- Worker/network absent, productionflagsfalse and unrelatedfixture namespacefile unchanged. No actualproductioncompatibility claims from minimal labv1.

## Stage C: later explicit execution gate, genuine restart

Only after StageB evidence reviewed. Independent P1PID disappears, P2newPID/startID same syntheticfile+alias. Test beforecommit no partial/receipt, aftercommitbeforedelivery receipt+rows recover, proposal-only no write, transitionwait stale results. OSkill not handleclose/activityrecreation. SQLite WAL/checkpoint/journal and fsync/powerloss separately reviewed; passing P1/P2 doesn't prove powerloss or all Androidversions. No runtime execution arranged yet.

## Stop conditions

KSP/schema mismatch, wrongpackage/process/provider/network capability, registry/alias collision, unclear ownerbinding, stalekey/unwrap failure, unexpected writer/process, noncanonical payload accepted, cancellation uncertain without receipt resolution, secrets in logs, unreviewed source drift. Preserve evidence/files, stop affected stage, report through main. No reset/wipe/fallback, no automatic extra builds/matrices. Main remains owner of external scope and user-facing timing.
