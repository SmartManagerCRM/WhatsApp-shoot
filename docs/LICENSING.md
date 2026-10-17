# Licensing: model, security properties, limits and recovery

## Commercial model implemented
**One lifetime license per customer, bound automatically to the first installation that activates it, with no request code.**
No activation server, no hosting, no database, no mandatory internet. Licenses are signed offline by the owner with `license-tool`; the app verifies them with an embedded public key.
(Optional extras for the owner, off by default: subscription end dates, renewal and stop codes - see `docs/LICENSE_ADMIN.md`.)

## Automatic offline binding (the default flow)
1. **Owner:** `license-tool` (or the License Manager) issues one unique, signed license per sale (a `first-use`, lifetime license; no request code).
2. **Customer:** installs the APK and receives the license code or file.
3. **First launch:** the app creates an installation-specific EC P-256 key pair inside the Android Keystore (private key non-exportable). No code is shown or asked.
4. **Customer** pastes the license or imports the file.
5. **App** verifies the signature with the embedded public key, then writes a **signed activation record** (license + this installation's public key + time, signed by the Keystore key) into two redundant slots.
6. **Every start:** the license signature, the activation record and a fresh proof-of-possession of the Keystore key are checked again, offline.
7. If activation data is missing or damaged the app says so and shows the recovery step (enter the license code again).

## The limitation (please read)
Because activation is entirely offline there is no central registry of used licenses. **A copied license code can be activated independently on another phone** before either installation contacts the owner.
Binding therefore guarantees only: *a given installation's activation cannot be copied to another phone* (the activation record needs that phone's Keystore key), not *one license = one phone*.
Strict one-device enforcement is **not** guaranteed and cannot be offline. Mitigations the owner controls: unique license per sale (ledger/register shows who got which license id), subscriptions with end dates, stop codes, and
`device-bound` licenses (below) for sales where strict binding matters.

## Two licence policies
| Policy | How issued | What it prevents | What it does not prevent |
|---|---|---|---|
| **`first-use`** (default, automatic binding) | `license-tool issue --unbound` / License Manager "Quick license" - no request code | Copying the *activation* (APK + app data) to another phone: the record needs the original Keystore key | The same license *code* being activated on several phones |
| **`device-bound`** (optional) | Customer creates an activation request in the app (License > phone-locked); owner runs `issue --request <code>` | The license is tied to that installation's key from the start: it will not activate on any other phone or fresh install | Root/Xposed/patched APK; a customer who hands over their whole unlocked phone |

## Honest limits
- A fully offline licence **cannot guarantee** that a licence is not used on several devices, and cannot be made impossible to bypass. An attacker who
  modifies the APK (replacing the embedded public key, or patching `isActive`), runs it on a rooted/instrumented device, or hooks the Keystore can defeat it.
- Implemented deterrents: asymmetric signatures (no secret in the APK); no universal key; Keystore-bound, non-exportable installation key;
  device-bound licences; signed + redundant activation record with proof-of-possession on every check; fail-closed behaviour; R8 shrinking/obfuscation in release;
  optional pinning of the release signing certificate (`signerSha256`) to detect repackaged APKs; no hidden boolean (status is recomputed from cryptographic evidence).
- Deliberately **not** done: root detection (would block legitimate customers), phoning home, hardware identifiers (spoofable and privacy-invasive).

## What happens in real-life situations
| Situation | Result | What the customer does |
|---|---|---|
| App updated normally (same signing key, same `applicationId`) | Keystore key and data persist -> still activated | nothing |
| Phone rebooted / app restarted | still activated | nothing |
| Phone replaced / lost | new phone has no key -> not activated (a `device-bound` licence is rejected as "different installation") | Recovery below |
| Uninstall + reinstall, "Clear data", factory reset | Keystore key and record are deleted by Android -> not activated | Recovery below |
| APK + activation data copied to another phone | rejected (that phone's Keystore has no matching key) | - |
| License *code* copied to another phone | activates there too (`first-use`) / rejected (`device-bound`) | - |
| Activation data corrupted in one slot | backup slot takes over | nothing |
| Activation data corrupted in both slots | "stored activation is damaged" | re-import the licence (a `device-bound` licence still matches the same Keystore key) |

## Recovery policy (fair to paying customers)
- **`first-use` licenses (default):** the customer simply enters the same license code again on the new/reset phone; it binds to the new installation. No request code. The owner can check the register for repeated recoveries.
  Recommended seller policy: free for genuine phone replacement / reinstall; use judgement for repeated requests. The owner cannot revoke the old phone offline (see `docs/LICENSE_ADMIN.md` for stop codes).
- **`device-bound` licenses:** the customer creates an activation request on the new phone and sends it with the order reference; the owner issues a replacement for that request.
- Customers should keep their license code/file and order reference safe; neither the app nor the seller can recover a license from the phone after data loss.

## Updates and data migration
Normal signed updates preserve the activation as long as `applicationId` and the signing certificate stay the same. Never change `applicationId`
(it would create a new app with a new, empty Keystore) and never change the signing key (Android would refuse the update). Activation record version `rv=1`
and licence version `v=1` are explicit; a future format change must keep reading version 1.
