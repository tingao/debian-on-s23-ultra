#!/system/bin/sh
# Compact status. A FOTA client counts as BLOCKED if it is uninstalled for
# user 0 (installed=false) OR disabled. Uninstalled-for-0 packages do NOT appear
# in `pm list packages -d`, which is why the old counter read 0/3.
B=0
for p in com.wssyncmldm com.sec.android.soagent com.samsung.android.sdm.config; do
  L=$(dumpsys package "$p" 2>/dev/null | grep -m1 'User 0:')
  if echo "$L" | grep -q 'installed=false'; then B=$((B+1))
  elif echo "$L" | grep -qE 'enabled=2|enabled=3|enabled=4'; then B=$((B+1))
  fi
done
HST=$(grep -c '^0\.0\.0\.0' /system/etc/hosts 2>/dev/null)
STG=$(ls -A /data/fota 2>/dev/null | wc -l | tr -d ' ')
WD=$(cat /data/local/tmp/ota_lockdown.watchdog.pid 2>/dev/null)
# Do NOT trust the pidfile alone: it survives reboots in /data/local/tmp and PIDs
# get recycled, so a live pid may belong to something else entirely. Confirm the
# process is actually our watchdog script, or this reports a false "yes" - which
# is exactly how a dead watchdog went unnoticed after a reboot.
WDA=no
if [ -n "$WD" ] && [ -d "/proc/$WD" ] && \
   tr '\0' ' ' < "/proc/$WD/cmdline" 2>/dev/null | grep -q 'watchdog\.sh'; then
  WDA=yes
fi
DNS=$(ping -c 1 -W 2 fota-cloud-dn.ospserver.net 2>&1 | head -1)
echo "fota_blocked=$B/3"
echo "hosts_blocked=$HST"
echo "staging_files=$STG"
echo "watchdog=$WDA"
echo "root=$(id -u)"
echo "dns=$DNS"
echo "staged_ota=$(ls -la /data/fota/update.zip 2>/dev/null | wc -l | tr -d ' ')"
