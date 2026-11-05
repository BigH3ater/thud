#!/usr/bin/env bash
# tm-fetch.sh <kdk-vX.Y.Z | latest>
# Download a thud-kdk pre-release (APK + .sha256) from the public fork, verify it, store
# it in $KIT/apk/<ver>/, move the current/previous symlinks and keep the newest 10.
# Public release assets: plain curl, no token.
set -euo pipefail
# shellcheck source=kdk/tm-lib.sh
. "$(dirname "$(readlink -f "$0")")/tm-lib.sh"

REPO="${REPO:-BigH3ater/thud}"
KEEP=10
[ $# -eq 1 ] || die "usage: $0 <kdk-vX.Y.Z|latest>"
need curl sha256sum

tag="$1"
if [ "$tag" = "latest" ]; then
  # /releases/latest skips pre-releases, so take the newest kdk-v* from the list.
  tag="$(curl -fsSL "https://api.github.com/repos/$REPO/releases?per_page=30" \
    | grep -o '"tag_name": *"kdk-v[^"]*"' | head -n1 | sed 's/.*"\(kdk-v[^"]*\)"$/\1/')"
  [ -n "$tag" ] || die "no kdk-v* release found on $REPO"
fi
case "$tag" in kdk-v*) ;; *) die "tag must look like kdk-vX.Y.Z (got: $tag)";; esac
ver="${tag#kdk-}"
apk="thud-kdk-$ver.apk"
APKDIR="$KIT/apk"
mkdir -p "$APKDIR"

if [ -f "$APKDIR/$ver/$apk" ] && (cd "$APKDIR/$ver" && sha256sum -c --quiet "$apk.sha256"); then
  log "$ver already fetched and verified"
else
  tmp="$(mktemp -d "$APKDIR/.fetch-XXXXXX")"
  trap 'rm -rf "$tmp"' EXIT
  base="https://github.com/$REPO/releases/download/$tag"
  log "download $base/$apk"
  curl -fsSL -o "$tmp/$apk" "$base/$apk"
  curl -fsSL -o "$tmp/$apk.sha256" "$base/$apk.sha256"
  (cd "$tmp" && sha256sum -c "$apk.sha256") >&2 || die "sha256 mismatch for $apk"
  chmod 644 "$tmp/$apk" "$tmp/$apk.sha256"
  rm -rf "${APKDIR:?}/$ver"
  mkdir -p "$APKDIR/$ver"
  mv "$tmp/$apk" "$tmp/$apk.sha256" "$APKDIR/$ver/"
fi

cur="$(readlink "$APKDIR/current" 2>/dev/null || true)"
if [ "$cur" != "$ver" ]; then
  [ -n "$cur" ] && ln -sfn "$cur" "$APKDIR/previous"
  ln -sfn "$ver" "$APKDIR/current"
fi
prev="$(readlink "$APKDIR/previous" 2>/dev/null || true)"
log "current=$ver previous=${prev:-none}"

# Prune: keep the newest $KEEP version dirs; never current/previous.
n=0
find "$APKDIR" -mindepth 1 -maxdepth 1 -type d ! -name '.*' -printf '%T@ %f\n' | sort -rn \
  | while read -r _ d; do
      n=$((n + 1))
      if [ "$n" -gt "$KEEP" ] && [ "$d" != "$ver" ] && [ "$d" != "$prev" ]; then
        log "prune $d"
        rm -rf "${APKDIR:?}/$d"
      fi
    done

printf '%s\n' "$APKDIR/$ver/$apk"
