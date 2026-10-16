# Key management

## Signing key (licence issuing)
Generate on an **offline** machine you control:
```
java -jar dist/license-tool.jar keygen --out-dir secrets
```
Creates `secrets/license-private.enc` (PKCS#8 key encrypted with AES-256-GCM, key derived with PBKDF2-HMAC-SHA256, 600 000 iterations, mode 0600) and
`secrets/license-public.b64`. The passphrase must be >= 12 characters, typed at the prompt or supplied through `WSS_KEY_PASSPHRASE` (prefer the prompt on a shared machine).
The tool never prints key material and refuses to overwrite existing key files.

Copy `license-public.b64` to `license-public-key.txt` in the repository root: that is the only key the Android build embeds
(`BuildConfig.LICENSE_PUBLIC_KEY`). The release build refuses to run without it.

## Backup procedure
1. Make **two** copies of `license-private.enc` on separate offline media (e.g. two encrypted USB drives) stored in different places.
2. Store the passphrase separately from the key file (password manager + a sealed paper copy in a safe). Never put both in the same place or in the repository.
3. Test the backup: run `license-tool issue --unbound --key <backup copy> --out /tmp/test.lic` on another machine, then `verify`.
4. Repeat the test yearly. Record the public-key fingerprint printed by `keygen` in your records.
`.gitignore` excludes `secrets/`, `*.enc`, `*.key`, `*.jks`, `keystore.properties`; `PolicyTest` fails the build if private-key markers are committed.

## If the signing key or passphrase is lost
Existing licences keep working (they only need the public key). You cannot issue new ones for apps embedding that public key. Recovery: generate a new key
pair, ship an app update embedding the new public key, and re-issue licences to customers who need them. To support a key change without breaking existing customers you would need to embed
**both** public keys before the old key is lost - consider doing that proactively (not implemented in v1).

## If the signing key is compromised
Anyone with the key and passphrase can mint licences. There is no revocation list offline. Rotate: new key pair, app update with the new public key only, re-issue
licences to paying customers (ledger), accept that old licences stop working after the update.

## App signing key (APK signature)
Separate from the licence key. Keep the Android upload/release keystore (`.jks`) and its passwords outside the repository (`keystore.properties`, git-ignored, or `WSS_STORE_*` env vars),
with the same two-copy backup. Losing it means customers cannot receive updates over existing installs.
