#!/system/bin/sh
# ============================================================================
#  radio-policy.sh - WiFi always on; airplane mode follows the SIM.
#
#  No SIM  -> airplane mode ON, WiFi kept on  (the cellular radio is useless and
#             only draws current scanning, and the modem retries forever)
#  Any SIM -> airplane mode OFF
#  Always  -> WiFi ON
#
#  WHAT "WIFI IS ON" MEANS HERE: `settings get global wifi_on` returns 2, not 1,
#  when WiFi is up (1 and 2 both mean on - 0 is off). Checking for "1" alone made
#  the script think WiFi was off while it was carrying our SSH session. The
#  authoritative test is an address on wlan0; the setting is only used to decide
#  whether to ask for it to be turned on.
#
#  WHY IT WAITS SO LONG: after airplane mode toggles, this handset can take
#  several minutes to reassociate. The wait is therefore up to 10 minutes, and a
#  nudge (disable/enable) is tried once at the two-minute mark. Undoing airplane
#  mode only happens after that whole window, because undoing it would itself
#  drop the link again.
#
#  A SIM counts as present if ANY slot reports something other than ABSENT, so
#  "ABSENT,READY" means a SIM is fitted. Testing for ABSENT anywhere gets that
#  wrong.
# ============================================================================
set -u

LOG=/data/local/tmp/radio-policy.log
LOCK=/data/local/tmp/radio-policy.lock
WAIT_MAX=${RADIO_WAIT:-600}     # seconds to wait for WiFi to come back
NUDGE_AT=120                    # try a disable/enable cycle at this point

# Single instance. The watchdog calls this every 60s, but a run can wait up to
# WAIT_MAX for WiFi - without a lock, twenty passes would stack twenty waiters.
if ! mkdir "$LOCK" 2>/dev/null; then
  P=$(cat "$LOCK/pid" 2>/dev/null)
  if [ -n "${P:-}" ] && [ -d "/proc/$P" ]; then exit 0; fi   # a run is active
  rm -rf "$LOCK"                                             # stale
  mkdir "$LOCK" 2>/dev/null || exit 0
fi
echo $$ > "$LOCK/pid" 2>/dev/null
trap 'rm -rf "$LOCK"' EXIT INT TERM

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" >> "$LOG"
        s=$(wc -c < "$LOG" 2>/dev/null || echo 0)
        [ "$s" -gt 131072 ] && { tail -60 "$LOG" > "$LOG.t" 2>/dev/null && mv "$LOG.t" "$LOG"; }
        return 0; }

# 0 = off; 1 and 2 both mean on
wifi_setting_on() { v=$(settings get global wifi_on 2>/dev/null); [ "$v" = "1" ] || [ "$v" = "2" ]; }
wifi_has_ip()     { ip -o addr show wlan0 2>/dev/null | grep -q 'inet '; }
airplane()        { [ "$(settings get global airplane_mode_on 2>/dev/null)" = "1" ]; }

set_airplane() {
  if [ "$1" = "true" ]; then
    cmd connectivity airplane-mode enable >/dev/null 2>&1 || {
      settings put global airplane_mode_on 1
      am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true >/dev/null 2>&1
    }
  else
    cmd connectivity airplane-mode disable >/dev/null 2>&1 || {
      settings put global airplane_mode_on 0
      am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false >/dev/null 2>&1
    }
  fi
}

# Wait for a real address on wlan0, nudging once.
wifi_wait() {
  waited=0
  nudged=0
  while [ "$waited" -lt "$WAIT_MAX" ]; do
    if wifi_has_ip; then
      [ "$waited" -gt 0 ] && log "wifi associated after ${waited}s"
      return 0
    fi
    if [ "$waited" -ge "$NUDGE_AT" ] && [ "$nudged" = "0" ]; then
      nudged=1
      log "no address after ${waited}s -> nudging wifi (disable/enable)"
      svc wifi disable >/dev/null 2>&1
      sleep 3
      svc wifi enable  >/dev/null 2>&1
    fi
    sleep 10
    waited=$((waited + 10))
    [ $((waited % 120)) -eq 0 ] && log "still waiting for wifi (${waited}s/${WAIT_MAX}s)"
  done
  return 1
}

# Ask for WiFi only if the setting says it is off; never toggle a working link.
wifi_ensure() {
  if wifi_has_ip; then return 0; fi
  if wifi_setting_on; then
    log "wifi setting is on but no address yet - waiting"
  else
    log "wifi is off -> enabling"
    svc wifi enable >/dev/null 2>&1
  fi
  wifi_wait
}

[ -f /data/local/tmp/radio-policy.disable ] && exit 0

# RADIO_SIM_STATE exists so the SIM-present branch can be tested without a SIM,
# which is otherwise impossible on this handset.
SIM_STATE=${RADIO_SIM_STATE:-$(getprop gsm.sim.state 2>/dev/null)}
PRESENT=$(printf '%s' "$SIM_STATE" | tr ',' '\n' | grep -vE '^[[:space:]]*ABSENT[[:space:]]*$' | tr -d ' \t')

if [ -z "$PRESENT" ]; then
  # ---------------------------------------------------------------- no SIM --
  if wifi_has_ip; then
    # Already in the desired state - just make sure airplane mode matches.
    if ! airplane; then
      log "no SIM (state=$SIM_STATE) -> airplane ON"
      set_airplane true
      sleep 3
    fi
    exit 0
  fi

  if ! airplane; then
    log "no SIM (state=$SIM_STATE) -> airplane ON, wifi kept on"
    set_airplane true
    sleep 3
  else
    log "no SIM, airplane already on, wifi not associated - waiting"
  fi

  if wifi_wait; then
    log "ok: airplane=$(settings get global airplane_mode_on 2>/dev/null), wifi has an address"
  else
    log "WIFI DID NOT COME BACK within ${WAIT_MAX}s -> undoing airplane mode"
    set_airplane false
    sleep 3
    svc wifi enable >/dev/null 2>&1
    if wifi_wait; then
      log "recovered: airplane off, wifi back"
    else
      log "STILL no wifi after undoing airplane mode - needs a human"
    fi
  fi
else
  # ------------------------------------------------------------- SIM fitted --
  if airplane; then
    log "SIM present (state=$SIM_STATE) -> airplane OFF"
    set_airplane false
    sleep 3
  fi
  wifi_ensure && log "ok: SIM present, airplane off, wifi up"
fi
exit 0
