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

`entitlement=lifetime` has no end. Since app 1.1.0 a license may instead be `entitlement=term` with `expires=<epoch seconds>` (a subscription), and carries `rev=<n>` (revision, see docs/LICENSE_ADMIN.md). Both keys are optional and absent in licenses issued earlier, which stay valid unchanged (revision 0). The payload never contains passwords, payment data or personal data (use an opaque order reference).

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


## Version 1.1 additions (backward compatible)

Payload key order is now: `v, product, id, policy, entitlement, issued, expires, rev, ref, bind` (`expires` and `rev` optional).
- `entitlement=term` requires `expires`; `entitlement=lifetime` must not have it. After `expires` the app reports "expired" until a renewal is entered.
- `rev` counts renewals/amendments of the same license id (first issue = 1). A license with a lower revision than the installed one is refused.

### Control code (stops a license)
`WSC1.<base64url payload>.<base64url signature>`, signed with the same key under the domain prefix `WSS-CONTROL-v1\n`. Payload (fixed order, signed bytes = exact bytes):

    v=1
    product=<product id>
    action=revoke
    id=<license id>
    rev=<highest revision withdrawn>
    issued=<epoch seconds>

The app stores `id,rev` after a valid code is entered; every revision of that license up to `rev` is then refused. A later revision (a renewal/reinstatement) is not covered.
