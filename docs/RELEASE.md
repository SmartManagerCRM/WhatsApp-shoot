# Building, signing, versioning and updating

> Status: the Gradle configuration below has **not been executed** (see `docs/TESTING_REPORT.md`). Treat the first build as a bring-up.

## Requirements
JDK 17+, Android SDK with platform 35 and build-tools 35.x, Gradle wrapper 8.9 (`./gradlew`), network access to Google Maven for the first sync (the finished app itself needs none).

## One-time setup
```
scripts/build-license-tool.sh <kotlinc-dir>          # or ./gradlew :license-tool:installDist
scripts/provision-dev-key.sh                          # writes license-public-key.txt (+ secrets/ with the private key; never commit secrets/)
```
For production generate the key pair on an offline machine (`docs/KEY_MANAGEMENT.md`) and commit only `license-public-key.txt`.

## Debug APK
```
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk, debug-signed, no signer pinning
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
## Release APK (signed)
Create the release keystore once (keep it outside the repo, back it up twice):
```
keytool -genkeypair -v -keystore ~/secure/wss-release.jks -alias wss -keyalg RSA -keysize 4096 -validity 36500
keytool -list -v -keystore ~/secure/wss-release.jks -alias wss      # note the SHA-256 certificate fingerprint
```
Provide credentials through `keystore.properties` (git-ignored) or environment variables:
```
storeFile=/home/me/secure/wss-release.jks
storePassword=...
keyAlias=wss
keyPassword=...
signerSha256=<certificate SHA-256, hex, no colons>
```
(env equivalents: `WSS_STORE_FILE, WSS_STORE_PASSWORD, WSS_KEY_ALIAS, WSS_KEY_PASSWORD, WSS_SIGNER_SHA256`.)
```
./gradlew :app:assembleRelease    # app/build/outputs/apk/release/app-release.apk (R8-minified, resource-shrunk, signed)
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```
`assembleRelease` is configured to **fail** when the public licence key, signing config or `signerSha256` is missing. `signerSha256` lets the app
detect a repackaged APK (licensed features are disabled if the signer differs); it is a speed bump, not a guarantee.

## Versioning
`versionCode` (monotonic integer, in `app/build.gradle.kts`) must increase for every release; `versionName` is semantic (`MAJOR.MINOR.PATCH`).
Never change `applicationId` (`com.smartmanager.whatsappshoot`) or the signing certificate: either one makes Android treat the new APK as a different app,
which destroys the installation key and therefore every customer's activation.

## Compatibility checks before each release
`minSdk 26`, `targetSdk 35`. Re-run `docs/DEVICE_TEST_PLAN.md` on at least: Android 10/11, 13, 14+, current WhatsApp and WhatsApp Business.
Re-calibrate `WhatsAppProfile` after major WhatsApp updates. Update `docs/WHATSAPP_COMPATIBILITY.md` with exact tested versions.

## Updates and migration
Customers install the new APK over the old one (`adb install -r` or normal sideload). Same applicationId + same signing key => app data and the Keystore key survive,
so the licence stays active. Activation records are versioned (`rv=1`); future versions must still read them. Uninstalling or clearing data removes the activation
(see `docs/LICENSING.md` recovery). `android:allowBackup=false` and empty backup rules mean Android backups never restore (or leak) activation data.
