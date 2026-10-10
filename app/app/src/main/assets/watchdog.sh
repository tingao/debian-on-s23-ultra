#!/system/bin/sh
# OTA Lockdown watchdog v2 — re-asserts BOTH layers, and specifically catches
# the regression found in testing: `pm install-existing` restores an
# uninstalled-for-0 system app, and `pm enable` flips the disabled flag.
# v1 only re-disabled, so a restored package stayed installed (though disabled).
DIR=/data/local/tmp/ota_lockdown
LOG=/data/local/tmp/ota_lockdown.log
WPID=/data/local/tmp/ota_lockdown.watchdog.pid
PKGS="com.wssyncmldm com.sec.android.soagent com.samsung.android.sdm.config"

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') [watchdog] $*" >> "$LOG"; }

# --- single instance ------------------------------------------------------
# Any starter can call this script (lockdown.sh, the app, a manual run). Without
# this guard a second instance overwrites the pidfile and orphans the first
# loop, so duplicates accumulate - that is exactly how three piled up. Check the
# pidfile, then confirm the pid is alive AND really is this script, so a
# recycled pid cannot block a legitimate start.
if [ -f "$WPID" ]; then
  OLD=$(cat "$WPID" 2>/dev/null)
  if [ -n "$OLD" ] && [ -d "/proc/$OLD" ]; then
    if tr '\0' ' ' < "/proc/$OLD/cmdline" 2>/dev/null | grep -q 'watchdog\.sh'; then
      log "another watchdog is already running (pid $OLD) - exiting"
      exit 0
    fi
  fi
fi

echo $$ > "$WPID"
log "watchdog v2 started (pid $$)"

