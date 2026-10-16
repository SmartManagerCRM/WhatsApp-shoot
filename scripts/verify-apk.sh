#!/usr/bin/env bash
# Verifies that a file is a genuine, installable Android APK (not HTML, not a placeholder) and prints its identity.
# Usage: verify-apk.sh <apk> <build-tools-dir> [expected-signer-sha256-hex]
# Exit code 0 only if every hard check passes.
set -u
APK="$1"; BT="$2"; EXPECT="${3:-}"
fail=0
bad() { echo "FAIL: $*"; fail=1; }
ok()  { echo "ok:   $*"; }

echo "================ $APK"
[ -f "$APK" ] || { echo "FAIL: file does not exist"; exit 1; }
SIZE=$(stat -c %s "$APK"); echo "size: $SIZE bytes"
echo "sha256: $(sha256sum "$APK" | cut -d' ' -f1)"
echo "file(1): $(file -b "$APK")"

# 1. magic bytes must be a ZIP ('PK'), never text/HTML
MAGIC=$(head -c 2 "$APK" | od -An -c | tr -d ' ')
[ "$MAGIC" = "PK" ] && ok "starts with ZIP magic 'PK'" || bad "does not start with 'PK' (first bytes: $(head -c 40 "$APK" | tr -c '[:print:]' '.'))"
head -c 512 "$APK" | grep -qi "<html\|<!doctype" && bad "contains HTML markup"
# 2. plausible size (a Compose app is well over 500 KB)
[ "$SIZE" -ge 500000 ] && ok "plausible size" || bad "size below 500 KB"
# 3. archive integrity + required entries
unzip -tqq "$APK" >/dev/null 2>&1 && ok "zip integrity test passed" || bad "zip integrity test failed"
LIST=$(unzip -Z1 "$APK" 2>/dev/null)
for e in AndroidManifest.xml classes.dex resources.arsc; do echo "$LIST" | grep -qx "$e" && ok "contains $e" || bad "missing $e"; done
echo "dex files: $(echo "$LIST" | grep -c '^classes[0-9]*\.dex$')   native libs: $(echo "$LIST" | grep -c '^lib/') (ABIs: $(echo "$LIST" | grep '^lib/' | cut -d/ -f2 | sort -u | tr '\n' ' '))"

# 4. manifest facts decoded by aapt2
echo "--- manifest (aapt2 dump badging)"
BADGE=$("$BT/aapt2" dump badging "$APK" 2>&1)
echo "$BADGE" | grep -E "^package:|^sdkVersion|^minSdkVersion|^targetSdkVersion|^application-label:|^native-code|^launchable-activity" | sed 's/^/  /'
echo "--- permissions"; echo "$BADGE" | grep -E "^uses-permission" | sed 's/^/  /'
echo "$BADGE" | grep -q "name='com.smartmanager.whatsappshoot'" && ok "package name is com.smartmanager.whatsappshoot" || bad "unexpected package name"
echo "$BADGE" | grep -q "^minSdkVersion:'26'" && ok "minSdk 26" || bad "minSdk is not 26"
echo "$BADGE" | grep -q "^targetSdkVersion:'35'" && ok "targetSdk 35" || bad "targetSdk is not 35"
echo "$BADGE" | grep -q "android.permission.INTERNET" && bad "app requests INTERNET" || ok "no INTERNET permission"

# 5. signature
echo "--- signature (apksigner)"
SIG=$("$BT/apksigner" verify --verbose --print-certs "$APK" 2>&1); RC=$?
echo "$SIG" | grep -E "Verifies|Verified using|certificate DN|certificate SHA-256|WARNING|ERROR" | sed 's/^/  /'
[ $RC -eq 0 ] && ok "apksigner verification passed" || bad "apksigner verification FAILED"
CERT=$(echo "$SIG" | sed -n 's/.*certificate SHA-256 digest: *//p' | head -1 | tr 'A-F' 'a-f')
if [ -n "$EXPECT" ]; then
  [ "$CERT" = "$(echo "$EXPECT" | tr 'A-F' 'a-f')" ] && ok "signer matches the pinned release certificate" || bad "signer does NOT match the pinned release certificate"
else
  echo "  (no pinned certificate given: debug build, signed with the Android debug key)"
fi
[ $fail -eq 0 ] && echo "RESULT: VALID APK" || echo "RESULT: INVALID"
exit $fail
