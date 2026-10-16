# Permissions and privacy

| Permission / capability | Why | Notes |
|---|---|---|
| Accessibility service (`BIND_ACCESSIBILITY_SERVICE`, `canRetrieveWindowContent`, `canTakeScreenshot`, `flagReportViewIds`, `flagRetrieveInteractiveWindows`) | Detect WhatsApp / open conversation, read the conversation title for the hashed identity, take the screenshot, host the floating button | User must enable it in system settings. No package filter (needed to notice leaving WhatsApp); content of other apps is never read. |
| Screen capture (MediaProjection) | Alternative screenshot method | System consent dialog; session-scoped |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Required by Android while a projection exists | Visible notification |
| `POST_NOTIFICATIONS` (Android 13+) | Active indicator, Pause/Stop, Attach action | Optional; without it use the in-app status and the floating button |
| `<queries>` for com.whatsapp, com.whatsapp.w4b | See whether WhatsApp is installed (Android 11+) | |
| **Not requested** | INTERNET, network state, storage, overlay (SYSTEM_ALERT_WINDOW), contacts, SMS, microphone, camera, location | `PolicyTest` enforces this |

## Data handling
- No network access, analytics, crash reporting, accounts or remote licence checks. No third-party SDKs.
- Screenshots: private cache directory only; deleted on Done/Delete/Stop, after the retention period, or on request. Shared with WhatsApp through a one-image temporary read grant.
- Conversation identity: a salted SHA-256 of the normalised title, in memory, per session. Never stored, logged or transmitted. Message text, phone numbers and the contact list are not read.
- No logging statements exist in the app sources (enforced by `PolicyTest`).
- Licence data: licence token (no personal data) and an installation public key, in private app storage; the private key never leaves the Android Keystore. Licensing code has no access to screenshots.
- Backups are disabled. The app does not send messages and does not capture unless the user started a session. Stopping removes the notification and floating button and cancels pending captures.
