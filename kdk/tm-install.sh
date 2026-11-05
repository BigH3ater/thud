#!/usr/bin/env bash
# tm-install.sh [--allow-uninstall] [apk]
# Install a thud-kdk APK on the console from the kit (default: $KIT/apk/current/).
#  1. verify <apk>.sha256          2. back up app data to $KIT/backup/<ts>.tar (keep 5)
#  3. adb install -r               (INSTALL_FAILED_UPDATE_INCOMPATIBLE = signing key
#     changed: stop, unless --allow-uninstall -> uninstall, install, restore the tar)
#  4. place certs/confs only if missing or the sha256 differs
#  5. make thud the home app again if it is not (an uninstall clears the HOME role)
#  6. force-stop, start MainActivity, run tm-smoke.sh
set -euo pipefail
HERE="$(dirname "$(readlink -f "$0")")"
# shellcheck source=kdk/tm-lib.sh
. "$HERE/tm-lib.sh"

ALLOW_UNINSTALL=0
APK=""
for a in "$@"; do
  case "$a" in
    --allow-uninstall) ALLOW_UNINSTALL=1 ;;
    -*) die "usage: $0 [--allow-uninstall] [apk]" ;;
    *) [ -z "$APK" ] || die "only one apk"; APK="$a" ;;
  esac
done
need sha256sum tar
if [ -z "$APK" ]; then
  APK="$(find -L "$KIT/apk/current/" -maxdepth 1 -name 'thud-kdk-*.apk' | head -n1)"
  [ -n "$APK" ] || die "no APK in $KIT/apk/current (run tm-fetch.sh first)"
fi
if [ ! -f "$APK" ] || [ ! -f "$APK.sha256" ]; then die "need $APK and $APK.sha256"; fi
(cd "$(dirname "$APK")" && sha256sum -c "$(basename "$APK").sha256") >&2 || die "sha256 mismatch: $APK"
ver="$(basename "$APK" .apk)"; ver="${ver#thud-kdk-}"
log "apk $APK (versionName $ver)"

adb_connect

# --- 2. backup ---------------------------------------------------------------
BACKUP=""
if "$ADB" shell pm path "$PKG" >/dev/null 2>&1; then
  mkdir -p "$KIT/backup" && chmod 700 "$KIT/backup"
  BACKUP="$KIT/backup/$(date +%Y%m%d-%H%M%S).tar"
  log "backup app data -> $BACKUP"
  ( umask 077
    # \$(...) runs on the device, so missing dirs (fresh install) do not break tar.
    "$ADB" exec-out "run-as $PKG sh -c 'tar -cf - \$(ls -d files databases shared_prefs 2>/dev/null)'" > "$BACKUP" )
  tar -tf "$BACKUP" >/dev/null 2>&1 || die "backup $BACKUP is not a valid tar; not installing"
  log "backup: $(tar -tf "$BACKUP" | wc -l) entries"
  find "$KIT/backup" -maxdepth 1 -name '*.tar' -printf '%T@ %p\n' | sort -rn | tail -n +6 \
    | while read -r _ f; do log "prune backup $f"; rm -f "$f"; done
else
  log "$PKG not installed yet: no backup"
fi

# --- 3. install --------------------------------------------------------------
out="$("$ADB" install -r "$APK" 2>&1 || true)"
printf '%s\n' "$out" >&2
if ! printf '%s' "$out" | grep -q '^Success'; then
  if printf '%s' "$out" | grep -q 'INSTALL_FAILED_UPDATE_INCOMPATIBLE'; then
    [ "$ALLOW_UNINSTALL" = 1 ] || die "signing key differs from the installed app. Re-run with --allow-uninstall (backup + restore) if intended"
    [ -n "$BACKUP" ] || die "no backup taken; refusing to uninstall"
    log "uninstall $PKG (backup $BACKUP)"
    "$ADB" uninstall "$PKG" >&2
    out="$("$ADB" install "$APK" 2>&1 || true)"
    printf '%s\n' "$out" >&2
    printf '%s' "$out" | grep -q '^Success' || die "install after uninstall failed"
    log "restore app data from $BACKUP"
    "$ADB" shell "run-as $PKG tar -xf -" < "$BACKUP"
  else
    die "install failed"
  fi
fi

# --- 4. certs + confs --------------------------------------------------------
place() {  # place <localfile> <relpath under files/> [required]
  local src="$1" rel="$2" want have
  if [ ! -f "$src" ]; then
    [ "${3:-}" = required ] && die "missing $src"
    log "skip files/$rel (no $src)"; return 0
  fi
  want="$(sha256sum "$src" | cut -d' ' -f1)"
  have="$(rr "sha256sum files/$rel 2>/dev/null" | tr -d '\r' | cut -d' ' -f1 || true)"
  if [ "$want" = "$have" ]; then log "ok    files/$rel"; return 0; fi
  put "$src" "$rel"
  log "placed files/$rel"
}
rr "mkdir -p files/certs"
place "$KIT/secrets/glassos_ca.pem"          certs/ca.crt     required
place "$KIT/secrets/glassos_client_cert.pem" certs/client.crt required
place "$KIT/secrets/glassos_client_key.pem"  certs/client.key required
place "$KIT/secrets/kdk-ha.conf"             kdk-ha.conf
place "$KIT/conf/kdk-launcher.conf"          kdk-launcher.conf

# --- 5. home role -------------------------------------------------------------
# The uninstall path drops the HOME role; a plain install -r keeps it, so this is a no-op there.
if "$ADB" shell cmd shortcut get-default-launcher 2>/dev/null | tr -d '\r' | grep -q "$PKG/"; then
  log "ok    default launcher = $PKG"
else
  log "set home activity $PKG/.MainActivity"
  "$ADB" shell cmd package set-home-activity "$PKG/.MainActivity" >&2
fi

# --- 6. restart + smoke --------------------------------------------------------
"$ADB" shell am force-stop "$PKG"
"$ADB" shell am start -n "$PKG/.MainActivity" >&2
exec "$HERE/tm-smoke.sh" "$ver"
