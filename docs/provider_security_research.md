# Payment provider verification notes

This note records the primary references reviewed while hardening the starter payment paths. No live provider sandbox payment, webhook delivery, or production credential was exercised in this task.

## PayHero

- Official documentation landing page: https://docs.payhero.co.ke/
- The accessible documentation was sufficient to confirm the provider API/callback integration surface, but did not establish a verifiable callback-signature algorithm that could safely be assumed here.
- The implementation therefore stores a high-entropy, one-time callback token hash per pending transaction and requires it on the callback, then checks the provider-reported result and exact transaction amount before an atomic server-side credit. This must still be exercised against the PayHero sandbox before production use.

## PayPal Orders v2

- Official Orders API reference: https://developer.paypal.com/api/orders/v2/
- The implementation creates an order from a fixed server catalog and requires the authenticated owner to capture it. Before crediting, it re-reads/verifies the order and checks the stored external reference, authenticated account ID, USD currency, exact amount, completed capture status, and capture amount. Database crediting is idempotent.

## OKX

- Official OKX API reference reviewed: https://www.okx.com/docs-v5/en/
- The general Exchange v5 API reference is not adequate evidence for a merchant-pay webhook signature or settlement contract. The starter's guessed callback verification and placeholder-wallet flow are disabled rather than represented as verified checkout. Re-enable only after implementing against the exact OKX merchant-pay contract and testing signed callbacks end-to-end.


## Local database encryption and backup controls (October 2026)

- Zetetic's official SQLCipher Android migration guide identifies `net.zetetic:sqlcipher-android:4.19.0` and `androidx.sqlite:sqlite:2.7.0` as the current Room 2-compatible setup, using `System.loadLibrary("sqlcipher")` and `SupportOpenHelperFactory`: https://www.zetetic.net/sqlcipher/sqlcipher-for-android-migration/
- The official SQLCipher Android repository confirms Room 2 `SupportOpenHelperFactory` integration and the native library loading requirement: https://github.com/sqlcipher/sqlcipher-android
- Zetetic's plaintext-database guide confirms existing SQLite data must be copied into a separate encrypted file using `ATTACH ... KEY ...` and `SELECT sqlcipher_export(...)`; simply applying a key to a plaintext file does not encrypt it: https://www.zetetic.net/sqlcipher/encrypting-plaintext-databases/
- Android's official Android 12 backup guide notes that `allowBackup="false"` may not block device-to-device transfer on all OEMs, and recommends explicit cloud-backup and device-transfer exclusion rules: https://developer.android.com/about/versions/12/backup-restore
