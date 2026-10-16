# Known limitations and remaining issues

## Not production-ready yet (honest status)
1. **No APK has been built.** The build environment could not reach Google Maven; see `docs/TESTING_REPORT.md`. Expect first-build fixes
   in the Compose UI and Gradle files, which were written but never compiled.
2. **Nothing has run on an Android device or emulator**, and nothing has been tested against a real WhatsApp. The screen classifier uses
   configurable view ids and activity class names that are best-effort guesses; calibrate them (`docs/DEVICE_TEST_PLAN.md`, section 1).
   Until then, expect `UNKNOWN_WHATSAPP` screens (safe behaviour: no auto-capture, manual confirmation).
3. **Tested WhatsApp versions: none. Tested Android versions: none.** The code targets Android 8.0+ (API 26+), tuned for 11+.

## Latest-screenshot and automatic-send limitations
- **Fully automatic sending is experimental and unverified on real devices.** WhatsApp has no API for it; it works by accessibility-clicking WhatsApp's own share screens
  (chat list, recipient preview, Send). The view ids/classes for those screens (`WhatsAppProfile.share*`, `sendButtonIds`, `outgoingMediaIds`) are unverified guesses. Do not claim
  automatic-send compatibility for any WhatsApp/Android version until `docs/DEVICE_TEST_PLAN.md` section 4b has been executed there.
- "Sent" is reported only when the chat shows more outgoing images than before; otherwise the app says the send "could not be confirmed". It cannot see delivery/read status.
- Chat identity is the visible chat title (hashed). Two chats with the same title cannot be told apart: the app then finds an ambiguous list row and stops, but "once per chat" would treat them as one chat.
- The sent-status ledger persists keyed hashes (no names) on the phone so "once per chat" survives restarts; it is cleared by the reset buttons. It is not encrypted beyond Android's app sandbox and the per-install key.
- "Handled" is recorded when the image is handed to WhatsApp (or sent), not when you actually press Send; if you cancel in WhatsApp, reset the chat status or use "Attach latest screenshot" (which ignores the repeat rules).
- If the newest screenshot cannot be read, an older one may be found; the app then refuses to act automatically and asks you to check it.
- Android 14+ "selected photos" access cannot reliably find the newest screenshot; the app asks for full photo access.
- Starting WhatsApp's share screen from a background service can be blocked by Android; the app then posts a notification to continue with one tap (untested).
- Screenshot folder detection relies on the folder name (default `Screenshots`); phones that store screenshots elsewhere need the name added in Settings.

## Platform and WhatsApp restrictions
- WhatsApp has no supported API to insert a file into an already-open chat. The app opens WhatsApp's own share screen (documented `ACTION_SEND`).
  The user chooses the chat and presses Send. Direct row selection is experimental and off by default.
- Accessibility text is not a unique identifier. Two chats may show the same title; a "verified" destination means "probably the same". The user
  always confirms the recipient in WhatsApp. When the title cannot be read, the app requires manual confirmation.
- Android 14+ lets each MediaProjection consent create one capture; with that method every capture asks again. Automatic mode therefore works
  best with the accessibility screenshot (Android 11+).
- Protected content (`FLAG_SECURE`, DRM, some banking apps) cannot be captured and the app does not try. MediaProjection may return black regions
  instead of an error; the accessibility API reports a secure-window error.
- Some manufacturers (aggressive battery managers) kill accessibility services; the user must exempt the app. The app stops its session when the
  service is disconnected rather than showing a false "active".
- The accessibility service is declared without a package filter so it can notice the user leaving WhatsApp; it only reads window content for the
  configured WhatsApp packages. Google Play restricts accessibility use; this product is intended for direct (APK) distribution.
- The text of the UI (Compose screens) is English only; status messages are in `strings.xml` and can be translated.
- A WhatsApp update can change view ids/classes; update `WhatsAppProfile` and ship an app update.

## Licensing limits (see `docs/LICENSING.md`)
- A purely offline licence **cannot** prevent copying. Device-bound licences stop casual sharing (a copied APK + licence + data does not work on
  another phone because the installation key lives in the Android Keystore), but a determined attacker with root, a modified APK, or
  runtime hooking can bypass checks. First-use licences can be activated on many phones.
- Replacement phone, lost phone, reinstall, "clear data": the activation is lost by design; the seller must issue a new licence (manual process).
  There is no revocation and no automatic transfer.
- Android Keystore behaviour (e.g. key invalidation after factory reset, OEM bugs) varies; Keystore failure blocks activation rather than bypassing it.
- If the vendor signing key or its passphrase is lost, no further licences can be issued for builds embedding that public key.
