#!/system/bin/sh
# One-shot root restore. The proven sequence:
#   exploit -> ksu-helper --late-load -> verify -> re-apply OTA lockdown
# Run as root OR as shell (the exploit runs as shell; the lockdown needs su).
cd /data/local/tmp || exit 1
LOG=/data/local/tmp/root-now.log
exec >> "$LOG" 2>&1
say() { echo "[$(date '+%H:%M:%S')] $*"; }

say "================ root-now start ================"

if [ -x /system/bin/su ] && su -c true 2>/dev/null; then
  say "already rooted - skipping exploit"
else
  TEMP=$(for z in /sys/class/thermal/thermal_zone*/temp; do cat "$z" 2>/dev/null; done | sort -n | tail -1)
  say "=== rooting (uptime $(cut -d' ' -f1 /proc/uptime)s, temp ${TEMP}) ==="
  if [ "${TEMP:-0}" -gt 48000 ] 2>/dev/null; then
    say "WARNING: device is warm; the exploit gate wants <=48C and may wait"
  fi

  rm -f bope.log temp_su.sock 2>/dev/null
  ./stability-launcher --payload /data/local/tmp/ksu-payload \
                       --helper /data/local/tmp/ksu-helper \
                       --mm-factory /data/local/tmp/mm-exec-factory > bope.log 2>&1 &
  LP=$!
  i=0
  while [ $i -lt 1200 ]; do
    grep -q 'Root achieved' bope.log 2>/dev/null && break
    if ! kill -0 $LP 2>/dev/null && [ $i -gt 6 ]; then say "launcher exited early"; break; fi
    i=$((i + 1)); sleep 0.5
  done
  say "exploit result: $(tail -2 bope.log 2>/dev/null | tr '\n' '|')"

  say "--- ksu-helper --late-load (THE step that installs su) ---"
  ./ksu-helper --late-load 2>&1 | sed 's/^/    /'
  say "kernelsu=$(grep -ic kernelsu /proc/modules 2>/dev/null)  su=$(ls /system/bin/su 2>/dev/null || echo none)"
fi

if [ -x /system/bin/su ]; then
  say "--- triggering the KernelSU service stage (runs module + service.d hooks) ---"
  su -c '/data/adb/ksud services' >/dev/null 2>&1

  say "--- re-applying OTA lockdown (through su) ---"
  su -c 'sh /data/local/tmp/ota_lockdown/lockdown.sh' 2>&1 | sed 's/^/    /'

  say "--- starting the Debian server ---"
  su -c 'sh /data/local/tmp/debian-start.sh' 2>&1 | sed 's/^/    /'
  if (ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null) | grep -q ':1304'; then
    say "    Debian sshd: LISTENING on 1304"
  else
    say "    Debian sshd: NOT listening"
  fi

  say "--- status ---"
  su -c 'sh /data/local/tmp/ota_lockdown/status.sh' 2>&1 | sed 's/^/    /'
  say "RESULT: ROOTED + DEBIAN UP"
else
  say "RESULT: ROOT FAILED - device may be too warm; let it cool and retry"
fi
say "================ root-now end ================"
