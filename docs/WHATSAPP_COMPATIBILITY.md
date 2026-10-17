# WhatsApp and Android compatibility

## Tested versions
| | Tested |
|---|---|
| WhatsApp (com.whatsapp) | **none** |
| WhatsApp Business (com.whatsapp.w4b) | **none** |
| Android | **none** (compile-checked against API 34 only) |

Everything WhatsApp-specific was verified against synthetic screen snapshots in JVM tests, **plus calibration data copied from one real phone**
(below). Having *read* a real phone's screens is not the same as having *tested* the whole flow on it; the flow has not yet been confirmed end to end.

### Calibration data from a real device (Infinix X6856, Android 16; WhatsApp Business 2.26.34.74, WhatsApp 2.26.39.74)
Obtained from the app's own diagnostics report (view-id names and classes only).
| Screen | Activity class | Useful view ids |
|---|---|---|
| Chat list | `com.whatsapp.home.ui.HomeActivity` | `conversations_row_contact_name`, `contact_selector`, `fab` |
| Conversation | `com.whatsapp.Conversation` | `conversation_contact_name` (title), `entry` (message box), `input_attach_button` |
| Share list (opened by the app's share intent) | `com.whatsapp.contact.ui.picker.ExternalShareContactPicker` | `contactpicker_row_name`, `menuitem_search`, `list`; the list fills in *after* the screen opens |
| Share list after a chat is selected | the **same** class | `recipients` (recipient label), `send` (Send button, bottom right), `appended_message` (caption box), `thumbnail` |
Consequences built into the app: the share list is given time to load and is scrolled if the chat is not in view; the "send bar" is recognised
by the presence of `send` inside the picker class; the recipient label must name exactly one recipient equal to the verified chat.

## How WhatsApp is recognised
`WhatsAppProfile` (in `logic/ScreenModel.kt`) lists supported packages, activity class names and view ids. A screen counts as a conversation only if
two independent signals agree (e.g. activity class + conversation header view, or header + input field). Anything else in WhatsApp becomes
`UNKNOWN_WHATSAPP`: no automatic capture, manual confirmation. The shipped defaults are educated guesses; **calibrate before relying on them**
(`docs/DEVICE_TEST_PLAN.md`, section 1). Other WhatsApp builds/forks are *not* matched unless added to `packages` explicitly.

## Attachment: what is and is not possible
- Supported mechanism: Android `ACTION_SEND` (image) addressed to WhatsApp, read-grant to WhatsApp only, via `FileProvider`. WhatsApp shows its recipient list.
- Not possible via supported means: attaching into the chat that is already open, or choosing a recipient through the intent.
- Experimental, off by default: the accessibility service tries to tap the single list row whose title hash equals the chat of the screenshot. It acts once per share launch, only when
  exactly one row matches, never presses Send, and reports ambiguity/timeouts back so the user can finish manually. May break with any WhatsApp update.
- Unsupported interface states: conversation title not exposed to accessibility (e.g. some custom layouts/RTL edge cases), disappearing UI during share, multi-window/split-screen with two WhatsApp windows, work-profile or cloned WhatsApp.
- Not used: unofficial WhatsApp APIs, automation of the Send button, background sending.

## Android
- minSdk 26. Accessibility screenshot needs Android 11+. MediaProjection works on all, with single-use consent on 14+.
- Background activity-start limits: the consent dialog and the share screen are started from user taps (app UI or notification action); automatic mode only posts a notification when it needs the user.

## Fully automatic sending: in-chat attach flow (v1.0.4)

Since 1.0.4 "Fully automatic sending" no longer uses WhatsApp's share list. The chat is already open, so the app drives WhatsApp's own
attach menu **inside that chat**: attach (+) → Gallery → **Screenshots** album → newest picture → Send. The destination is the open chat itself,
so no name matching is needed. Rules enforced by `AttachFlowEngine` (unit-tested):

- a picture is chosen only after the Screenshots album (the folder names from Settings) was opened; if the album cannot be found nothing is sent;
- Send is tapped only after a picture was chosen, never while the chat screen is in front (there that button is the voice-note button);
- "Sent" is reported only if the chat shows more outgoing media than before; otherwise "sent, unconfirmed".

**Not verified on a real phone yet.** The labels it looks for (attach button, Gallery, Screenshots/Albums, Send) are matched by view id, accessibility
label or text in common languages; WhatsApp versions differ. If a step is not found the run stops with a specific message and the Home screen's
*Copy diagnostics* shows the last step reached. Manual mode (no opt-in) still opens WhatsApp's share sheet.

### v1.0.5: screenshots folder as a path, album search
Real-phone result for 1.0.4: the app opened WhatsApp's gallery but did not find the Screenshots album. Changes:
- Settings accepts a folder **path** (e.g. `/storage/emulated/0/Pictures/Screenshots`) as well as a bare name. A path pins the exact folder for the app's own lookup;
  its last segment is the album name the app looks for in WhatsApp's gallery. (WhatsApp's picker lists albums by name, so a path cannot be handed to it directly.)
- Album labels are now also read from content descriptions of any node, and the gallery is scrolled (up to 6 pages) to reveal the album.
- When the album is not found, the activity log lists the labels seen in the gallery ("picker labels: ..."; gallery album names only), so the matching can be corrected.

### v1.0.6: calibrated on a real WhatsApp Business screen dump (2.26.34.74, Android 16)
The 1.0.5 report showed how WhatsApp Business actually builds its attach UI:
- the attach menu (`pickfiletype_*_holder`) and the gallery (`gallery_view_pager`, `grid`, tiles `media_item_view`) are **panels inside the chat window**
  (window class stays `com.whatsapp.Conversation`), so the chat's title and message box remain on screen. "Chat in front" therefore now also requires that no menu/gallery is open;
  the earlier version thought the chat was still in front and never looked for albums.
- albums are chosen from a dropdown (`gallery_spinner`); the album names exist only inside the opened dropdown (a popup window). The app now scans every WhatsApp window,
  taps the dropdown, taps the wanted album, and **verifies the dropdown now shows that album** before it taps the first tile (`media_item_view`) of the grid.
- the chat's own media thumbnails (`thumb`) are never used as gallery tiles.
- Send is found only by WhatsApp's id (`send`) or a "send" label. The position-based guess used for the share sheet is NOT used in the gallery flow: in the chat the camera button sits in the same corner.
- if no Send control is identified, the log prints the lower on-screen controls ("lower controls: …") so the id can be added.
