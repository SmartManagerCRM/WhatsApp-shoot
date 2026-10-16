# Release checklist

Tick only what was actually done. Last updated for the first source drop: **nothing below the "Source" section has been done yet**.

## Source (done in this repository state)
- [x] JVM unit/policy tests pass (126) - `docs/TESTING_REPORT.md`
- [x] Platform sources type-check against Android 34
- [x] License tool built and exercised end to end
## Build
- [ ] Gradle sync + `assembleDebug` OK; Compose UI compiles
- [ ] `:license-core:test :license-tool:test testDebugUnitTest` OK under Gradle
- [ ] `license-public-key.txt` is the PRODUCTION public key (fingerprint recorded)
- [ ] Production signing key generated offline, two backups verified, passphrase stored separately
- [ ] Release keystore created, two backups, `signerSha256` set
- [ ] `assembleRelease` OK; `apksigner verify` OK; certificate matches `signerSha256`
## Device
- [ ] `docs/DEVICE_TEST_PLAN.md` fully executed on the supported matrix; results copied to the testing report with device/OS/WhatsApp versions
- [ ] `WhatsAppProfile` calibrated; `docs/WHATSAPP_COMPATIBILITY.md` lists exact tested versions
- [ ] Update-in-place keeps licence; reinstall / copied-APK behaviour confirmed on real Keystore
- [ ] R8 build tested (service, receiver, activities, FileProvider)
## Business / docs
- [ ] `versionCode` incremented, `versionName` set, changelog written
- [ ] Customer docs reviewed (installation, manual, recovery), seller recovery procedure and ledger in place
- [ ] No secrets in the repository (`PolicyTest`, `git log -p | grep` review)
- [ ] Distribution channel decided (accessibility apps are restricted on Google Play; direct APK distribution assumed)
