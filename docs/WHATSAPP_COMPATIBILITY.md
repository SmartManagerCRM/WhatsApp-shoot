# WhatsApp and Android compatibility

## Tested versions
| | Tested |
|---|---|
| WhatsApp (com.whatsapp) | **none** |
| WhatsApp Business (com.whatsapp.w4b) | **none** |
| Android | **none** (compile-checked against API 34 only) |

Everything WhatsApp-specific in this repository was verified only against synthetic screen snapshots in JVM tests.

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
