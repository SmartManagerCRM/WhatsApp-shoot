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

### v1.4.2: "could not put the product picture in the gallery as the newest photo"
A real-phone report (Infinix X6856, Android 16, v1.4.1) showed the text reader and the product match working (`catalogue: read N characters, 1 products, matched=true`) and then the gallery copy being refused by the
"newest by every date" check, so nothing was sent. Most likely cause: after the copy is finished the phone's media scanner rewrites its dates and leaves DATE_TAKEN empty (the copy had no EXIF capture time), so by DATE_TAKEN the copy was not first.
Now the copy carries an EXIF capture time (= now, with time zone offset), DATE_TAKEN is put back and re-read if the scanner lost it, and a failed check logs which date failed and the values involved:
`catalogue: gallery copy failed: DATE_x: … (own=…, first=…, second=…)`. If it still fails, that line says why.

## v1.6.0: back to sending the catalogue picture, plus details read on the photo
The catalogue picture is sent again for a recognised product (through the temporary gallery copy of v1.4.x, which now carries an EXIF capture time and logs why it is refused if the phone does not list it as the newest image;
a failure still sends nothing). The v1.5.0 start-up clean-up of old copies was dropped because copies are made again; each copy is deleted about 2.5 minutes after it was made.
New: an order number, a date and a branch read on the photo are added to the message. They are found only by their English/French label in Latin letters (see the manual); Arabic text is not read, and a receipt layout with the value far from its label
(for example in a table column) may not be understood. Not verified on a real phone before v1.6.0: use *Try it* on your own photos to see what is found.

### v1.6.1: order of the steps, and reading small print
The steps always run in this order, once per chat: (1) read the newest photo in the gallery (the ORIGINAL, before anything is copied), (2) extract the order number / date / branch and the product from that text,
(3) only then put the catalogue picture in the gallery, (4) build the message from the product, the details read in step 2 and the user's text, (5) tap through WhatsApp and send once.
The catalogue copy can never be read for details: it is created after step 1 and ignored when looking for "the newest photo". If the details are missing from a sent message, the photo's labels were not recognised (the diagnostics line
`catalogue: read N characters … order=… date=… branch=…` shows which were found). v1.6.1 reads the photo at up to 2560 px (was 1600) so small print on a photographed receipt is less likely to be lost.

