# shellcheck shell=bash
# Shared helpers for kdk/tm-*.sh (sourced, not run). One copy of the kit paths,
# the adb pinning and the run-as quoting, so the four scripts never drift.
#
# Kit layout (vault: linux-tools dev pipeline v2, "Share layout"):
#   $KIT/secrets/  glassos_*.pem + kdk-ha.conf (0600)    $KIT/conf/  kdk-launcher.conf
#   $KIT/apk/<ver>/ + current/previous symlinks          $KIT/backup/ app-data tars
#   $KIT/state/    saved adb port, smoke logs (0700)     $KIT/../platform-tools/37.0.1/adb

KIT="${KIT:-/mnt/linux-tools/thud}"
PKG="io.github.avikulin.thud"
CONSOLE_IP="10.1.30.103"
DIRCON_PORT=36866                # GlassOS/DIRCON gRPC, never an adb port
CONSOLE_MODEL="CVTEMediatekXenon1"
ADB="${ADB:-$KIT/../platform-tools/37.0.1/adb}"   # Debian adb is too old for `pair`
STATE="$KIT/state"

log() { printf '>> %s\n' "$*" >&2; }
die() { printf '!! %s\n' "$*" >&2; exit 1; }

need() {   # need <cmd>...
  local c
  for c in "$@"; do command -v "$c" >/dev/null 2>&1 || die "missing command: $c"; done
}

ensure_state() { mkdir -p "$STATE" && chmod 700 "$STATE"; }

# Pass the WHOLE "run-as PKG sh -c '...'" as one string to `adb shell`, so the device
# shell (not adb's arg splitter) parses it. The inner command must contain no single
# quotes. Same quoting as the 2026-11-01 place-certs-conf.sh.
rr() {     # rr "<shell command run as the app user, cwd = app data dir>"
  "$ADB" shell "run-as $PKG sh -c '$1'"
}
put() {    # put <localfile> <relpath under files/>
  "$ADB" shell "run-as $PKG sh -c 'cat > files/$2'" < "$1"
}

model_ok() {   # model_ok <serial>: the device answers and is the treadmill console
  local m
  m="$(timeout 15 "$ADB" -s "$1" shell getprop ro.product.model 2>/dev/null | tr -d '\r')" || return 1
  [ "$m" = "$CONSOLE_MODEL" ]
}

try_port() {   # try_port <port>: adb connect + model check; disconnect on a miss
  local s="$CONSOLE_IP:$1"
  if timeout 15 "$ADB" connect "$s" 2>&1 | grep -q 'connected to' && model_ok "$s"; then
    return 0
  fi
  "$ADB" disconnect "$s" >/dev/null 2>&1 || true
  return 1
}

scan_ports() { # open TCP ports 30000-50000 on the console, DIRCON excluded
  need nc xargs
  # shellcheck disable=SC2016 # $0/$1 expand in the child sh, not here
  seq 30000 50000 | grep -vx "$DIRCON_PORT" \
    | xargs -P200 -I{} sh -c 'nc -z -w1 "$0" "$1" 2>/dev/null && echo "$1"' "$CONSOLE_IP" {} \
    | sort -n
}

drop_stale() { # disconnect every console/mDNS entry except $ANDROID_SERIAL
  local s
  "$ADB" devices | awk -v keep="$ANDROID_SERIAL" -v ip="$CONSOLE_IP:" \
      'NR > 1 && $1 != "" && $1 != keep && (index($1, ip) == 1 || $1 ~ /_adb-tls-connect/) {print $1}' \
    | while read -r s; do
        log "disconnect stale $s"
        "$ADB" disconnect "$s" </dev/null >/dev/null 2>&1 || true
      done
}

# adb_connect: find the console, export ANDROID_SERIAL=10.1.30.103:<port> so every later
# adb call is pinned. Transport ids change on each reconnect and mDNS adds a duplicate
# entry ("more than one device"), so never use -t <id>.
adb_connect() {
  [ -x "$ADB" ] || die "adb not found at $ADB (seed platform-tools 37.0.1 on the share)"
  need timeout awk
  ensure_state
  local port="" saved="" p
  if [ -n "${ANDROID_SERIAL:-}" ] && model_ok "$ANDROID_SERIAL"; then
    port="${ANDROID_SERIAL##*:}"
  else
    saved="$(cat "$STATE/adb-port" 2>/dev/null || true)"
    if [ -n "$saved" ] && try_port "$saved"; then
      port="$saved"
    else
      log "scanning $CONSOLE_IP:30000-50000 (wireless debugging must be on)"
      for p in $(scan_ports); do
        log "candidate port $p"
        if try_port "$p"; then port="$p"; break; fi
      done
    fi
  fi
  [ -n "$port" ] || die "no adb port on $CONSOLE_IP answers as $CONSOLE_MODEL"
  printf '%s\n' "$port" > "$STATE/adb-port"
  export ANDROID_SERIAL="$CONSOLE_IP:$port"
  drop_stale
  log "device $ANDROID_SERIAL ($CONSOLE_MODEL)"
}
