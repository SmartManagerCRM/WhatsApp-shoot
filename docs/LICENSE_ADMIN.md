# Managing issued licenses (owner guide)

You, the seller, decide what every customer's license does: **renew**, **amend**, **suspend**, **cancel** or **reinstate** it. Everything runs offline in the License Manager
(`java -jar dist/license-tool.jar`, tab **3. Customers & licenses**) or on the command line (`java -jar dist/license-tool.jar help`).

## The one thing to understand: how control works without internet
The app has no internet permission (a deliberate privacy promise), so it can never "phone home" to be switched off. Control therefore works in two ways:

1. **Subscription licenses end by themselves.** Issue a license with an end date (`--days 30` or `--expires 2027-01-31`; in the GUI: *Subscription: valid for N days*).
   When the date passes, the app stops automating and shows "License expired". The customer continues only after you send a **renewal code**.
   **This is how you stop a customer who does not pay: do not send the renewal.** It is enforced by the app without any action from the customer.
2. **Stop codes (suspend / cancel).** Produces a signed code; the license stops on a phone **only after that code is entered in the app**.
   Useful when the customer cooperates (refund, plan change, lost phone) or enters it when contacting you. A customer who never opens the License screen or never
   connects cannot be switched off remotely with a stop code, so use subscriptions for customers who may not pay.

If you later want instant remote switch-off, the app would need an optional internet check against a server you run. That changes the privacy promise ("no internet at all")
and needs a server and a privacy-text update; it is not part of this version.

Clock tricks: the app remembers the latest date it has seen, so turning the phone clock back does not revive an expired subscription.

## Daily work (License Manager, tab 3)
The register lists every license with its status: ACTIVE, EXPIRING (< 7 days), EXPIRED, SUSPENDED, CANCELLED. Select a row, type your signing passphrase, then:

| Button | What it does | Customer does |
|---|---|---|
| **Renew...** | New revision of the same license with a later end date (adds days from the current end, or from today if it already ended) | Pastes the code in the app's License screen |
| **Amend...** | Changes end date (or lifetime), order reference, private note | Pastes the code |
| **Suspend** | Stop code; reversible | Pastes the code (app stops) |
| **Cancel** | Stop code, recorded as cancelled | Pastes the code (app stops) |
| **Reinstate...** | New revision after a suspend/cancel (optionally a new end date) | Pastes the code (app works again) |
| **History** | Everything done to that license, with dates | - |
| **Export CSV...** | Whole register as a spreadsheet | - |
| **Import old ledger...** | Adds licenses issued before the register existed (`issued-licenses.csv`) | - |

Renew/Amend/Reinstate codes are bound to the same phone as the original (the register keeps the phone's fingerprint), so you never need a new request code.
Codes are saved under `secrets/codes/` and shown on screen with a **Copy code** button; send them by WhatsApp or email.

The register is the file `secrets/licenses-registry.tsv` (plus `.history.log`). It holds your private notes (customer name, phone, invoice) which never enter a license.
**Back it up together with the signing key.** The app never sees it.

## Issuing (tab 2)
Choose *Lifetime* or *Subscription: valid for N days*, fill the order reference and a private customer note, keep the register path, press **Issue license**.

## Command line cheat sheet
    java -jar dist/license-tool.jar issue --key secrets/license-private.enc --request WSR1... --out lic.txt --days 30 --ref order-12 --registry secrets/licenses-registry.tsv --note "Acme, Maria"
    java -jar dist/license-tool.jar list --registry secrets/licenses-registry.tsv
    java -jar dist/license-tool.jar renew --registry R --key K --id 3333-4444 --out code.txt --days 30
    java -jar dist/license-tool.jar suspend --registry R --key K --id 3333-4444 --out stop.txt
    java -jar dist/license-tool.jar reinstate --registry R --key K --id 3333-4444 --out back.txt
`--id` accepts any unique fragment of the license id.

## Existing customers
Licenses issued before this version are lifetime, revision 0, and keep working. Use *Import old ledger* to put them in the register; you can then suspend/cancel or
convert them to a subscription with **Amend** (a new code for the customer to enter). The app update must be installed first for the phone to understand renewal and stop codes.
