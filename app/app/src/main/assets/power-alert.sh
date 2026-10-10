#!/system/bin/sh
# Battery and power alerts over Telegram.
#
# Sourced from watchdog.sh, which already runs as root every 60 s, so no extra
# service and no adapter state to poll. Kept standalone so it can also be run by
# hand: `sh power-alert.sh` checks once and prints what it decided.
#
# Reads battery state from sysfs. That path is root-only, which is fine here:
# the watchdog runs as root. /system/bin/curl does the HTTP, so this works even
# when the chroot is not up.
#
# Alerts, deliberately only on transitions so a 60 s poll cannot spam:
#   - charger plugged in
#   - charger unplugged   <- the one that matters if the phone is on a shelf
#   - still unplugged and the battery has dropped further (15%, then every 5%)
#
# State lives in the ota_lockdown dir, next to the other watchdog state.

PA_DIR=/data/local/tmp/ota_lockdown
PA_STATE="$PA_DIR/power.state"
PA_LOG=/data/local/tmp/ota_lockdown.log
PA_CAP=/sys/class/power_supply/battery/capacity
PA_STATUS=/sys/class/power_supply/battery/status

# Where the Telegram credentials come from. Three options, first one wins:
#   1. POWER_ALERT_TOKEN / POWER_ALERT_CHAT in the environment
#   2. POWER_ALERT_CONF, a file containing BOT_TOKEN= and CHAT_ID= lines
#   3. the default path below
# If none of them yield both values, the check still runs and still updates its
# state file; it just logs that it cannot send.
PA_CONF="${POWER_ALERT_CONF:-/data/local/chroot/debian/etc/telegram-sms/config.env}"

PA_LOW_FIRST=15   # first low-battery alert
PA_LOW_STEP=5     # then re-alert each further drop of this many percent

pa_log() { echo "$(date '+%Y-%m-%d %H:%M:%S') [power] $*" >> "$PA_LOG"; }

pa_send() {
  # $1 = message. Returns 0 on a 2xx from Telegram.
  T="${POWER_ALERT_TOKEN:-}"
  C="${POWER_ALERT_CHAT:-}"
  if [ -z "$T" ] || [ -z "$C" ]; then
    if [ -f "$PA_CONF" ]; then
      T=$(grep -m1 '^BOT_TOKEN' "$PA_CONF" 2>/dev/null | cut -d= -f2-)
      C=$(grep -m1 '^CHAT_ID'   "$PA_CONF" 2>/dev/null | cut -d= -f2-)
    fi
  fi
  if [ -z "$T" ] || [ -z "$C" ]; then
    pa_log "cannot send: set POWER_ALERT_TOKEN and POWER_ALERT_CHAT, or provide $PA_CONF"
    return 1
  fi
  # POST with --data-urlencode rather than building a query string. The messages are
  # multi-line now, and the hand-rolled sed encoder could not encode a newline at all -
  # a raw one in the URL would have truncated the request at the first line break.
  # curl does the whole job properly, whatever the text contains.
  R=$(/system/bin/curl -sS --max-time 20 \
        --data-urlencode "chat_id=$C" \
        --data-urlencode "text=$1" \
        "https://api.telegram.org/bot$T/sendMessage" 2>&1)
  case "$R" in
    # The log is one line per event, so flatten the message for it.
    *'"ok":true'*) pa_log "alert sent: $(printf '%s' "$1" | tr '\n' ' ')"; return 0 ;;
    *)             pa_log "alert FAILED: $(printf '%s' "$R" | head -c 200)"; return 1 ;;
  esac
}

pa_check() {
  CAP=$(cat "$PA_CAP" 2>/dev/null)
  ST=$(cat "$PA_STATUS" 2>/dev/null)
  case "$CAP" in ''|*[!0-9]*) pa_log "cannot read battery capacity"; return ;; esac
  [ -n "$ST" ] || ST=Unknown

  # Previous state: "STATUS CAP LOWLEVEL". Treat a state file older than this boot
  # as absent, so the first check after a reboot does not fire a spurious alert.
  PST=""; PLOW=""
  if [ -f "$PA_STATE" ]; then
    PST=$(cut -d' ' -f1 "$PA_STATE" 2>/dev/null)
    PLOW=$(cut -d' ' -f3 "$PA_STATE" 2>/dev/null)
    UP=$(cut -d. -f1 /proc/uptime 2>/dev/null)
    if [ -n "$UP" ]; then
      # File mtime, read straight from stat, in epochs.
      MT=$(stat -c %Y "$PA_STATE" 2>/dev/null)
      NOW=$(date +%s)
      case "$MT" in ''|*[!0-9]*) MT="" ;; esac
      [ -n "$MT" ] && [ $((NOW - MT)) -gt "$UP" ] && PST=""
    fi
  fi
  case "$PLOW" in ''|*[!0-9]*) PLOW=0 ;; esac

  NEW_LOW=$PLOW

  if [ -z "$PST" ]; then
    pa_log "first check this boot: status=$ST capacity=${CAP}%"
  elif [ "$ST" != "$PST" ]; then
    case "$ST" in
      Charging|Full)
        pa_send "[System Information]
Charger connected.
Battery level: ${CAP}%" ;;
      Discharging|"Not charging")
        pa_send "[System Information]
Charger disconnected.
Battery level: ${CAP}%" ;;
      *)
        pa_send "[System Information]
Charger state changed: $PST to $ST.
Battery level: ${CAP}%" ;;
    esac
    NEW_LOW=0
  fi

  # Low battery while running on the battery. Alert at PA_LOW_FIRST, then each
  # further PA_LOW_STEP drop, so a slow decline does not repeat every minute.
  case "$ST" in
    Discharging|"Not charging"|Unknown)
      if [ "$CAP" -le "$PA_LOW_FIRST" ]; then
        if [ "$NEW_LOW" -eq 0 ] || [ "$CAP" -le $((NEW_LOW - PA_LOW_STEP)) ]; then
          pa_send "[System Information]
Battery low.
Battery level: ${CAP}%"
          NEW_LOW=$CAP
        fi
      fi
      ;;
    *)
      NEW_LOW=0 ;;
  esac

  printf '%s %s %s\n' "$ST" "$CAP" "$NEW_LOW" > "$PA_STATE"
}

# Run a check when executed directly, but stay side-effect free when sourced.
#
# Matching on the script name alone is brittle: any copy under a different name
# silently does nothing. Instead, a marker variable is set by the sourcing side.
if [ "${POWER_ALERT_SOURCED:-0}" != "1" ]; then
  pa_check
fi
