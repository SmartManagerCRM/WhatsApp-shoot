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

### v1.0.7: landing gallery first
On a real phone the Screenshots-album route stalled in WhatsApp's landing gallery. Fully automatic sending now takes the **first picture of the landing gallery**
when (and only when) the phone's media library confirms that the staged screenshot is the newest picture on the device (`isNewestPicture`: it is the newest image, and the next one is strictly older).
If a newer picture exists - or the setting "Use WhatsApp's landing gallery" is off - the Screenshots-album route (v1.0.6) is used instead.
Limit: only images are checked (the app has no video permission), so a video newer than the screenshot could still be the first tile. That is the accepted trade-off of this mode.

### v1.0.8: photos only
Real-phone result for 1.0.7: it worked, but when the newest item on the phone was a video, the video was sent.
- New route decision (`galleryRoute`): the landing gallery is used only if the screenshot is the newest image AND (video access granted) no video is as new or newer. If a newer video exists elsewhere, the Screenshots album is used.
  If a video as new as the screenshot sits in the screenshots folder, nothing is sent ("newest item is a video").
- If the tile about to be tapped shows a duration (m:ss) or a "video" description, nothing is sent.
- This needs `READ_MEDIA_VIDEO` (date lookups only). Without it the landing gallery is not used and the Screenshots album route applies.
- Licence persistence: the Keystore can be briefly unavailable (e.g. just after boot). The activation check now retries and, if the Keystore still holds the recorded key but cannot sign right now, keeps the licence active instead of discarding it. Records copied to another phone are still rejected (that phone has no such key).

### v1.1.1: "Newest photo" is now the default source (no folder lookup)
Real-phone result for 1.1.0: the app could not find the Screenshots folder/album. The default source is now **WHATSAPP_GALLERY** ("Newest photo"):
- the newest PHOTO of the whole phone is found in the media library (images only, any folder, only for the ledger/preview) - no screenshot folder is needed;
- in WhatsApp the flow is + > Gallery > the first tile of the landing gallery > Send (no album dropdown);
- never a video: tiles that show a duration or "video" are skipped (first tile without a marker is taken). With no marker visible, the media library says how many videos are as new as the newest photo (`TilePlan.skip`, needs the video permission):
  0 = tile 0, n = tile n (by position, logged). With no marker visible AND no video permission nothing is sent, because a video would look the same ("Allow video access" on Home);
- settings saved by older versions are switched to this source once (settings version 2). "Latest screenshot from a folder" and "Take a new screenshot" remain available.
The activity log shows `tiles: n=.. video=pppV.. choice=...` so the choice can be checked.

### v1.2.0: languages and automatic license binding
- The app (screens, notifications, floating button) ships in English, Arabic (right-to-left) and French; Settings > Language switches it at once. `TranslationsTest` fails the build if any string is missing in a language, if placeholders differ, or if a screen hard-codes English text.
- First launch creates the installation key; a license is entered with no request code and binds to that installation. See `docs/LICENSING.md` for the limitation (a copied code can be activated on another phone).
