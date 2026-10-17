# Screenshot Assistant for WhatsApp

Android app that, when a WhatsApp conversation is opened, finds your **latest existing screenshot** (it takes no new screenshot by default) and prepares it as an image attachment
through WhatsApp's own share screen. By default you choose the chat and press Send. An optional, off-by-default "fully automatic" mode can press Send itself after the chat and recipient are verified. (Taking a new screenshot instead is still available in Settings.) One-time purchase, lifetime licence, fully offline:
no INTERNET permission, no backend, no accounts, no analytics.

## Honest project status

| Part | State |
|---|---|
| Licensing core (signed licences, installation-bound activation) + owner CLI | Implemented; **39 JVM tests pass** (285 in total); CLI exercised end to end |
| Automation logic (state machine, classifier, destination checks, duplicate guard, storage, controller) | Implemented; **168 JVM tests pass** against fakes / synthetic screens |
| Android platform glue (accessibility service, capture engines, Keystore, notifications, share launcher) | Written; **compiles against Android 34 API**; never executed on Android |
| Compose UI, manifest, resources, Gradle build | Written; **never compiled** (Google Maven unreachable where this was built) |
| APKs | **None built** (debug or release) |
| Real WhatsApp / real device behaviour | **Not tested.** View-id defaults are unverified guesses; see the device test plan |

Read `docs/TESTING_REPORT.md` and `docs/KNOWN_LIMITATIONS.md` before relying on anything. The app must not be described as production-ready until
`docs/DEVICE_TEST_PLAN.md` and `docs/RELEASE_CHECKLIST.md` are completed.

## Repository layout
```
app/              Android application (Kotlin, Compose, Material 3)
license-core/     shared licence format + verifier + activation engine (pure JVM)
license-tool/     owner-only CLI to create keys and issue licences (pure JVM)
dist/             license-tool.jar (built from the sources here; rebuild with scripts/build-license-tool.sh)
docs/             architecture, licensing, key management, release, install, manual, compatibility, testing
scripts/          JVM test runner, platform compile check, tool build, dev key provisioning
```

## Quick start
Run the verified JVM tests (needs JDK 17+, `kotlinc` 2.0.x, and JUnit 4.13.2 + hamcrest-core, e.g. from a Gradle distribution's `lib/`):
```
scripts/run-jvm-tests.sh <kotlinc-dir> <android.jar> [workdir]
```
Build the owner tool: `scripts/build-license-tool.sh <kotlinc-dir>`, then see `docs/LICENSE_TOOL.md`.
Build the app (needs Android SDK 35 and network access to Google Maven): see `docs/RELEASE.md`.

## Documents
`ARCHITECTURE`, `LICENSING`, `LICENSE_FORMAT`, `KEY_MANAGEMENT`, `LICENSE_TOOL`, `LICENSE_ADMIN`, `RELEASE`, `INSTALLATION`, `USER_MANUAL`, `WHATSAPP_COMPATIBILITY`,
`PERMISSIONS_AND_PRIVACY`, `TESTING_REPORT`, `DEVICE_TEST_PLAN`, `KNOWN_LIMITATIONS`, `RELEASE_CHECKLIST` - all under `docs/`.
