# Architecture

```
app/                       Android application (Kotlin, Compose, Material 3)
  logic/                   Pure Kotlin, no Android types: fully unit-tested on the JVM
    AutomationStateMachine   explicit states + transition table
    ScreenModel              ScreenSnapshot, ScreenClassifier, WhatsAppProfile (configuration, not code)
    Identity                 salted-hash ConversationIdentity, DestinationVerifier, ShareTargetSelector
    CaptureGuard             duplicate / recursive capture prevention
    ScreenshotRepository     temp-file storage + cleanup, touches only its own files
    AutomationController     orchestrates detection -> capture -> verification -> attachment
    Ports                    interfaces implemented by `platform` (CaptureEngine, ScreenObserver, AttachmentLauncher, Scheduler)
  platform/                Android glue
    WhatsAppAccessibilityService   events -> ScreenSnapshot, accessibility screenshot, overlay, experimental share-row selection
    ProjectionCapture              MediaProjection single-frame capture, foreground service, consent activity
    ShareLauncher                  ACTION_SEND to WhatsApp via FileProvider
    Notifier                       status/attach notifications, action receiver, trampoline activity
    KeystoreInstallKeys            Android Keystore installation identity + redundant activation store
    LicenseGate                    bridge from licensing to the rest of the app (a boolean + a status flow)
    AppGraph                       manual dependency wiring
  ui/                      Compose screens: Home, Settings, License, Privacy
license-core/              Pure JVM: license format, verifier, activation engine (shared by app and tool)
license-tool/              Pure JVM CLI for the owner: keygen / issue / verify. Never shipped in the APK
```

## Separation of concerns
- **Licensing is independent of automation.** `license-core` has no dependency on screenshot code (checked by `PolicyTest`). The controller is
  given a `() -> Boolean`; the license subsystem never receives screenshots, conversation data or file paths, so a licensing error cannot
  transmit screenshot data (and the app has no network permission at all).
- **The signing code is not in the app.** The app depends on `license-core` (verification) only; `initSign` appears only in `license-tool`.
- **Platform code is thin.** All decisions (when to capture, what counts as a duplicate, when attachment is allowed) live in `logic/` where
  they are tested with fakes.

## State machine
States: `INACTIVE, WAITING_FOR_WHATSAPP, WHATSAPP_DETECTED, CONVERSATION_DETECTED, CAPTURE_PERMISSION_REQUIRED, CAPTURING_SCREEN,
SCREENSHOT_READY, VERIFYING_CONVERSATION, PREPARING_ATTACHMENT, ATTACHMENT_READY, MANUAL_ACTION_REQUIRED, ERROR`.
Pause is an orthogonal flag (the requested state list has no PAUSED state).

| From | Event | To |
|---|---|---|
| any except INACTIVE | STOP | INACTIVE |
| INACTIVE | START | WAITING_FOR_WHATSAPP |
| WAITING_FOR_WHATSAPP | WHATSAPP_FOREGROUND | WHATSAPP_DETECTED |
| WAITING_FOR_WHATSAPP | CONVERSATION_OPENED | CONVERSATION_DETECTED |
| WAITING_FOR_WHATSAPP / WHATSAPP_DETECTED | CAPTURE_STARTED (explicit user capture only) | CAPTURING_SCREEN |
| WHATSAPP_DETECTED | CONVERSATION_OPENED / WHATSAPP_BACKGROUND | CONVERSATION_DETECTED / WAITING_FOR_WHATSAPP |
| CONVERSATION_DETECTED | CONVERSATION_CLOSED / WHATSAPP_BACKGROUND | WHATSAPP_DETECTED / WAITING_FOR_WHATSAPP |
| CONVERSATION_DETECTED | CAPTURE_PERMISSION_MISSING / CAPTURE_STARTED | CAPTURE_PERMISSION_REQUIRED / CAPTURING_SCREEN |
| CAPTURE_PERMISSION_REQUIRED | CAPTURE_PERMISSION_GRANTED / CAPTURE_STARTED | CONVERSATION_DETECTED / CAPTURING_SCREEN |
| CAPTURE_PERMISSION_REQUIRED | CAPTURE_PERMISSION_DENIED | MANUAL_ACTION_REQUIRED |
| CAPTURE_PERMISSION_REQUIRED | CONVERSATION_CLOSED / WHATSAPP_BACKGROUND / DISMISS | WHATSAPP_DETECTED / WAITING_FOR_WHATSAPP / WAITING_FOR_WHATSAPP |
| CAPTURING_SCREEN | CAPTURE_SUCCEEDED / CAPTURE_FAILED / CAPTURE_PERMISSION_MISSING | SCREENSHOT_READY / ERROR / CAPTURE_PERMISSION_REQUIRED |
| SCREENSHOT_READY, ATTACHMENT_READY | VERIFY_REQUESTED | VERIFYING_CONVERSATION |
| SCREENSHOT_READY, ATTACHMENT_READY, MANUAL_ACTION_REQUIRED | CAPTURE_STARTED (deliberate re-capture) | CAPTURING_SCREEN |
| SCREENSHOT_READY, ATTACHMENT_READY, MANUAL_ACTION_REQUIRED, ERROR | DISMISS | WAITING_FOR_WHATSAPP |
| VERIFYING_CONVERSATION | VERIFIED | PREPARING_ATTACHMENT |
| VERIFYING_CONVERSATION | VERIFY_UNCERTAIN / VERIFY_CHANGED | MANUAL_ACTION_REQUIRED |
| MANUAL_ACTION_REQUIRED | USER_CONFIRMED_DESTINATION / VERIFY_REQUESTED | PREPARING_ATTACHMENT / VERIFYING_CONVERSATION |
| PREPARING_ATTACHMENT | ATTACHMENT_PREPARED / ATTACHMENT_FAILED | ATTACHMENT_READY / MANUAL_ACTION_REQUIRED |

