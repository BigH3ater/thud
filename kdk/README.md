# kdk install/test scripts

These scripts install and test the kdk thud build on the NordicTrack console (10.1.30.103, wireless adb) from **kdk-dkr-04**, run as `kdkadmin`. They need no Docker and no JDK. The APKs come from the fork's `kdk-v*` pre-releases, which are built by `.github/workflows/build.yml`.

Design: vault note `linux-tools dev pipeline v2` ("Share layout", "Install/test scripts").

## Kit
`KIT=${KIT:-/mnt/linux-tools/thud}` (NFS share `linux-tools`). The scripts read only from the kit, never from a scratchpad.

| Path | Holds |
|---|---|
| `$KIT/../platform-tools/37.0.1/adb` | the pinned adb (override with `ADB=`). Debian adb is too old for `pair` |
| `$KIT/src/` | this repo (`git clone`; scripts in `kdk/`) |
| `$KIT/secrets/` (0700) | `glassos_ca.pem`, `glassos_client_cert.pem`, `glassos_client_key.pem`, `kdk-ha.conf`, all 0600 |
| `$KIT/conf/kdk-launcher.conf` | Apps-button targets (not secret) |
| `$KIT/apk/<ver>/`, `current`, `previous` | fetched releases (newest 10 kept) |
| `$KIT/backup/` | app-data tar taken before each install (newest 5 kept) |
| `$KIT/state/` (0700) | `adb-port` (last good connect port), `smoke-*.log` |

Host tools: `bash`, `curl`, `nc`, `sha256sum`, `tar`, `timeout`, `xargs`.

## Scripts
| Script | Does |
|---|---|
| `tm-adb.sh [pair <port> <code>]` | Connects to the console. It tries the saved port first, otherwise it scans 30000-50000 (skipping DIRCON 36866) and accepts only a device whose model is `CVTEMediatekXenon1`. It disconnects stale or offline `10.1.30.103:*` entries and mDNS `_adb-tls-connect` duplicates, then prints `export ANDROID_SERIAL=10.1.30.103:<port>`. |
| `tm-fetch.sh <kdk-vX.Y.Z\|latest>` | Downloads `thud-kdk-<ver>.apk` and its `.sha256` from the release, verifies them, stores them in `apk/<ver>/`, moves `current`/`previous` and prunes old versions. |
| `tm-install.sh [--allow-uninstall] [apk]` | Verifies the sha256, backs up app data (`run-as ... tar`), runs `adb install -r`, places certs and confs only when they are missing or changed, restarts thud, then runs tm-smoke. |
| `tm-smoke.sh [versionName]` | Read-only checks: version, process, HUDService, top activity, default launcher, cert sizes, logcat lines (`HA integration ENABLED`, `Using INTERNAL certs dir`, `KdkSmoke: glassos connected`, no `UNAVAILABLE`/`PERMISSION_DENIED`/`Missing certificates`), NordicFTMS running, DIRCON open. It exits non-zero on any miss and logs to `state/`. |
| `tm-lib.sh` | Shared helpers that the four scripts source (paths, adb pinning, `run-as` quoting). |

## Normal update
```sh
cd /mnt/linux-tools/thud/src && git pull
eval "$(kdk/tm-adb.sh)"          # wireless debugging on at the console
kdk/tm-fetch.sh latest
kdk/tm-install.sh                # install -r keeps certs, profiles, history
```

## Signing key change (one time, or when the key changes)
If `adb install -r` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the installed app was signed with a different key. `tm-install.sh` stops. Re-run it with `--allow-uninstall`: it uninstalls, installs, and restores the backup tar it just took. The certs and confs are then re-placed. The uninstall also clears the home-app role; `tm-install.sh` re-sets it (`cmd package set-home-activity`) whenever `cmd shortcut get-default-launcher` does not name thud.

## Rollback
```sh
kdk/tm-install.sh "$(ls /mnt/linux-tools/thud/apk/previous/thud-kdk-*.apk)"
```
This is a downgrade, so `-r` fails because the versionCode is lower. Uninstall and restore with `--allow-uninstall`. The home-app role is re-set automatically here too.

## Notes
- `tm-smoke.sh` on a build older than the CI workflow has no `KdkSmoke` line, so that check fails there. That is expected.
- Never put a cert, a webhook id or a lab hostname in this directory. Secrets live only in `$KIT/secrets/`, and their source of truth is 1Password.
