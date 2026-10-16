# License-generation utility (owner only)

Standalone, offline, JDK 17+ only. Build: `scripts/build-license-tool.sh <kotlinc-dir>` -> `dist/license-tool.jar`
(or `./gradlew :license-tool:installDist` once Gradle can run). Never ship it to customers.

```
java -jar dist/license-tool.jar keygen          --out-dir secrets
java -jar dist/license-tool.jar issue           --key secrets/license-private.enc (--request WSR1... | --unbound) --out customer.lic [--ref ORDER-1234] [--ledger issued-licenses.csv]
java -jar dist/license-tool.jar verify          --pub secrets/license-public.b64 --license customer.lic [--request WSR1...]
java -jar dist/license-tool.jar inspect-request --request WSR1...
```
Exit codes: 0 ok, 1 usage/input error, 2 key problem (missing file, wrong passphrase, damaged file, refuse to overwrite), 3 licence invalid.

## Selling a licence (recommended device-bound flow)
1. Customer installs the app, opens **License > Create activation request**, and sends you the `WSR1...` code (copy/share button) with their order reference.
2. `inspect-request` to sanity-check it, then
   `issue --key secrets/license-private.enc --request "WSR1..." --ref ORDER-1234 --out ORDER-1234.lic --ledger issued-licenses.csv`
   (enter the passphrase at the prompt).
3. Send `ORDER-1234.lic` (the file, or the single `WSS1...` line). Customer uses **Import License File** or pastes it and taps **Activate License**.
4. Optionally `verify --pub ... --license ORDER-1234.lic --request "WSR1..."` before sending.

`--ref` must not contain personal data. `issued-licenses.csv` (licence id, UTC time, policy, reference, installation fingerprint) is your recovery
ledger; it is git-ignored, keep it backed up with the signing key (it contains no secrets but is business data).
Each licence gets a fresh 128-bit random id. `first-use` licences (`--unbound`) print a warning because they can be activated on several phones.
