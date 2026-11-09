#!/bin/sh
# On-device smoke test for ATB. Run only when you are ready to force-stop and
# relaunch PhoneManager. Does not modify rules.
#
#   ./tools/selftest.sh            # passive: verify hooks installed
#   ./tools/selftest.sh --active   # also open the anti-fraud page and look for hits
set -u
ADB=${ADB:-adb}
PKG=com.coloros.phonemanager
FAKE=com.coloros.phonemanager/com.oplus.phonemanager.FakeActivity
FRAUD_ACTION=com.oplus.phonemanager.action.ANTI_FRAUD_HOME
FRAUD_ACT=com.coloros.phonemanager/com.oplus.phonemanager.aivoicecalldetect.antifraudhome.SecurityHomeActivity
FAILED=0

say() { printf '\n== %s ==\n' "$1"; }
ok()  { printf '  OK   %s\n' "$1"; }
bad() { printf '  FAIL %s\n' "$1"; FAILED=1; }
info(){ printf '       %s\n' "$1"; }

say "0. module installed"
if "$ADB" shell pm path com.eta.atb 2>/dev/null | grep -q package:; then
  ok "com.eta.atb installed"
else
  bad "com.eta.atb not installed"
fi

if "$ADB" shell test -f /data/local/tmp/atb.disable 2>/dev/null; then
  info "WARNING: /data/local/tmp/atb.disable exists; hooks are bypassed"
fi

say "1. cold start and capture ATB logs"
"$ADB" logcat -c 2>/dev/null
"$ADB" shell am force-stop "$PKG" 2>/dev/null
sleep 1
"$ADB" shell am start -n "$FAKE" >/dev/null 2>&1
LOG=""
i=0
while [ $i -lt 15 ]; do
  sleep 1
  LOG=$("$ADB" logcat -d -s ATB:'*' 2>/dev/null)
  echo "$LOG" | grep -q 'summary pkg=' && break
  i=$((i+1))
done

echo "$LOG" | grep -q 'module loaded' && ok "module loaded" || bad "module loaded missing (LSPosed not injecting?)"
echo "$LOG" | grep -q 'hooked silence AiVoiceDetectDCSUtils#d' && ok "DCS updateDetectInfo hooked" || bad "DCS updateDetectInfo hook missing"
echo "$LOG" | grep -q 'hooked silence AiVoiceDetectDCSUtils#e' && ok "DCS uploadSwitches hooked" || bad "DCS uploadSwitches hook missing"
echo "$LOG" | grep -q 'hooked sink TrackApi#M' && ok "TrackApi.M hooked" || bad "TrackApi.M hook missing"
echo "$LOG" | grep -q 'status=ACTIVE' && ok "status=ACTIVE" || bad "status not ACTIVE (see summary line)"
echo "$LOG" | grep 'summary pkg=' | tail -1 | sed 's/^/       /'

if [ "${1:-}" = "--active" ]; then
  say "2. active: open anti-fraud page"
  "$ADB" shell am start -a "$FRAUD_ACTION" -n "$FRAUD_ACT" >/dev/null 2>&1
  sleep 8
  HITS=$("$ADB" logcat -d -s ATB:'*' 2>/dev/null | grep 'hit silence' | grep -v 'LSPosedFramework' | wc -l || true)
  BLOCKED=$("$ADB" logcat -d -s ATB:'*' 2>/dev/null | grep 'blocked event' | grep -v 'LSPosedFramework' | wc -l || true)
  info "hit silence lines: $HITS"
  info "blocked event lines: $BLOCKED"
  "$ADB" logcat -d -s ATB:'*' 2>/dev/null | grep 'blocked event' | tail -5 | sed 's/^/       /'
fi

say "3. crash check"
if "$ADB" logcat -d -b crash 2>/dev/null | grep -qi "$PKG"; then
  bad "crash buffer contains $PKG"
  "$ADB" logcat -d -b crash 2>/dev/null | grep -i "$PKG" | head -5 | sed 's/^/       /'
else
  ok "no $PKG crash"
fi

"$ADB" shell input keyevent KEYCODE_HOME >/dev/null 2>&1
say "result"
if [ "$FAILED" = 0 ]; then
  echo "  PASS"
  exit 0
fi
echo "  FAILED"
exit 1
