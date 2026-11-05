#!/usr/bin/env bash
# tm-adb.sh [pair <port> <pin>]
# Connect adb to the treadmill console and print the serial pin for this shell:
#   eval "$(kdk/tm-adb.sh)"            # sets ANDROID_SERIAL=10.1.30.103:<port>
#   kdk/tm-adb.sh pair 41234 123456    # first time / after "forget": pairing port + code
# Tries the saved port ($KIT/state/adb-port) first, else scans 30000-50000 (not 36866)
# and accepts only a device whose ro.product.model is CVTEMediatekXenon1.
set -euo pipefail
# shellcheck source=kdk/tm-lib.sh
. "$(dirname "$(readlink -f "$0")")/tm-lib.sh"

if [ "${1:-}" = "pair" ]; then
  [ $# -eq 3 ] || die "usage: $0 pair <pairing-port> <pairing-code>"
  [ -x "$ADB" ] || die "adb not found at $ADB"
  log "pairing with $CONSOLE_IP:$2"
  "$ADB" pair "$CONSOLE_IP:$2" "$3" >&2
elif [ $# -ne 0 ]; then
  die "usage: $0 [pair <pairing-port> <pairing-code>]"
fi

adb_connect
printf 'export ANDROID_SERIAL=%s\n' "$ANDROID_SERIAL"
