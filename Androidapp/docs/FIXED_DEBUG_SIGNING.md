# Fixed debug signing for shared APKs

Slim builds require the fixed debug keystore. Never generate a replacement automatically.
The encrypted recovery bundle is in the owner's vault entry named
`CycleCare fixed Android debug signing bundle`.

Recover its password field using approved secure access. It contains a JSON bundle:
`keystore_b64`, `store_password`, and `key_alias`. Decode the keystore into a private
file outside the repository, with mode 0600. Do not print the bundle or passwords.
Set `CYCLECARE_DEBUG_KEYSTORE`, `CYCLECARE_DEBUG_STORE_PASSWORD`, and
`CYCLECARE_DEBUG_KEY_PASSWORD` for the build process. Build with
`./gradlew assembleSlim -PslimApk`.

Verify the resulting APK signature against the saved certificate before delivery.
A sandbox-generated key from an older build cannot be recovered from an APK.
Changing certificates prevents Android from installing over that older package;
preserve local app data before considering uninstalling it.
