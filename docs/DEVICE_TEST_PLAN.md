# Device test plan (NOT YET EXECUTED)

Every box is unchecked: **none of this has been run**. Record device model, Android version, WhatsApp version (Settings > Help > App info)
and the app build for each pass. Suggested minimum matrix: Android 10 or 11 (MediaProjection path), Android 13, Android 14 or 15 (single-use
projection token), plus one device with a custom skin (Samsung or Xiaomi).

## 0. Build
- [ ] `./gradlew :app:assembleDebug` succeeds (fix any first-build Compose/AGP issues).
- [ ] `./gradlew testDebugUnitTest :license-core:test :license-tool:test` passes.
- [ ] Provision keys: `scripts/provision-dev-key.sh`, rebuild, install on the device.

## 1. WhatsApp calibration (do first; everything else depends on it)
- [ ] Enable the accessibility service. Open each WhatsApp screen and record the window class name and view ids
      (use Android Studio Layout Inspector, `adb shell uiautomator dump`, or `adb shell dumpsys activity top`).
- [ ] Update `WhatsAppProfile` (class names, `conversationHeaderIds`, `inputFieldIds`, group/individual markers, picker ids,
      `shareRowTitleIds`) until: chat list, 1:1 chat, group chat, settings, calls, status, media viewer, attachment picker,
      share list all classify correctly. Repeat for WhatsApp Business.
- [ ] Confirm header text is read for 1:1 and group chats; note languages/RTL tested.

## 2. Screenshot
- [ ] Accessibility screenshot (Android 11+): full screen incl. status bar and navigation bar, original resolution, not cropped.
- [ ] MediaProjection path: first consent, repeated capture (Android <14 reuses session; 14+ asks again every time).
- [ ] Consent denied once: no repeated prompting by automatic mode; manual button asks again.
- [ ] Consent revoked from the system "stop sharing" chip while the session is active.
- [ ] Rotate to landscape and back before and during capture; change display size / font size / split-screen.
- [ ] Capture a `FLAG_SECURE` screen (e.g. a banking app): error message shown, no bypass.
- [ ] Low storage and low memory behaviour.
- [ ] Overlay button is NOT visible in the captured image.
- [ ] Screenshot files appear only under `cache/screenshots/`, are removed on Done/Delete/Stop and after the retention period; unrelated files untouched.

## 3. Detection and modes
- [ ] Automatic, Delayed (change delay), Manual modes; Start/Stop/Pause/Resume; notification actions; floating button only while active.
- [ ] Opening, leaving, re-entering a chat; switching chats before the timer fires; switching apps; opening the attachment picker.
- [ ] No duplicate captures while staying in one chat; no capture after Stop; no capture while paused.
- [ ] Permission revocation of the accessibility service while running: session stops, UI does not claim "active".

## 4. Attachment
- [ ] Notification "Attach to WhatsApp" with WhatsApp still in front: destination verified, WhatsApp share list opens with the image.
- [ ] From inside the app: manual-confirmation card appears; "Open WhatsApp sharing" works; image attached; Send is never pressed.
- [ ] Changed conversation before attach -> "destination changed" and nothing launched.
- [ ] Group with duplicate display names / contacts with identical names -> ambiguous -> manual.
- [ ] Experimental direct selection on/off; ambiguous and not-found outcomes fall back to manual; Send never pressed.
- [ ] WhatsApp Business routing; WhatsApp not installed message.
- [ ] URI grant works on the tested WhatsApp versions (image visible in WhatsApp preview) and is revoked on cleanup.

## 4b. Latest screenshot and automatic sending (all unchecked)
- [ ] Photo permission: grant / deny / (Android 14+) "selected photos only" -> correct messages, no crash, no repeated prompting.
- [ ] Screenshots folder detection on: Pixel (Pictures/Screenshots), Samsung (DCIM/Screenshots), Xiaomi, plus a phone with a custom folder name.
- [ ] Newest screenshot chosen correctly after taking a new one, after deleting the newest, after a screenshot is still being written (pending), PNG/JPEG/WebP.
- [ ] Nothing outside the Screenshots folder is ever used (put a newer camera photo in DCIM/Camera).
- [ ] Behavior A/B/C with 3+ chats, reopening, reset status, restart of the app/phone (ledger persistence), identical copies of an image.
- [ ] Mode C: take a screenshot while the chat is open -> hand-off after ~1.5 s only once.
- [ ] Chat title unreadable / duplicate titles -> manual confirmation, nothing recorded.
- [ ] Share-screen calibration: record window classes/ids of WhatsApp's recipient list, preview/send screen, recipient label, Send button, outgoing image bubble; update `WhatsAppProfile`.
- [ ] Fully automatic send (opt-in dialog shown; default off): correct chat -> sent; confirm "Sent" appears only when a new outgoing image is visible; wrong/ambiguous recipient -> nothing sent.
- [ ] Chat switched mid-flow -> nothing sent. Stop button (floating and notification) halts clicks immediately.
- [ ] Background-activity-start: does Android allow the share screen to open from the service? If not, does the fallback notification work?
- [ ] Record exact WhatsApp / Android versions for each pass in `docs/WHATSAPP_COMPATIBILITY.md`.

## 5. Licensing on real Keystore
- [ ] Create request code, issue device-bound license with the tool, activate: Active.
- [ ] Reboot, force-stop, update in place (`adb install -r` with same signing key): still Active.
- [ ] Uninstall + reinstall: NotActivated; old bound license rejected as other installation; re-issue works.
- [ ] Clear app data: same as reinstall.
- [ ] Copy APK to a second phone and import the same license: rejected (device-bound). First-use license: documented as shareable.
- [ ] Device without StrongBox; device with StrongBox; behaviour when Keystore is locked/unavailable.
- [ ] Release build only: tampered APK (re-signed with another key) refuses to run licensed features (signer pin).

## 6. Release
- [ ] `assembleRelease` signed; R8 build runs all of the above (service, receiver, activities survive minification).
- [ ] `apksigner verify --print-certs` matches the pinned `signerSha256`.
- [ ] Upgrade from the previous release keeps the license.
