# Licensing: model, security properties, limits and recovery

## Commercial model implemented
One-time purchase, lifetime entitlement, no subscription, no account, no server, no network. Licences are files/strings signed
offline by the owner with `license-tool`; the app verifies them with an embedded public key.

## Two licence policies
| Policy | How issued | What it prevents | What it does not prevent |
|---|---|---|---|
| **`device-bound`** (recommended) | Customer shows an *activation request code* from the app; owner runs `license-tool issue --request <code>` | The licence is cryptographically tied to that installation's Android-Keystore key. The same licence file will not activate on any other phone or fresh install. | Root/Xposed/patched APK; a customer who hands over their whole unlocked phone |
| **`first-use`** | `license-tool issue --unbound` (no request needed) | Casual sharing of the *APK + activation data* (the record needs the original Keystore key) | The same licence *string* can be activated on any number of phones - a pure offline system cannot know it was used elsewhere |

Requirement 9.5 describes "import licence, then create the installation key". That flow is exactly `first-use`. Because it cannot enforce
"one installation per licence" offline, the product also offers `device-bound`, which adds one manual step (send the request code) to get genuine binding.
Choose per sale; the tool warns when you issue `first-use`.

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
| Phone replaced / lost | new phone has no key -> not activated; the old `device-bound` licence is rejected as "different installation" | Recovery below |
| Uninstall + reinstall, "Clear data", factory reset | Keystore key and record are deleted by Android -> not activated | Recovery below |
| APK + licence copied to another phone | rejected (`device-bound`) / activates (`first-use`, by design of that policy) | - |
| Activation data corrupted in one slot | backup slot takes over | nothing |
| Activation data corrupted in both slots | "stored activation is damaged" | re-import the licence (a `device-bound` licence still matches the same Keystore key) |

## Recovery policy (fair to paying customers)
There is no server, so recovery is manual and requires trust in the order record:
1. Customer installs the app on the new/reset phone, opens **License > Create activation request**, and sends the code plus their order reference.
2. Owner finds the order in the issue ledger (`--ledger issued-licenses.csv`: licence id, date, policy, order reference, installation fingerprint).
3. Owner issues a **replacement** `device-bound` licence for the new request code (`license-tool issue --request ... --ref <same order>`) and records it in the ledger.
4. Recommended seller policy: replacement is free for genuine hardware loss/replacement and a reasonable number of reinstalls; apply judgement to repeated requests.
   The tool cannot revoke the old licence (there is no channel to the old phone); the old device-bound licence is useless on any phone but the old one.
5. Customers should keep their licence file and order reference in a safe place; the app and the seller cannot recover a licence from the phone after data loss.

## Updates and data migration
Normal signed updates preserve the activation as long as `applicationId` and the signing certificate stay the same. Never change `applicationId`
(it would create a new app with a new, empty Keystore) and never change the signing key (Android would refuse the update). Activation record version `rv=1`
and licence version `v=1` are explicit; a future format change must keep reading version 1.
