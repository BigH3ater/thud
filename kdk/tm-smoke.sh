#!/usr/bin/env bash
# tm-smoke.sh [expected-versionName]
# Read-only health check of thud on the console. Exit non-zero on any miss.
# Log: $KIT/state/smoke-<ts>.log. Expected version: arg, else $KIT/apk/current.
# SMOKE_WAIT (default 90 s) bounds the wait for the process and the log lines.
set -euo pipefail
# shellcheck source=kdk/tm-lib.sh
. "$(dirname "$(readlink -f "$0")")/tm-lib.sh"

need nc timeout
adb_connect
LOG="$STATE/smoke-$(date +%Y%m%d-%H%M%S).log"
exec > >(tee -a "$LOG") 2>&1
WAIT="${SMOKE_WAIT:-90}"
expect="${1:-$(readlink "$KIT/apk/current" 2>/dev/null || true)}"
fails=0
pass() { printf 'PASS  %s\n' "$*"; }
miss() { printf 'FAIL  %s\n' "$*"; fails=$((fails + 1)); }
sh_() { "$ADB" shell "$@" 2>/dev/null | tr -d '\r'; }   # device command, CRs stripped

echo "== tm-smoke $ANDROID_SERIAL $(date -Is)"

vn="$(sh_ dumpsys package "$PKG" | sed -n 's/^ *versionName=//p' | head -n1 || true)"
if [ -z "$expect" ]; then
  echo "INFO  versionName=$vn (no expected version given)"
elif [ "$vn" = "$expect" ]; then pass "versionName $vn"
else miss "versionName $vn, expected $expect"; fi

pid=""
for _ in $(seq "$WAIT"); do
  pid="$(sh_ pidof "$PKG" || true)"
  [ -n "$pid" ] && break
  sleep 1
done
if [ -n "$pid" ]; then pass "pidof $PKG = $pid"; else miss "pidof $PKG (not running)"; fi

if sh_ dumpsys activity services "$PKG/.HUDService" | grep -q 'ServiceRecord'; then
  pass "HUDService running"; else miss "HUDService: no ServiceRecord"; fi

if sh_ dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' | grep -q "$PKG/.MainActivity"; then
  pass "top resumed = $PKG/.MainActivity"; else miss "MainActivity is not the top resumed activity"; fi

if sh_ cmd shortcut get-default-launcher | grep -q "$PKG/"; then
  pass "default launcher = $PKG"; else miss "default launcher is not $PKG"; fi

# Cert sizes: the seeded kit files if present, else the NordicFTMS v1.1.3 sizes.
size_of() { if [ -f "$1" ]; then wc -c < "$1" | tr -d ' '; else echo "$2"; fi; }
for c in ca.crt:glassos_ca.pem:1265 client.crt:glassos_client_cert.pem:1090 client.key:glassos_client_key.pem:1704; do
  IFS=: read -r name kitf def <<< "$c"
  want="$(size_of "$KIT/secrets/$kitf" "$def")"
  have="$(rr "stat -c %s files/certs/$name 2>/dev/null" | tr -d '\r' || true)"
  if [ "$have" = "$want" ]; then pass "files/certs/$name $have B"; else miss "files/certs/$name size '${have:-missing}', expected $want"; fi
done

if [ -n "$pid" ]; then
  logs=""
  for _ in $(seq "$WAIT"); do
    logs="$(sh_ logcat -d --pid="$pid" || true)"
    if printf '%s' "$logs" | grep -q 'HA integration ENABLED' \
      && printf '%s' "$logs" | grep -q 'Using INTERNAL certs dir' \
      && printf '%s' "$logs" | grep -q 'KdkSmoke: glassos connected'; then break; fi
    sleep 1
  done
  for want in 'HA integration ENABLED' 'Using INTERNAL certs dir' 'KdkSmoke: glassos connected'; do
    if printf '%s' "$logs" | grep -qF "$want"; then pass "logcat: $want"; else miss "logcat: no '$want'"; fi
  done
  bad="$(printf '%s' "$logs" | grep -E 'UNAVAILABLE|PERMISSION_DENIED|Missing certificates' | head -n3 || true)"
  if [ -z "$bad" ]; then pass "logcat: no UNAVAILABLE/PERMISSION_DENIED/Missing certificates"
  else miss "logcat errors:"; printf '      %s\n' "$bad"; fi
else
  miss "logcat checks skipped (no pid)"
fi

if [ -n "$(sh_ pidof com.nordicftms.app || true)" ]; then
  pass "NordicFTMS (HA tracking) running"; else miss "com.nordicftms.app not running"; fi
if nc -z -w3 "$CONSOLE_IP" "$DIRCON_PORT"; then
  pass "DIRCON $CONSOLE_IP:$DIRCON_PORT open"; else miss "DIRCON $CONSOLE_IP:$DIRCON_PORT closed"; fi

echo "== $fails miss(es); log $LOG"
[ "$fails" -eq 0 ]
