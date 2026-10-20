# Optional debug diagnostics

## Scope and default

Normal debug builds have **no configured diagnostic destination**. Pairing and sending stay disabled until a developer explicitly supplies an approved temporary HTTPS origin for that build. Release builds retain an empty endpoint and the runtime refuses diagnostics outside debug builds.

This feature is an owner-approved technical snapshot, not remote screen control, logcat access, health-record extraction or automatic telemetry. The debug application ID ends in `.debug`; that separates local app storage, not the configured backend.

## Configure an authorized session

The bridge implementation is `debug-bridge/bridge_server.py`, a temporary Python standard-library service. Arrange an authorized HTTPS endpoint forwarding to that service, then explicitly build:

```bash
./gradlew :app:assembleDebug -Pcyclecare.debugBridgeUrl=https://diagnostics.example.test
```

Replace the example with your own approved temporary origin. It must use HTTPS, with an optional valid port and optional trailing slash, but no credentials, application path, query or fragment. Invalid values fail build configuration without echoing the supplied value. Omitting the property keeps diagnostics unavailable. Do not put tokens in the URL or commit a temporary endpoint into the build script.

Install the separate debug app with a test account and test data. In Settings, open **Remote debug (test build)**, enter the one-time code and approve that diagnostic session. Pairing sends the code only. A snapshot leaves the phone only after the owner deliberately taps **Send diagnostic snapshot**. End the session when finished and stop the temporary bridge.

## Exact data and privacy boundary

Snapshots include only app version, Android release/API level, current internet-connectivity indication and UTC timestamp. They exclude cycle dates, symptoms, notes, identity/email, auth tokens, device identifiers, screen content, contacts and payment details.

The bridge creates a random one-time pairing code expiring after 24 hours, rate-limits pairing, consumes the code once and issues an in-memory session token. It accepts an exact field allowlist and stores only the latest report with owner-only permissions. It has no public report-reading endpoint and does not retain requester IP access logs. The app must never send a snapshot merely because a build endpoint exists.

The runtime independently rejects blank, malformed, non-HTTPS, credential-bearing, path/query/fragment-bearing or out-of-range-port configuration before opening a connection. Release mode is always rejected. The UI explains unavailable configuration rather than offering a pairing action that cannot work.

## Acceptance limits

Unit tests cover configuration policy; builds and built instrumentation do not establish hardware acceptance or prove a bridge is online. Actual pairing, revoke/expiry and snapshot handling need an authorized phone and temporary bridge. Use USB/Android Wireless Debugging on a trusted network for full developer instrumentation. Any future screen sharing or remote control requires separate consent, implementation and security/privacy review; it is not provided here.
