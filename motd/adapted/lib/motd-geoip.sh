#!/bin/sh
# Shared helpers for update-motd.d scripts. Source this file.

is_private_ip() {
 case "$1" in
 10.*|192.168.*|172.1[6-9].*|172.2[0-9].*|172.3[0-1].*|127.*|169.254.*|::1|fe80:*|"") return 0 ;;
 *) return 1 ;;
 esac
}

geo_country() {
 ip="$1"
 if is_private_ip "$ip"; then printf 'LAN'; return; fi
 if command -v mmdblookup >/dev/null 2>&1 && [ -f /var/lib/GeoIP/GeoLite2-Country.mmdb ]; then
 c=$(mmdblookup --file /var/lib/GeoIP/GeoLite2-Country.mmdb --ip "$ip" country names en 2>/dev/null | grep -oP '"\K[^"]+(?=")' | head -1)
 [ -n "$c" ] && { printf '%s' "$c"; return; }
 fi
 printf 'Unknown'
}

find_sshd_session_pid() {
 p=$$
 i=0
 while [ "$p" -gt 1 ] 2>/dev/null && [ "$i" -lt 20 ]; do
 c=$(cat /proc/"$p"/comm 2>/dev/null)
 if [ "$c" = "sshd-session" ] || [ "$c" = "sshd" ]; then printf '%s' "$p"; return 0; fi
 p=$(awk '{print $4}' /proc/"$p"/stat 2>/dev/null)
 i=$((i + 1))
 done
 return 1
}

# ADAPTED: tokyo reads this out of journalctl. There is no journald in this
# chroot, but sshd always exports SSH_CONNECTION to the session, which is the
# same information and works everywhere.
current_session_user_ip() {
 [ -n "$SSH_CONNECTION" ] || return 1
 ip=$(printf '%s' "$SSH_CONNECTION" | awk '{print $1}')
 [ -n "$ip" ] || return 1
 printf '%s %s\n' "${USER:-root}" "$ip"
}
