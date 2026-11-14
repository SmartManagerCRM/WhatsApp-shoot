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

- Share-screen recognition is structural and heuristic (text rows without a message box = recipient list; message box + a send-like corner button = send screen; Send found by id, then by a "send" label in common languages, then as the bottom-right button). It has been exercised only against synthetic screens. If the same chat name appears twice in WhatsApp's share list (e.g. "frequent" and "recent"), the app treats it as ambiguous and stops rather than guess.

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
- Replacement phone, lost phone, reinstall, "clear data": the activation is lost by design; the customer enters the license code again (automatic binding) or, for a `device-bound` licence, the seller issues a replacement.
- Automatic binding is not strict one-device enforcement: a copied license code can be activated on another phone before either phone contacts the owner (no activation server exists).
  There is no revocation and no automatic transfer.
- Android Keystore behaviour (e.g. key invalidation after factory reset, OEM bugs) varies; Keystore failure blocks activation rather than bypassing it.
- If the vendor signing key or its passphrase is lost, no further licences can be issued for builds embedding that public key.

## Caption (text with the photo)
The caption is typed into WhatsApp's caption box on the preview screen (or in the gallery bar) just before Send. WhatsApp's box was not calibrated on a real device before v1.3.0:
the app finds it by an id containing "caption", otherwise the only editable box that is not the chat's own message box. If the box is not found, or the text cannot be set after 3 tries,
the photo is sent WITHOUT the text (it never blocks the photo). The diagnostics show `clicked SetCaption: true/false`. If the preview is skipped by WhatsApp, no caption can be added.

### v1.3.1: where the caption may be typed
v1.3.0 accepted ANY editable box other than `entry` as the caption box, also inside the gallery panel, where the chat's own message box is still on screen. The likely effect on a
real phone: the text was typed into the chat's message box behind the gallery, the keyboard opened and the gallery flow lost its Send button. Since v1.3.1 only a box whose id contains "caption" may
be used inside the gallery panel; any other editable box counts only on the preview screen (no gallery, no menu, no chat message box). The diagnostics line `caption box: named=… other=… preview=… using=…`
shows what the app saw. Not yet confirmed on a real phone.

## Catalogue (v1.4.0)
- **Reading**: the newest photo is read with Google ML Kit's on-device Latin text reader (model bundled, no download, no network). Arabic text is not read. Stylised, tiny or very noisy text may not be read; use *Try it* to check.
- **How the product picture is sent**: the app puts a copy of the product picture into the phone's gallery (album "WSS Products", Android 10+) as a brand-new photo, checks that the phone lists it as the newest image by DATE_ADDED, DATE_MODIFIED and DATE_TAKEN,
  and lets the normal "first tile of WhatsApp's gallery" flow send it. If that check fails, nothing is sent ("A product was recognised but its catalogue picture could not be prepared").
  The copy is deleted about 2.5 minutes after it was created (and leftovers on the next start). While it exists, other apps with gallery access can see it.
- Android 9 and older (no scoped storage): the catalogue picture cannot be prepared, so nothing is sent for a recognised product.
- Only used in fully automatic gallery mode. Not verified on a real phone before v1.4.0: the message (several lines) is typed into WhatsApp's caption box like the plain text; if the box is not found the picture goes without it.
- The ML Kit library declares network permissions for optional telemetry; they are removed from the manifest (CI checks the final APK has no INTERNET permission).

### v1.4.1: the catalogue step can no longer hold up a send
Possible failure found by reading the 1.4.0 code: the text reader runs on its own thread and only `Exception`s were caught, so a native-library or memory error (`Error`) would end that thread silently,
the controller would never get an answer and the send would be abandoned after the 12 s lookup watchdog ("nothing sent"). Now: every failure of the reader counts as "no match", the controller
stops waiting for the catalogue after 8 s and carries on with the newest photo, a late answer is ignored, the reader gives up after 6 s, and the start-up clean-up of old gallery copies runs on its own thread.
Diagnostics lines to look for: `catalogue: …`.
