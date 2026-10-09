#!/system/bin/sh
# ============================================================================
#  OTA Lockdown - one idempotent script. Safe to run as often as you like:
#  after every boot, after re-rooting, or by hand.
#
#  What it does:
#    1. disables the Samsung OTA clients (persists in /data, needs no root)
#    2. sets the AOSP "no automatic update" flag
#    3. deletes any staged OTA package and locks the staging dirs to root-only
#    4. bind-mounts an OTA/DM hostname blocklist over /system/etc/hosts (needs root)
#    5. starts the watchdog (needs root)
#
#  Turn everything off:  touch /data/local/tmp/ota_lockdown/disable
# ============================================================================
DIR=/data/local/tmp/ota_lockdown
LOG=/data/local/tmp/ota_lockdown.log
PKGS="com.wssyncmldm com.sec.android.soagent com.samsung.android.sdm.config"
HOSTS_SRC="$DIR/hosts.block"
HOSTS_LIVE="$DIR/hosts.live"

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG"; }

if [ -f "$DIR/disable" ]; then
  log "disable flag present - nothing done"
  echo "ota_lockdown is disabled (remove $DIR/disable to re-enable)"
  exit 0
fi

UID_NOW=$(id -u)
log "===== lockdown run (uid=$UID_NOW) ====="

# --- 1. disable the Samsung OTA clients -------------------------------------
for p in $PKGS; do
  am force-stop "$p" >/dev/null 2>&1
  OUT=$(pm disable-user --user 0 "$p" 2>&1)
  log "disable-user $p -> ${OUT:-<ok>}"
done

# --- 2. AOSP no-auto-update flag -------------------------------------------
settings put global ota_disable_automatic_update 1 2>/dev/null \
  && log "settings global ota_disable_automatic_update=1"

# --- 3. clear + lock the OTA staging dirs ----------------------------------
for d in /data/fota /cache/fota /data/ota /data/ota_package; do
  [ -d "$d" ] || continue
  if [ -n "$(ls -A "$d" 2>/dev/null)" ]; then
    log "clearing $d -> $(ls -A "$d" 2>/dev/null | tr '\n' ' ')"
    rm -rf "$d"/* 2>/dev/null
  fi
  if [ "$UID_NOW" = "0" ]; then
    chown root:root "$d" 2>/dev/null
    chmod 0700 "$d" 2>/dev/null
  fi
done
log "staging: $(ls -ld /data/fota /cache/fota 2>/dev/null | tr '\n' '|')"

# --- 4. hosts blocklist (root only) ---------------------------------------
if [ "$UID_NOW" != "0" ]; then
  log "not root - skipping hosts block and watchdog"
  log "===== lockdown done (unprivileged) ====="
  echo "OK (unprivileged: packages+settings+staging only)"
  exit 0
fi

if [ -f "$HOSTS_SRC" ]; then
  cp -f "$HOSTS_SRC" "$HOSTS_LIVE" 2>/dev/null
  chmod 0644 "$HOSTS_LIVE" 2>/dev/null
  # SELinux label matters: anything else and the resolver silently ignores the file.
  chcon u:object_r:system_file:s0 "$HOSTS_LIVE" 2>/dev/null

  if cmp -s "$HOSTS_LIVE" /system/etc/hosts; then
    log "hosts block already active ($(grep -c '^0\.0\.0\.0' "$HOSTS_LIVE") domains)"
  else
    umount /system/etc/hosts 2>/dev/null
    if mount -o bind "$HOSTS_LIVE" /system/etc/hosts 2>>"$LOG"; then
      log "hosts block MOUNTED - $(grep -c '^0\.0\.0\.0' "$HOSTS_LIVE") domains blackholed"
    else
      log "ERROR: hosts bind mount FAILED"
    fi
  fi
else
  log "WARN: $HOSTS_SRC missing"
fi

# --- 5. watchdog ----------------------------------------------------------
# The pidfile lives in /data/local/tmp, which SURVIVES a reboot, and PIDs get
# recycled. Checking only "-d /proc/$OLDPID" therefore reported "already running"
# after a reboot when the pid had been handed to some unrelated process (observed:
# pid 9116 became com.samsung.android.app.routines), so the watchdog silently
# never started and cnss_diag came back. Verify it is actually our script.
WPID=/data/local/tmp/ota_lockdown.watchdog.pid
RUNNING=0
if [ -f "$WPID" ]; then
  OLDPID=$(cat "$WPID" 2>/dev/null)
  if [ -n "$OLDPID" ] && [ -d "/proc/$OLDPID" ] && \
     tr '\0' ' ' < "/proc/$OLDPID/cmdline" 2>/dev/null | grep -q 'watchdog\.sh'; then
    RUNNING=1
  else
    rm -f "$WPID"   # stale - pid is gone or belongs to something else
  fi
fi
if [ "$RUNNING" = "1" ]; then
  log "watchdog already running (pid $(cat "$WPID"))"
elif [ -f "$DIR/watchdog.sh" ]; then
  sh "$DIR/watchdog.sh" >/dev/null 2>&1 &
  log "watchdog started (pid $!)"
fi

log "===== lockdown done ====="
echo "OTA lockdown applied."
echo "  packages disabled : $(pm list packages -d 2>/dev/null | grep -cE 'wssyncmldm|soagent|sdm\.config')/3"
echo "  hosts blocked     : $(grep -c '^0\.0\.0\.0' "$HOSTS_LIVE" 2>/dev/null) domains"
echo "  staging           : $(ls -ld /data/fota 2>/dev/null | awk '{print $1, $3, $4}')"
echo "  log               : $LOG"
