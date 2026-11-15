# User manual

## What it does (latest-screenshot mode, the default)
When you open a WhatsApp chat, the app takes **your most recent existing screenshot** from the Screenshots folder (it does not take a new one), and opens WhatsApp's share screen
with it for that chat. By default **you press Send**. Allow photo access when asked.

**Settings > Automatic sending behavior**: *Every chat open* (each time you open a chat), *Once per chat* (until you reset that chat's status on the main screen), *New screenshot only*
(only a screenshot not yet handled for that chat). If the app cannot reliably tell which chat is open, it asks you to confirm. **Stop** is always available (notification and the red button in WhatsApp).
**Fully automatic sending** (off by default; Settings shows a warning) lets the app press Send itself, only after the chat and WhatsApp's recipient screen match; it says "Sent" only when it can see a new outgoing image,
otherwise "could not be confirmed". Taking a *new* screenshot instead is available under Settings > Image source.

## What it does (original mode: capture a new screenshot)
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
See the **License** tab. Paste the license code you received (or import the file) and tap Activate - no request code is needed. New phone, reinstall or cleared data => enter the license code again.
Keep your licence file and order number. Updating the app by installing over it keeps your licence.

## Language
Settings > Language: System default, English, العربية (Arabic, right-to-left) or Français. The whole app, its notifications and the floating button switch immediately.

## Troubleshooting
- *WhatsApp is open but "not recognised"*: WhatsApp changed its screens; use Capture and confirm the chat manually; report your WhatsApp version.
- *Nothing happens*: check the accessibility service is still on and battery optimisation is off for the app.

## Text with the photo (v1.3.0)
On the Home screen, the card **Text with the photo** holds a message you type once. The app puts it into WhatsApp's caption box and sends it together with every photo.
Leave the box empty to send the photo only. The text is stored only on the phone (settings), never sent anywhere except inside WhatsApp with the photo.

## Catalogue (v1.6.0)
The **Catalogue** tab holds your products: a picture, a name, a price and a description.
1. Tap **Add product**, choose the product picture, type the **name exactly as it is written on the photos you will scan** (for example the model name), the price and a description.
2. Keep **Recognise products** on and **fully automatic sending** on (Settings).
3. When a chat is opened the app reads the newest photo on the phone (on the phone, offline). If its text contains a product's name, the app sends **that product's catalogue picture** instead of the newest photo.
4. The message sent with it is made of: the product **name, price and description**, then the **details read on the photo** (order number, date, branch and any **notes** - each only if found), then **your text** from the Home screen.
5. If no product is recognised the newest photo is sent as before, with the details read on it (if any) and your text.
6. **Try it** (Catalogue tab) lets you choose a photo and see what the phone reads, which product it would pick and which details it finds, without sending anything.

Details are found by their label, written in Latin letters (English or French): *Order No / Order # / Order ID / Commande n° / Invoice / Facture*, a date (`12/11/2026`, `2026-11-12`, `12 Nov 2026`, `12 novembre 2026`),
*Branch / Store / Shop / Agence / Succursale / Magasin / Boutique*, and notes written after *Notes / Note / Remark / Remarque / Observation / Comment / Commentaire / Instructions / Memo* (on the same line, or on the lines below a label that stands alone; at most 200 characters). Nothing is guessed: a detail missing from the photo is simply left out. The labels in the message follow the app language.
Switch "Add details read on the photo" off on the Catalogue tab to send only the product information.

How a name is matched: case, accents and punctuation do not matter; all words of the product name must be found (a name of 4+ words may miss one); a word of 5+ letters tolerates one misread letter.
Two products with an equal match are treated as "no match" (nothing is guessed). The text reader handles Latin letters and digits; it does **not** read Arabic script (labels or names written in Arabic are not found).
