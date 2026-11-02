# Rust spike S3: encrypted data parity + toolchain proof (branch rust-spike-s3-data-parity)

Bounded evidence run for the CycleCare Rust migration. Nothing here ships to
users; nothing lands on main from this branch.

What the workflow `cyclecare-rust-spike` proves (one bounded CI run, <=25 machine min target):

1. **Real schema authority** - SpikeRoomSchemaDumpTest (Robolectric, sdk 34)
   opens the actual Room database (7 entities, migrations 4..9, framework
   SQLite) and dumps sqlite_master DDL, per-table PRAGMA table_info/index_list,
   and the Room identity hash. The hand-transcribed fixture
   (spike/fixture/cyclecare-fixture-v9-transcribed.sql, marked unverified) is
   diffed against this dump; table/column mismatches fail the run.
2. **Codec parity, both directions** - a fixture DB created by the SQLCipher
   CLI (brew, version logged) with the Room-authoritative DDL + synthetic rows
   + real identity hash is read by Rust (rusqlite 0.41.1 / libsqlite3-sys
   0.39.1 / sqlcipher-src =419.0.6 = SQLCipher 4.19.0, the same core the app's
   sqlcipher-android 4.19.1 bundles). Then Rust creates a DB and the CLI reads
   it back. Cipher parameters recorded on both sides (cipher_version,
   kdf_iter, page size, HMAC/KDF algorithms).
3. **AES-GCM unwrap parity** - Kotlin AES/GCM/NoPadding (12-byte prepended IV,
   128-bit tag, JCE) vs Rust aes-gcm 0.11.1, synthetic vectors, both directions.
4. **Owner-namespace parity** - cycle_care_owner_<sha256hex(UTF-8 identity)>
   from Rust vs an independent shasum computation; the Kotlin algorithm is
   CycleCareDatabase.namespaceFor (source-cited).
5. **Migration core** - Androidapp/rust/core (framework-neutral domain crate:
   account-start policy, shell routing, identity-bound wallet state machine,
   ported from main@31b88f5) runs its 16 tests in CI.
6. **Android toolchain packaging** - the spike crate also builds for
   aarch64-linux-android (NDK clang, minSdk 24), producing
   libcyclecare_spike.so (ELF aarch64, symbol cyclecare_spike_probe verified),
   packaged into the slim APK when budget remains.

Known limits (stated, not hidden):
- Android readback (Room opening a Rust-written DB on a device/emulator) is
  NOT covered: the emulator workflow is broken and sqlcipher-android has no
  host natives. Residual risk recorded; resolution = one future emulator or
  device check.
- The zetetic wrapper is source-verified (master branch of
  sqlcipher-android: SupportOpenHelperFactory passes the passphrase to
  nativeKey/sqlite3_key; no cipher pragmas, page_size pragma skipped when the
  codec is present). The maven AAR binary itself is not executed here.
- Keystore-invalidated DBs are unrecoverable by ANY app; a Rust app must
  detect, preserve files untouched, and re-authenticate via the server.
