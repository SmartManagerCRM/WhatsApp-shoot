# User manual

## What it does
When you open a WhatsApp chat, Screenshot Assistant can capture your whole screen, show you the picture, and open WhatsApp's share screen with that picture
ready. **You** pick the chat and press Send. The app never sends anything.

## Main screen
- **Status card**: what the app is doing now ("Waiting for WhatsApp", "Conversation detected", "Screenshot captured", ...).
- **Start / Stop Automation**, **Pause / Resume**. A notification (with Pause and Stop) and a small *Capture* button inside WhatsApp show that it is active.
- **Capture Screenshot**: starts a 5-second countdown so you can switch to the screen you want; or use the floating *Capture* button inside WhatsApp (immediate).
- **Preview**: the full, uncropped image. **Attach to WhatsApp**, **Delete**, **Done** (finish and delete the file).
- Permission rows show accessibility, screen capture, notification and licence status.

## Capture modes (Settings)
- **Automatic**: captures about a second after a chat opens. **Delayed**: waits the delay you choose (0.5-30 s). **Manual**: only when you tap Capture.
- One automatic capture per chat visit; leave and re-enter the chat (or tap Capture) for another.

## Attaching
- Tap **Attach** in the *"Screenshot ready"* notification while the chat is still on screen: the app re-checks that it is the same chat, then opens WhatsApp's share list.
  Choose the chat and press Send.
- From inside the app, or when the app cannot be sure which chat it was (the title could not be read, you switched chats, WhatsApp is not in front),
  you get **Manual confirmation required**: tap **Open WhatsApp sharing**, choose the intended chat, check the name, press Send.
- If something fails, the screenshot is kept so you can try again or share it yourself.

## Permissions
- **Accessibility service**: required. **Screen capture** system dialog: only when the accessibility screenshot is unavailable (Android 10 or earlier, or you chose that method); on Android 14+ it is asked every time.
- If you decline the capture dialog the app will not keep asking by itself.
- Protected screens (banking, some video apps) cannot be captured; you will see a message.

## Privacy
Everything stays on the phone; no internet permission. See the **Privacy** tab and `docs/PERMISSIONS_AND_PRIVACY.md`.

## Licence and recovery
See the **License** tab. New phone, reinstall or cleared data => create a new activation request and contact the seller with your order number.
Keep your licence file and order number. Updating the app by installing over it keeps your licence.

## Troubleshooting
- *WhatsApp is open but "not recognised"*: WhatsApp changed its screens; use Capture and confirm the chat manually; report your WhatsApp version.
- *Nothing happens*: check the accessibility service is still on and battery optimisation is off for the app.
