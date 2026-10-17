#!/usr/bin/env bash
# Installs an APK on a RUNNING emulator/device (adb), launches it, and reports what happened.
# Usage: emulator-smoke.sh <apk> <label>
# Hard failures: install error, process not running, crash in logcat, accessibility service not bound.
set -u
APK="$1"; LABEL="$2"; PKG=com.smartmanager.whatsappshoot; SVC="$PKG/com.smartmanager.whatsappshoot.platform.WhatsAppAccessibilityService"
fail=0; bad() { echo "FAIL[$LABEL]: $*"; fail=1; }; ok() { echo "ok[$LABEL]:   $*"; }
echo "================ SMOKE TEST ($LABEL): $APK"
echo "device: $(adb shell getprop ro.product.model | tr -d '\r') | Android $(adb shell getprop ro.build.version.release | tr -d '\r') (API $(adb shell getprop ro.build.version.sdk | tr -d '\r')) | ABI $(adb shell getprop ro.product.cpu.abi | tr -d '\r')"

adb uninstall "$PKG" >/dev/null 2>&1
OUT=$(adb install -r "$APK" 2>&1); echo "install: $OUT"
echo "$OUT" | grep -q "Success" && ok "adb install succeeded" || { bad "adb install failed"; echo "RESULT[$LABEL]: FAILED"; exit 1; }
adb shell dumpsys package "$PKG" | grep -E "versionName=|versionCode=|minSdk=|targetSdk=|apkSigningVersion|signatures=" | head -6 | sed 's/^ */  /'

adb shell pm grant "$PKG" android.permission.READ_MEDIA_IMAGES 2>&1 | head -1
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>&1 | head -1
adb logcat -c
adb shell am start -W -n "$PKG/.ui.MainActivity" | grep -E "Status|Activity|TotalTime" | sed 's/^/  /'
sleep 8
PID=$(adb shell pidof "$PKG" | tr -d '\r'); [ -n "$PID" ] && ok "app process is running (pid $PID)" || bad "app process is not running"

ui_texts() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
  python3 - <<'PY'
import xml.etree.ElementTree as ET
try:
    r = ET.parse('/tmp/ui.xml').getroot()
    ts = [n.get('text') for n in r.iter('node') if n.get('text')]
    print('  UI text: ' + ' | '.join(ts[:25]))
except Exception as e:
    print('  (could not read UI: %s)' % e)
PY
}
tap_text() {
  python3 - "$1" <<'PY'
import sys, re, xml.etree.ElementTree as ET
t = sys.argv[1]
try: r = ET.parse('/tmp/ui.xml').getroot()
except Exception: sys.exit(1)
# Several nodes can carry the same text (e.g. a checklist row and a bottom-navigation tab): take the lowest one on screen.
best = None
for n in r.iter('node'):
    if n.get('text') == t:
        m = list(map(int, re.findall(r'\d+', n.get('bounds'))))
        if best is None or m[1] > best[1]: best = m
if best is None: sys.exit(1)
print((best[0]+best[2])//2, (best[1]+best[3])//2)
PY
}
echo "--- Home screen"; ui_texts | tee /tmp/home.txt
grep -q "Screenshot Assistant" /tmp/home.txt && ok "Home screen rendered (app title visible)" || bad "Home screen text not found"
if XY=$(tap_text "License"); then adb shell input tap $XY; sleep 3; echo "--- License screen"; ui_texts | tee /tmp/lic.txt
  grep -q "Create activation request\|Activated" /tmp/lic.txt && ok "License screen rendered" || bad "License screen not rendered"
  grep -q "not provisioned" /tmp/lic.txt && bad "build has no license key" || ok "license public key is present in this build"
else bad "License tab not found"; fi

echo "--- accessibility service"
adb shell settings put secure enabled_accessibility_services "$SVC"; adb shell settings put secure accessibility_enabled 1; sleep 6
adb shell dumpsys accessibility | grep -i -E "WhatsAppAccessibilityService" | head -3 | sed 's/^ */  /'
adb shell dumpsys accessibility | grep -q "WhatsAppAccessibilityService" && ok "accessibility service is declared and bound" || bad "accessibility service not found/bound"

echo "--- crash scan"
CRASH=$(adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime: .*$PKG|Process: $PKG" | head -5)
[ -z "$CRASH" ] && ok "no crash in logcat" || { bad "crash detected:"; echo "$CRASH" | sed 's/^/  /'; adb logcat -d | grep -A12 "FATAL EXCEPTION" | head -30 | sed 's/^/  /'; }
adb shell pidof "$PKG" >/dev/null 2>&1 && ok "app still running after all steps" || bad "app died during the test"
[ $fail -eq 0 ] && echo "RESULT[$LABEL]: PASSED" || echo "RESULT[$LABEL]: FAILED"
exit $fail