N=0
while true; do
  if [ -f "$DIR/disable" ]; then
    log "disable flag present - exiting"
    rm -f "$WPID"
    exit 0
  fi
  sleep 60
  N=$((N + 1))

  # Battery / mains alerts over Telegram. Sourced, not executed, so the 60 s loop
  # here is the only scheduler needed - no second service ticking on the phone.
  # The file is optional: an older deployment without it still runs.
  if [ -f /data/local/tmp/power-alert.sh ]; then
    POWER_ALERT_SOURCED=1 . /data/local/tmp/power-alert.sh
    pa_check
  fi

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

  # Debian sshd must stay reachable - restart it if it died.
  if [ -f /data/local/tmp/debian-start.sh ]; then
    if ! (ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null) | grep -q ':1304'; then
      log "debian sshd not listening on 1304 -> restarting"
      sh /data/local/tmp/debian-start.sh >> "$LOG" 2>&1
      if (ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null) | grep -q ':1304'; then
        log "debian sshd restored"
      else
        log "debian sshd restart FAILED"
      fi
    fi
  fi

  # rsyslog must stay up: sshd's auth.log depends on it, and sshguard reads that.
  # sshguard is deliberately NOT restarted here. It cannot block on this kernel
  # (nft -> "Operation not supported", iptables -> "No chain/target/match by
  # that name"; Android 16 enforces the firewall in eBPF), so its only output is
  # a WOULD-BLOCK log line, and its lifetime is unreliable. Retrying it every
  # 60s produced nothing but log churn.
  if [ -x /data/local/chroot/debian/usr/sbin/rsyslogd ]; then
    pgrep -x rsyslogd >/dev/null 2>&1 || {
      log "rsyslogd died -> restarting"
      setsid chroot /data/local/chroot/debian /usr/sbin/rsyslogd -n >> "$LOG" 2>&1 < /dev/null &
    }
  fi

  # --- vendor wifi log daemon ------------------------------------------
  # cnss_diag is an init-started Qualcomm logger that burns ~7% of a core and
  # writes a fresh 31 MB file every minute (~1.8 GB/hour of flash wear) for
  # diagnostics nobody reads. There is NO Settings toggle for it - ctl.stop is
  # the only control. Verified that wifi keeps working with it stopped (LAN and
  # internet ping, 0% loss). init restarts it at boot, so this runs every pass.
  if [ "$(getprop init.svc.vendor.cnss_diag 2>/dev/null)" = "running" ]; then
    log "cnss_diag running -> stopping (saves ~7% cpu + flash wear)"
    setprop ctl.stop vendor.cnss_diag 2>/dev/null
  fi

  # --- packages that must stay dead ------------------------------------
  # Beyond the FOTA set: faceservice (face unlock) and vending (Play Store).
  # Samsung has been observed restoring packages on its own (game.gos resists
  # pm uninstall entirely), so re-check and re-apply rather than trusting it.
  for kp in com.samsung.faceservice com.android.vending; do
    if pm path "$kp" >/dev/null 2>&1; then
      log "$kp came back -> disabling + removing"
      pm disable-user --user 0 "$kp" >/dev/null 2>&1
      pm uninstall --user 0 "$kp" >/dev/null 2>&1
    fi
  done

  # --- radio policy: WiFi always on, airplane mode follows the SIM -------
  # Backgrounded on purpose: a run can legitimately wait up to 10 minutes for
  # WiFi to reassociate after an airplane-mode toggle, and blocking the watchdog
  # loop for that long would stall the OTA enforcement. The script takes its own
  # lock, so stacking 60s passes is harmless.
  if [ -x /data/local/tmp/radio-policy.sh ]; then
    setsid sh /data/local/tmp/radio-policy.sh >/dev/null 2>&1 < /dev/null &
  fi

  # --- disk: act on FREE SPACE, not on chroot size ----------------------
  # /data is f2fs with usrquota,grpquota but no prjquota mounted, so there is no
  # per-directory hard limit available. Rather than an arbitrary size budget for
  # the chroot, watch the thing that actually hurts: free space on /data. Only
  # reclaim once free space drops below 1 GB. Uses df, not du - walking 2.8 GB
  # of chroot files every pass was needless I/O.
  AVAIL_MB=$(df -k /data 2>/dev/null | awk 'NR==2{printf "%d", $4/1024}')
  if [ -n "$AVAIL_MB" ] && [ "$AVAIL_MB" -lt 1024 ] 2>/dev/null; then
    log "disk free=${AVAIL_MB}MB (<1GB) -> reclaiming"
    rm -rf /data/local/chroot/debian/var/cache/apt/archives/*.deb 2>/dev/null
    find /data/local/chroot/debian/var/log -name '*.gz' -delete 2>/dev/null
    find /data/local/chroot/debian/var/log -name '*.[0-9]' -delete 2>/dev/null
    find /data/local/chroot/debian/tmp -type f -atime +1 -delete 2>/dev/null
    # second pass: the big remaining consumers
    AVAIL2=$(df -k /data 2>/dev/null | awk 'NR==2{printf "%d", $4/1024}')
    if [ "$AVAIL2" -lt 1024 ] 2>/dev/null; then
      log "  still ${AVAIL2}MB free -> deeper reclaim"
      rm -rf /data/local/chroot/debian/var/cache/* 2>/dev/null
      find /data/local/chroot/debian/var/log -type f -size +10M -delete 2>/dev/null
      AVAIL2=$(df -k /data 2>/dev/null | awk 'NR==2{printf "%d", $4/1024}')
      log "  deeper reclaim done: ${AVAIL2}MB free"
    else
      log "  after reclaim: ${AVAIL2}MB free"
    fi
  fi

  # both package layers, every 5 min
  if [ $((N % 5)) -eq 0 ]; then
    for p in $PKGS; do
      L=$(dumpsys package "$p" 2>/dev/null | grep -m1 'User 0:')
      case "$L" in
        *installed=false*) : ;;
        *)
          log "$p is INSTALLED for user 0 again -> uninstalling (was: $L)"
          pm uninstall --user 0 "$p" >> "$LOG" 2>&1
          ;;
      esac
      case "$L" in
        *enabled=3*) : ;;
        *)
          log "$p disabled-flag lost -> re-disabling"
          pm disable-user --user 0 "$p" >> "$LOG" 2>&1
          ;;
      esac
    done
  fi
done
