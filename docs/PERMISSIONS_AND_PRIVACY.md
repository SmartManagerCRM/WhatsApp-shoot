# Permissions and privacy

| Permission / capability | Why | Notes |
|---|---|---|
| Accessibility service (`BIND_ACCESSIBILITY_SERVICE`, `canRetrieveWindowContent`, `canTakeScreenshot`, `flagReportViewIds`, `flagRetrieveInteractiveWindows`) | Detect WhatsApp / open conversation, read the conversation title for the hashed identity, take the screenshot, host the floating button | User must enable it in system settings. No package filter (needed to notice leaving WhatsApp); content of other apps is never read. |
| Screen capture (MediaProjection) | Alternative screenshot method | System consent dialog; session-scoped |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Required by Android while a projection exists | Visible notification |
| `POST_NOTIFICATIONS` (Android 13+) | Active indicator, Pause/Stop, Attach action | Optional; without it use the in-app status and the floating button |
| `<queries>` for com.whatsapp, com.whatsapp.w4b | See whether WhatsApp is installed (Android 11+) | |
| `READ_MEDIA_IMAGES` (Android 13+), `READ_EXTERNAL_STORAGE` (Android 12 and below, `maxSdkVersion=32`) | Find the newest image in the Screenshots folder | Read-only, images only; asks MediaStore only for folders named like the Screenshots folder; only the chosen image is opened; copied to private temporary storage; never uploaded |
| `READ_MEDIA_VIDEO` (Android 13+) | Be sure the newest item is a photo (photo-only sending) | Only the DATE of the newest video is queried (and whether a video in the screenshots folder is as new as the screenshot); video content is never opened, read or copied |
| **Not requested** | INTERNET, network state, write/manage storage, audio media, location metadata, overlay (SYSTEM_ALERT_WINDOW), contacts, SMS, microphone, camera | `PolicyTest` enforces this |

## Data handling
- No network access, analytics, crash reporting, accounts or remote licence checks. No third-party SDKs.
- Screenshots: private cache directory only; deleted on Done/Delete/Stop, after the retention period, or on request. Shared with WhatsApp through a one-image temporary read grant.
- Conversation identity: a salted SHA-256 of the normalised title, in memory, per session. Never stored, logged or transmitted. Message text, phone numbers and the contact list are not read.
- No logging statements exist in the app sources (enforced by `PolicyTest`).
- Licence data: licence token (no personal data) and an installation public key, in private app storage; the private key never leaves the Android Keystore. Licensing code has no access to screenshots.
- Sent-status ledger: opaque keyed hashes of (chat, image content) with timestamps, in private preferences, capped at 500 entries; reset buttons clear it. No names, no message text.
- Backups are disabled. The app does not send messages and does not capture unless the user started a session. Stopping removes the notification and floating button and cancels pending captures.

## Diagnostics (added in 1.0.2)
The Home screen can copy an in-memory diagnostics text (recent steps, screen structure). It contains view-id names, view classes, yes/no flags and counts, never message text, names or numbers. It is not stored or sent; you copy and share it yourself.
During fully automatic sending the app reads the visible names in WhatsApp's share list and send screen to find and verify the chat; they are compared as hashes in memory and discarded.
