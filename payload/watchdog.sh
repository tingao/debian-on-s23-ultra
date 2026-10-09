#!/system/bin/sh
# OTA Lockdown watchdog: re-asserts the lockdown every 60s.
# Exits if /data/local/tmp/ota_lockdown/disable appears.
DIR=/data/local/tmp/ota_lockdown
LOG=/data/local/tmp/ota_lockdown.log
WPID=/data/local/tmp/ota_lockdown.watchdog.pid
PKGS="com.wssyncmldm com.sec.android.soagent com.samsung.android.sdm.config"

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') [watchdog] $*" >> "$LOG"; }

echo $$ > "$WPID"
log "watchdog started (pid $$)"

N=0
while true; do
  if [ -f "$DIR/disable" ]; then
    log "disable flag present - watchdog exiting"
    rm -f "$WPID"
    exit 0
  fi
  sleep 60
  N=$((N + 1))

  # staged OTA package reappeared?
  for d in /data/fota /cache/fota /data/ota /data/ota_package; do
    [ -d "$d" ] || continue
    if [ -n "$(ls -A "$d" 2>/dev/null)" ]; then
      log "staged content reappeared in $d -> removing"
      rm -rf "$d"/* 2>/dev/null
    fi
  done

  # staging dir perms drifted?
  for d in /data/fota /cache/fota; do
    [ -d "$d" ] || continue
    if [ "$(stat -c '%u:%a' "$d" 2>/dev/null)" != "0:700" ]; then
      chown root:root "$d" 2>/dev/null
      chmod 0700 "$d" 2>/dev/null
      log "re-locked $d"
    fi
  done

  # hosts mount lost?
  if [ -f "$DIR/hosts.block" ] && ! cmp -s "$DIR/hosts.block" /system/etc/hosts; then
    cp -f "$DIR/hosts.block" "$DIR/hosts.live" 2>/dev/null
    chmod 0644 "$DIR/hosts.live" 2>/dev/null
    chcon u:object_r:system_file:s0 "$DIR/hosts.live" 2>/dev/null
    umount /system/etc/hosts 2>/dev/null
    mount -o bind "$DIR/hosts.live" /system/etc/hosts 2>/dev/null \
      && log "hosts block re-mounted"
  fi

  # packages re-enabled? (every 5 min)
  if [ $((N % 5)) -eq 0 ]; then
    DIS=$(pm list packages -d 2>/dev/null)
    for p in $PKGS; do
      case "$DIS" in
        *"package:$p"*) ;;
        *)
          log "$p was ENABLED again -> re-disabling"
          am force-stop "$p" >/dev/null 2>&1
          pm disable-user --user 0 "$p" >>"$LOG" 2>&1
          ;;
      esac
    done
  fi
done
