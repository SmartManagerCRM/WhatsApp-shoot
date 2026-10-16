# Testing report

Date of run: 2026-10-16. Environment: Linux container, OpenJDK 21, Kotlin compiler 2.0.21 (`kotlinc`), JUnit 4.13.2,
Android `android.jar` API 34 (compile-time only). **No Android SDK, no Android Gradle Plugin, no emulator and no physical device
were available** (Google's Maven repository and SDK download host are blocked in this environment, and Maven Central returned HTTP 429/404).

This report separates three kinds of evidence. Nothing in the second or third column may be read as a statement that it was tested.

| Evidence | Meaning |
|---|---|
| **A. Automated JVM tests (PASSED)** | Executed here with JUnit. Real code, real cryptography (JDK ECDSA), fakes for Android-only dependencies. |
| **B. Compile check (PASSED)** | Android platform sources type-check against the real Android 34 API. Proves they compile, not that they behave. |
| **C. Not run** | Gradle build, APK packaging, Compose UI compilation, emulator or physical-device behaviour. |

## A. Automated JVM tests: 126 tests, 0 failures

Run with `scripts/run-jvm-tests.sh` (or `scripts/jvm-tests-quick.sh` in the authoring sandbox).

| Suite | Tests | Covers |
|---|---|---|
| `LicenseVerifierTest` | 13 | signature, tampering, product, version/policy/entitlement, malformed input, device binding, request codes, fail-closed on bad public key |
| `ActivationEngineTest` | 16 | activation, restart/update, copy to another installation, key loss, reinstall, device replacement, record corruption + backup slot, forged edits, Keystore/storage failure |
| `LicenseToolTest` | 10 | keygen -> issue -> verify -> activate-in-app round trip, unique IDs, wrong passphrase, missing key, malformed input, no secrets printed, no overwrite |
| `StateMachineTest` | 10 | transition table, stop from every state, no skipping verification, pause/resume |
| `ClassifierAndIdentityTest` | 14 | screen classification (synthetic snapshots), Business variant, destination verification, unambiguous share-row selection, hashed identity |
| `RepositoryAndGuardTest` | 7 | atomic save, retention cleanup that never deletes foreign files, storage failure, duplicate-capture guard |
| `ControllerTest` | 45 | full automation workflow with fakes (see matrices) |
| `PolicyTest` | 11 | static checks: no INTERNET permission, no network APIs, no backup, FileProvider scope, no logging, no `Send` automation, no private key committed, signing code absent from the app |

Sensitivity check: deleting the signature check in `LicenseVerifier` makes 3 tests fail (performed once, by hand).

Also executed manually (not a unit test): the packaged `dist/license-tool.jar` was driven through `keygen`, `inspect-request`, `issue`,
`verify` (valid), `verify` on a tampered file (`BAD_SIGNATURE`), and `issue` with a wrong passphrase (rejected). SHA-256 of the jar
built in this session starts `1cbf5345...`.

### Coverage of the requested test lists

Legend: **JVM-sim** = exercised by a JVM test against fakes/synthetic data; **Device** = needs a phone, not done.

Licensing (Part 12)

| # | Case | Status |
|---|---|---|
| 1 | Valid lifetime license | JVM pass |
| 2 | Invalid signature | JVM pass |
| 3 | Modified payload | JVM pass |
| 4 | Wrong product identifier | JVM pass |
| 5 | Missing license | JVM pass |
| 6 | Malformed license | JVM pass |
| 7 | Missing installation key | JVM-sim (fake Keystore) |
| 8 | Missing activation data | JVM-sim |
| 9 | Corrupted local activation record | JVM-sim |
| 10 | APK + license copied to a different installation | JVM-sim; real Android Keystore behaviour is Device |
| 11 | Normal application update | JVM-sim (new engine instance over same key+store); real `adb install -r` is Device |
| 12 | Reinstall | JVM-sim; Device for real uninstall/Keystore wipe |
| 13 | Device replaced | JVM-sim |
| 14 | Private signing key unavailable | JVM pass |
| 15 | License tool validation | JVM pass + manual CLI run |

Screenshot (Part 12)

| # | Case | Status |
|---|---|---|
| 1 | Capture permission granted | JVM-sim (controller + fake engine); real consent flow Device |
| 2 | Permission denied (and no re-prompt loop) | JVM-sim; Device |
| 3 | Permission revoked | JVM-sim; Device |
| 4 | Screen rotation | **Device** (each capture reads current display size by design; untested) |
| 5 | Display-size changes | **Device** |
| 6 | Capture-session termination | JVM-sim of error mapping only; `MediaProjection.Callback.onStop` path is Device |
| 7 | Low memory | JVM-sim of error mapping only; real OOM is Device |
| 8 | Temporary storage failures | JVM pass (repository `IOException`) + controller mapping |
| 9 | Screenshot cleanup | JVM pass |
| 10 | Protected-screen behaviour | JVM-sim of result mapping; real `FLAG_SECURE` is **Device** |

WhatsApp (Part 12) - all against **synthetic snapshots**, never real WhatsApp

| # | Case | Status |
|---|---|---|
| 1-4 | Closed / chat list / individual / group | JVM-sim |
| 5 | WhatsApp Business | JVM-sim (explicit package config) |
| 6 | User changes conversations | JVM-sim |
| 7 | User switches applications | JVM-sim |
| 8 | Attachment picker opens | JVM-sim |
| 9 | Destination cannot be identified | JVM-sim |
| 10 | Attachment workflow succeeds | JVM-sim with a fake launcher; real share-intent to WhatsApp is Device |
| 11 | Attachment workflow fails | JVM-sim |
| 12 | User cancels before sending | JVM-sim |

## B. Compile check

`scripts/compile-check-platform.sh` compiles every non-Compose Kotlin file under `app/src/main/java` against `android.jar` (API 34)
with stubs for `R`, `BuildConfig` and `androidx.core.content.FileProvider`. Result: success, 94 classes, no errors.

## C. Not run (be explicit)

- `./gradlew assembleDebug`, `assembleRelease`, `testDebugUnitTest`, `connectedAndroidTest`: **not run**, no APK exists.
- Compose screens (`app/src/main/java/.../ui`): **never compiled**, never rendered. Expect small compile fixes on first build.
- `AndroidManifest.xml`, resources, `build.gradle.kts` files: reviewed by `PolicyTest` text checks only; AAPT/AGP never ran.
- Android Keystore, StrongBox fallback, accessibility `takeScreenshot`, MediaProjection, notification actions, overlay window,
  FileProvider grants and the WhatsApp share flow: **no execution on any Android runtime**.
- The default WhatsApp view identifiers/class names in `WhatsAppProfile` are **unverified guesses** and must be calibrated on a device.
- R8 minification / ProGuard rules: not exercised.

See `docs/DEVICE_TEST_PLAN.md` for the checklist that closes these gaps.