### v1.6.2: more ways to find the details, and a way to see what the phone reads
A real phone reported that none of the order number, date or branch was found. The cause is not known yet (the text the phone reads on that photo was not available). Changes:
- Arabic-Indic / Persian digits are converted to ordinary digits before searching; odd space characters are normalised.
- Order number: an order/invoice/receipt/transaction label first, then a generic reference label (Ref, N°, No., Numéro, #), then order-code shapes without a label (CMD-…, ORD…, INV…, PO…, BL…).
- Date: also `12-Nov-26`, and `12 Nov` (no year) on a line that says "date".
- Branch / shop: also "Shop name", "Store name", "Nom du magasin", "Seller", "Vendor", "Merchant", "Sold by", "Location", "Emplacement".
- **Try it** now shows up to 1500 characters of what the phone read and has a **Copy the text read** button, so the exact text can be checked or sent for calibration.
- The diagnostics contain `catalogue: text make-up latin=… arabic-script=… digits=… lines=…` (counts only, never the text): if arabic-script is large and latin small, the photo is in Arabic and the Latin reader cannot read its labels.
Still not possible: reading labels written in Arabic script (the text reader is Latin-only).

### v1.7.0: notes read on the photo
Any note on the photo is added to the message after the order number, date and branch ("Notes: ..."; "Remarques: ..." in French). Found by its label (Notes, Note, Remark, Remarque, Observation, Comment, Commentaire, Instructions, Memo) followed by ":" or "-", or a line that starts with the label;
a label alone on its line takes the next lines (up to 3) until a blank line or another "label: value" line. Cut at 200 characters. Words that merely contain a label ("Notebook", "Commentary") are ignored.
Handwritten notes and notes in Arabic script cannot be read by the Latin text reader. A note that is not introduced by one of these labels is not recognised. Not verified on a real phone.

## v1.8.0: Arabic text on the photo
A second on-device reader (Tesseract with the Arabic language data, both bundled in the app: no download, no network) reads Arabic script. Its text is added to the Latin reader's text before the details are searched, so
**notes written in Arabic are added to the message**, and the order number, date and branch are also found by their Arabic labels (رقم الطلب, التاريخ, الفرع, اسم المتجر, ملاحظات, ملاحظة, تعليق, تعليمات ...), with Arabic-Indic digits and Arabic month names.
- Cost: about +12 MB in the APK (library, four processor types) and +2.5 MB of language data; a few extra seconds per send (both readers run at the same time; the Arabic one is stopped after 14 s and then only the Latin text is used).
  The "Read Arabic text too" switch on the Catalogue tab turns it off.
- Accuracy: Tesseract is less accurate than the Latin reader, especially on photographs, small, stylised or handwritten Arabic. A misread letter is sent as read: check important notes. Unreliable text is not filtered.
- The note must be introduced by a label (see above). A note written next to its label on the same line, or on the lines below a label standing alone, is found; free text without a label is not.
- Third-party components (both Apache License 2.0): Tesseract OCR and the `ara` language data (tessdata_best, integer model); Tesseract4Android (adaptech-cz). The library is fetched from JitPack at build time only.
- Not verified on a real phone before v1.8.0: use *Try it* to see what is read.

## v1.8.1: 64-bit phones only
The APK contains native code only for `arm64-v8a` (all current phones) and `x86_64` (the emulator that tests every build in CI). The 32-bit types (`armeabi-v7a`, `x86`) were dropped to cut the APK size
(the two text readers ship native code per processor type). A phone whose system is 32-bit only (some old budget phones, and some phones with a 64-bit processor but a 32-bit Android) cannot install the app: Android says it is
not compatible. Check with a customer's phone before selling if it is old. The x86_64 code is not needed on phones; dropping it too would save more but the release build could then no longer be launch-tested in CI.

### v1.8.2: notes in a "Notes" box (found on a real order screenshot)
A real order screenshot (a delivery-app order page: "Notes" heading, then a grey box with small Arabic text, then "Order Summary") produced the message line "Notes: Order Summary a": the Latin reader saw the English word "Notes", the Arabic text in the box was never read,
and the next English text on the page ("Order Summary") was taken as the note. Now, when the Latin reader finds a heading that stands alone on its line (Notes, Note, Remarks, Remarques, Comments, Commentaires, Observations, Instructions ...):
- text directly under the heading (line gap up to 1.2 line heights) is its note if written in Latin letters; text further below is another section and is never taken;
- when "Read Arabic text too" is on, the strip between the heading and the next section is cropped, enlarged up to 3x (the print is small) and read by the Arabic reader on its own, at the same time as the rest; an Arabic result replaces the Latin guess;
- emoji and stray marks are dropped, lines are joined, the note is cut at 200 characters; if nothing readable is under the heading, no note is added (instead of a wrong one).
If the heading is itself written in Arabic ("ملاحظات") the Latin reader cannot see it: the earlier text-based search is used (Arabic label followed by the note). The strip method needs a heading on its own line; small, low-contrast or handwritten Arabic may still be misread.
Diagnostics: `catalogue: notes heading found, strip=…px arabic-note=… latin-note=… note-used=…` and `catalogue: notes strip read … ms, … characters` (counts only).

### v1.8.3: Arabic note under a "Notes" heading, tested on the real order photo
The real order photo (1080 x 2436, "Notes" heading, grey box with three lines of small Arabic print, then "Order Summary") was run through Tesseract 5 with the bundled Arabic data:
- the **whole photo** at 1600 px gives garbage; the **strip under the heading**, enlarged about 2x, gives the note almost exactly (all three lines);
- a strip cut too tight gives garbage, a strip cut in the middle of a line mangles that line, and a window that reaches the next row adds a garbage line made from "Order Summary" and its numbers.
Therefore (1) the heading is found with more tolerance (an icon misread as a short token in front of "Notes" is ignored); (2) the Latin reader invents junk lines for Arabic print, so junk can no longer end the strip: only a real English-looking section heading
(6+ Latin letters, e.g. "Order Summary") does, and a short or non-word line is ignored; (3) if the strip yields nothing, a generous window under the heading (18 % of the picture height) is read as a second try; (4) from the Arabic reader's output only the first block of mostly-Arabic lines is kept
(short lines such as an emoji are skipped; the block stops at an English line or a line full of digits). Not verified on the phone itself: the reader there is the same Tesseract with the same data, but speed and small differences in the picture are not identical.
Diagnostics: `catalogue: notes heading found, strip=…px window=…px arabic-note=… latin-note=… note-used=…` and `catalogue: notes strip read … ms in N pass(es), … characters`.

### v1.9.0: several orders on one photo, notes icon, speed, licence backup, "no scrolling"
**Not verified on a phone; the Android-side code is only checked by CI and the emulator smoke test.** What was checked on a computer: the icon finder, the order splitter and the sequential-send logic (JVM tests), and the icon finder + Tesseract on the two real order screenshots (both icons found; both Arabic notes read, apart from one junk line at the end of the second when the strip ran into the next section, which the English section line cuts off in the app).
- **Several orders on one photo.** The Latin reader's lines are searched for order numbers (a label such as Order/Commande/رقم الطلب or `#` followed by 5+ digits, or a code like CMD-12345). Two or more *different* numbers = several orders; the photo is cut into one band per order (from a little above each order-number line to the next one; at most 8). Each band gives its own product match, date, branch, order number and note; each order whose product is in the catalogue is sent as its own message (product picture + all its details + the notes + your Home text), one after the other in the same chat visit. An order whose product is not in the catalogue is skipped (and logged), because sending the whole multi-order photo would put other customers' orders in the wrong message. If the sender's layout puts the order number somewhere that is not read as a number, the photo is treated as one order. A wrongly split single order would send that order twice if both halves match the same product: report such a photo.
- **Notes icon.** When there is no "Notes" word, the left 30% of the photo is searched for the small notes icon (a rounded document outline holding three short bars, at most about 8.5% of the photo width). The note is read from the area around it (from a little above the icon, because a line such as "write on the card:" can start above it, down to the next English section line or about 4 icon heights). Needs "Read Arabic text too" on. A different icon design, or a photo where the icon is crossed by other marks, will not be found.
- **Speed.** The whole-photo Arabic read (slow and often garbage on screenshots) is now cancelled when the Latin text already gives the product and the order number and date; the newest photo is read in the background as soon as WhatsApp comes to the front (a reading is kept 10 minutes for exactly that photo), so a chat that opens afterwards usually needs no reading at all.
- **Licence.** Each activation slot is also kept as a private file and restored if the preferences file is lost; while the licence is still being checked at start-up the Home screen says "Checking your license..." instead of "Activate your license". Diagnostics: `license: record restored from its backup file`.
- **No scrolling needed.** The screen check is no longer pushed back by every new event (a screen that keeps sending events could starve it), and the safety re-check while WhatsApp is in front no longer stops after ~18 s (it keeps going every 2.5 s while the session waits for a chat). If the app still waits, the log now says why (`auto start declined: state=… licensed=…`).
Diagnostics: `catalogue: N orders found on the photo`, `catalogue: order i/N: matched=… order=… date=… notes=…`, `next order: …`, `catalogue: notes icon found (WxHpx) … note-used=…`, `catalogue: using the reading made ahead of time`, `catalogue: Latin text is enough, whole-photo Arabic reading skipped`.

### v1.9.1: several orders without a usable order number
v1.9.0 only split a photo when it found two different order numbers of 5+ digits. The real order pages show a short code (e.g. 4 digits) or none, so the photo was read as one order. Now, when fewer than two such numbers exist, the repeated heading "Order Status" (else "Order Summary") marks the orders, and the "Total" line that closes an order marks where the next order's header (date, shop) begins. Checked only with synthetic line positions and the heading positions measured on the two real screenshots; not run on the phone. If your photo's headings differ (another app, another language), the log line `catalogue: read as one order (…)` shows it was not split; send that log.

### v1.9.2: splitting at "Total" lines, and a layout line in the log
The v1.9.1 log from the phone said `read as one order (20 text lines; no repeated order number or Order Status/Summary heading)`: the real two-order photo has neither. A last-resort split was added: two or more separate "Total" lines (an order's closing line; "SubTotal" does not count; lines within 12 line heights count once) = that many orders, each starting under the previous Total. Also the log now has `catalogue: layout (top%: shape): …`, one entry per text line with only its structure (words like Order/Status/Total/Notes kept; other letters = a, Arabic = ع, digits = 9), so a photo that is still read as one order can be understood without any private text. Not verified on the phone.

### v1.9.3: longer activity log
The activity log kept only the last 120 lines; one send writes about 60, so by the time the report was copied the two-order attempt had been pushed out and only later, unrelated runs were left. It now keeps the last 600 lines.

### v1.9.4: several items on ONE order page
The v1.9.3 layout line showed the photo is a single order page (one "Order Status", one "Notes", one "Order Summary") with two item lines under "Order Summary" ("1X ..." and "1X ..."), not two orders. The product match took only the better-scoring product of the two. Now every item line that starts with a quantity (1X, 2x, 3 x ...) and names a catalogue product gives one message (that product's picture and price/description + the order's number, date, branch and notes + your Home text), sent one after the other in the same chat; each product only once. Item lines the text reader cannot read, or whose product is not in the catalogue, are skipped. Diagnostics: `catalogue: N items of this order name catalogue products …`. Not verified on the phone.

### v1.9.5: second product sent the same photo; repeated sends to the same chat
From the v1.9.4 phone log: both items were found and both messages went out, but the second message carried the same photo, and after the sends the app sent again (twice more) to the same chat.
- **Same photo for both.** Most likely cause (not proven; the phone's log cannot show which tile was clicked): WhatsApp keeps finishing its own copy of the photo it just sent for a few seconds, and that copy could be listed before the next product picture in WhatsApp's gallery, so the first tile was the previous photo again. Now the next product picture is put in the gallery 3.5 s after the previous send (not 2 s), and it is only accepted if it is still the newest picture (by DATE_ADDED, DATE_MODIFIED and DATE_TAKEN) 0.7 s after it was made; otherwise it is made again (up to 3 tries). If it still happens, the log has `catalogue: gallery copy failed: after 700 ms: …` lines or none, and I need to know which photo was sent twice.
- **Sending again and again.** After a send the app returned to the same open chat, saw a "new" newest photo (WhatsApp's own saved copy of what it had just sent, which is in WhatsApp's media folders) and sent that. Two fixes: pictures inside WhatsApp's own media folders (any folder name starting with "WhatsApp", or "com.whatsapp…") are never taken as the newest photo any more, and a chat that was just sent to is not sent to again until the user has been to the chat list or another chat, or has been away from WhatsApp for more than 8 s. A manual "send now" is not blocked. Side effect: a photo that really is in a WhatsApp folder can no longer be sent by this automatic mode.

### v1.9.6: opening the same chat again sends again
The "already sent to this chat" block now ends as soon as the user goes to the chat list, opens another chat, or has been away from WhatsApp for more than 8 s (was 30 s), so opening the chat another time sends again. It only stops the app re-sending within the same opening (system windows that appear for a moment after a send). The setting "Send: every time a chat is opened" must be chosen in Settings; "once per chat" still sends only once ever per chat.