`StateMachineTest` enumerates the table and asserts invariants: the only ways into `PREPARING_ATTACHMENT` are `VERIFIED` or an explicit user
confirmation; `ATTACHMENT_READY` is reachable only from `PREPARING_ATTACHMENT`; observation events are rejected once a capture is in progress
(this is what stops a preview screen or the user's trip to our own app from causing capture loops).

## Duplicate prevention (layers)
1. Accessibility events are coalesced (200 ms debounce) and the service's `notificationTimeout` throttles them.
2. Our own package and `com.android.systemui` are `IGNORED`: they never change state.
3. The controller schedules at most one pending automatic capture, and does not reschedule on repeated events; a new timer starts only when the conversation changes.
4. `CaptureGuard`: one capture in flight, one automatic capture per conversation *visit* (reset when the user leaves/switches), cooldown, double-tap debounce. Failures also consume the visit, so a failing capture cannot loop.
5. The state machine ignores observation events during the workflow. Attachment picker / media viewer cancel any pending capture.

## Capture engines
- **Accessibility screenshot** (`AccessibilityService.takeScreenshot`, Android 11+): no consent dialog per capture, so automatic mode works. Full display, original resolution; secure windows are refused by the system.
- **MediaProjection** (all supported versions): one VirtualDisplay is created per capture, receives one frame and is released immediately - no recording. Android 14+ tokens are single-use, so the app re-asks each time (never silently, never in a loop; denial is remembered until the user asks again).
- The floating button is hidden before every capture so it is never part of the image.

## Conversation identity and destination safety
`ScreenSnapshot` carries view ids and the header text. The controller hashes the normalised header with a random per-session salt
(`IdentityHasher`) and keeps only the hash, in memory. Nothing is persisted or logged. Before sharing, `DestinationVerifier` compares
the screenshot's hash with WhatsApp's *current* screen (read through the accessibility window list, so it works even when our
transparent trampoline activity is on top): `VERIFIED`, `CHANGED` (different chat / left the chat) or `UNCERTAIN` (not observable, header unreadable).
Only `VERIFIED` may proceed automatically, and even then the user picks and confirms the recipient in WhatsApp and presses Send.

## Storage
Screenshots: `<cache>/screenshots/wss_<uuid>.<ext>` (atomic `.part` -> final rename). `ScreenshotRepository` refuses to delete or commit anything outside that directory
or without the `wss_` prefix. `FileProvider` exposes only that directory. Backups are disabled so neither screenshots nor activation data leave the device.

## Latest-screenshot workflow (default image source)
```
conversation detected --(settle delay)--> startLatest -> ScreenshotSelector (MediaStore, Screenshots folder only, newest eligible, hash of bytes)
   -> SendPolicy.decide(behavior A/B/C, chat identity, image key, SentLedger)
        Skip      -> CAPTURE_SKIPPED (status message, no loop: one attempt per conversation visit)
        NeedManual-> stage copy in private cache, SCREENSHOT_READY, user confirms
        Proceed   -> stage copy -> requestAttach(): DestinationVerifier re-checks the open chat -> WhatsApp share screen
                      -> (optional, opt-in) ShareFlowEngine: pick the single matching row -> verify the recipient WhatsApp shows -> Send -> evidence check
```
- **Image identity** = SHA-256 of the file bytes (identical copies count as the same image). **Chat identity** = keyed hash of the normalised title (per-install secret); titles under 2 characters or unreadable are "unreliable" and force manual confirmation in modes B and C.
- **SentLedger** (`PersistentLedger`): chat key x image key x date x status (PREPARED / SENT_UNCONFIRMED / SENT_CONFIRMED), capped at 500, cleared by resets.
- **Mode C** also listens for new images (`NewScreenshotWatcher`, MediaStore observer, 1.5 s debounce) while a chat is open.
- **Send button**: reachable only through `ShareFlowEngine` -> `ClickSend`, which exists only when `fullyAutomaticSend` is on, the destination was verified, and the recipient on WhatsApp's own screen matched. Stop clears the engine at once.
- The workflow ends automatically after a hand-off when the user leaves the chat (chat list, other chat, other app), which deletes the temporary copy and resets the state for the next chat.
