# License format, version 1 (normative)

## Token
```
WSS1.<base64url(payload)>.<base64url(signature)>
```
Base64url without padding, canonical (re-encoding must reproduce the text). Whitespace anywhere in a pasted token is ignored.
Payload <= 1024 bytes, signature <= 80 bytes.

## Signature
ECDSA over NIST P-256 with SHA-256 (`SHA256withECDSA`, DER encoded), implemented by the platform provider (JDK / Android).
Signed bytes = `"WSS-LICENSE-v1\n"` (domain separator) followed by the exact payload bytes. The app contains only the X.509 public key.

## Payload (UTF-8, LF, fixed key order, every line required unless marked)
```
v=1
product=com.smartmanager.whatsappshoot.lifetime
id=WSS-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX     (128 random bits, uppercase hex groups)
policy=device-bound | first-use
entitlement=lifetime
issued=<unix seconds>
ref=<customer reference>                            (optional; [A-Za-z0-9._:-]{1,64}; no personal data)
bind=<base64url SHA-256 of installation public key>  (present iff policy=device-bound)
```
Values match `[A-Za-z0-9._:\-]{1,64}`. Unknown, duplicate or re-ordered keys, a missing trailing newline or a `bind` on a `first-use` licence are rejected.

## Validation order (all must hold)
1. Token structure and size. 2. Signature (nothing in the payload is trusted before this). 3. Strict payload parse. 4. `v == 1`.
5. `product` equals this app's product id. 6. `entitlement == lifetime`. 7. Policy: `device-bound` requires `bind` == fingerprint of *this* installation's Keystore public key; `first-use` requires no `bind`.

There is no expiry field: lifetime means lifetime. The payload never contains passwords, payment data or personal data (use an opaque order reference).

## Activation request code
`WSR1.<base64url(X.509 EC P-256 public key of the installation)>.<6-char checksum>`. Contains only a public key; the checksum catches typos.

## Local activation record (on the phone)
```
rv=1
token=<license token>
installpub=<base64url X.509 installation public key>
at=<unix seconds>
sig=<base64url ECDSA by the Keystore key over "WSS-ACTIVATION-v1\n" + id + "\n" + installpub + "\n" + at + "\n" + applicationId>
```
Stored twice (two SharedPreferences slots). On every check the app (1) re-verifies the licence, (2) verifies the record signature,
(3) checks the Keystore still holds exactly `installpub`, and (4) signs a fresh random challenge with the Keystore key and verifies it - a copied record fails step 3/4.
