#!/system/bin/sh
# Lean, idempotent Debian server start. Called on every root re-establishment.
R=/data/local/chroot/debian
LOG=/data/local/tmp/debian-server.log
say() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG"; }

# --- self-provisioning: is there a Debian to start? ----------------------
# The app is meant to be installed and clicked once. Rooting, the OTA lockdown
# and starting the server all live here, but a rootfs has to exist before there
# is a server to start, and a rootfs is far too big to ship inside an APK.
#
# So: if the chroot is missing, look for a rootfs tarball in the usual places and
# use it. If there is not one, say so precisely, with the command to fix it. The
# dashboard shows Debian as down either way, but the log says why.
if [ ! -x "$R/usr/bin/bash" ] && [ ! -x "$R/bin/bash" ]; then
  say "no Debian rootfs at $R - looking for one to unpack"
  ROOTFS=""
  for c in /sdcard/Download/debian13-arm64.tar.xz /sdcard/Download/*debian*.tar.xz \
           /sdcard/Download/*rootfs*.tar.xz /data/local/tmp/debian*.tar.xz; do
    [ -f "$c" ] && { ROOTFS="$c"; break; }
  done

  if [ -n "$ROOTFS" ] && [ -x /data/local/tmp/00-bootstrap.sh ]; then
    say "bootstrapping from $ROOTFS (a few minutes)"
    sh /data/local/tmp/00-bootstrap.sh "$ROOTFS" >> "$LOG" 2>&1
    if [ -x "$R/usr/bin/bash" ] || [ -x "$R/bin/bash" ]; then
      say "bootstrap finished"
    else
      say "ERROR: bootstrap did not produce a working rootfs, see the lines above"
      exit 1
    fi
  else
    say "ERROR: no Debian rootfs, and nothing to unpack."
    say "  Fix it once, then this runs on its own:"
    say "    adb push <debian13-arm64.tar.xz> /sdcard/Download/"
    say "    adb push 00-bootstrap.sh /data/local/tmp/"
    say "    adb shell \"su -c 'sh /data/local/tmp/00-bootstrap.sh /sdcard/Download/debian13-arm64.tar.xz'\""
    say "  See docs/15-quickstart.md."
    exit 1
  fi
fi

[ -d "$R" ] || { say "ERROR: chroot missing at $R"; exit 1; }

# --- resource limits -----------------------------------------------------
# SOURCED, not executed: cgroups are inherited from the process that spawns a
# child, so this has to run in THIS shell for sshd/rsyslog and every
# session they later fork to land inside the limits. Executing it separately
# would move a throwaway shell into the group and leave the services unlimited.
# Caps: memory 6144 MB hard (swap.max=0), CPU confined to cores 0-3 because the
# kernel lacks CONFIG_CFS_BANDWIDTH and no quota exists. Override per-run with
# DEBIAN_MEM_MB / DEBIAN_CPUS.
[ -f /data/local/tmp/debian-limits.sh ] && . /data/local/tmp/debian-limits.sh

# pseudo-filesystems
for m in dev proc sys; do
  mkdir -p "$R/$m"
  grep -q " $R/$m " /proc/mounts || mount --bind "/$m" "$R/$m" 2>>"$LOG"
done
mkdir -p "$R/dev/pts" "$R/dev/shm" "$R/run/sshd"
# A real devpts mount is required for PTY allocation (interactive ssh + tmux).
grep -q " $R/dev/pts " /proc/mounts || mount -t devpts devpts "$R/dev/pts" -o gid=5,mode=620 2>>"$LOG"
[ -e "$R/dev/ptmx" ] || ln -sf /dev/pts/ptmx "$R/dev/ptmx" 2>/dev/null
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "$R/etc/resolv.conf"

# --- sshd, inside a private UTS namespace -------------------------------
# /etc/hostname then applies to the server and to every SSH session,
# while Android's own hostname is untouched. -D keeps sshd in the foreground so
# it cannot daemonise its way out of the namespace.
mkdir -p "$R/usr/local/bin"
cat > "$R/usr/local/bin/sshd-ns.sh" <<'EOS'
#!/bin/sh
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
# Writing the proc file is more reliable than relying on the hostname binary.
[ -r /etc/hostname ] && cat /etc/hostname > /proc/sys/kernel/hostname 2>/dev/null
mkdir -p /run/sshd
[ -f /etc/ssh/ssh_host_ed25519_key ] || ssh-keygen -A >/dev/null 2>&1
exec /usr/sbin/sshd -p 1304 -D
EOS
chmod 755 "$R/usr/local/bin/sshd-ns.sh"

if (ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null) | grep -q ':1304'; then
  say "sshd: already listening"
else
  setsid chroot "$R" /usr/bin/unshare -u /usr/local/bin/sshd-ns.sh >> "$LOG" 2>&1 < /dev/null &
  sleep 3
  if (ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null) | grep -q ':1304'; then
    say "sshd: started as $(cat "$R/etc/hostname" 2>/dev/null)"
  else
    say "sshd: FAILED to start"
  fi
fi

# a persistent tmux session, also named after the server
chroot "$R" /bin/sh -c '
  export PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin
  tmux has-session -t server 2>/dev/null || tmux new-session -d -s server
' 2>>"$LOG"

# --- Cloudflare tunnel (remotely-managed) ------------------------------
# No systemd/init in the chroot, so run the connector directly. Routes
# (e.g. ssh -> ssh://localhost:1304) live in the Zero Trust dashboard.
# NB: test the REAL path. /usr/local/bin/cloudflared is a symlink whose target
# is absolute, so it does not resolve when tested from outside the chroot.
if [ -x "$R/usr/bin/cloudflared" ] && [ -s "$R/etc/cloudflared/token" ]; then
  if pgrep -f 'cloudflared.*tunnel' >/dev/null 2>&1; then
    say "cloudflared: already running"
  else
    # Read the token on the HOST side. The chroot's /bin/sh inherits Android's
    # PATH, where `cat` does not exist, so $(cat ...) inside the chroot is empty
    # and cloudflared would start with no token at all.
    CF_TOKEN=$(cat "$R/etc/cloudflared/token")
    setsid chroot "$R" /usr/bin/cloudflared tunnel --no-autoupdate run \
      --token "$CF_TOKEN" >> "$LOG" 2>&1 < /dev/null &
    sleep 5
    say "cloudflared: started pid=$(pgrep -f 'cloudflared.*tunnel' | head -1)"
  fi
fi

# --- syslog: sshd writes its auth log here ------------------------------
# Kept because it is the only record of who logged in, and it is what you read
# when SSH stops working. sshguard used to consume this file; it is no longer
# run at all (see the note below).
if [ -x "$R/usr/sbin/rsyslogd" ]; then
  if pgrep -x rsyslogd >/dev/null 2>&1; then
    say "rsyslog: already running"
  else
    setsid chroot "$R" /usr/sbin/rsyslogd -n >> "$LOG" 2>&1 < /dev/null &
    sleep 3
    say "rsyslog: started pid=$(pgrep -x rsyslogd | head -1)"
  fi
fi

# --- deliberately NOT started: sshguard / fail2ban ----------------------
# This kernel refuses nftables ("Operation not supported") and iptables rule
# changes ("No chain/target/match by that name") even for root, because Android
# 16 enforces its firewall in eBPF. A brute-force blocker on the device is
# therefore impossible, not merely difficult, and running one only produces a
# log of attacks it could not stop. Put that protection in front of port 1304
# instead - at your router, or wherever your remote-access tunnel terminates.

# --- default password, so a first login is possible ----------------------
# Freshly built rootfs images have root locked (no password), which means SSH is
# password-less-reachable from nowhere and new users hit "Permission denied" with
# no idea why. Set a documented default once, and only if root has no password
# yet, so a password you set yourself is never overwritten.
#
# CHANGE IT. It is published, so it is not a secret:
#   su -c '/data/local/tmp/debian -c "echo root:NEWPASS | chpasswd"'
DEF_PW=root
if [ -x "$R/usr/sbin/chpasswd" ] || [ -x "$R/usr/bin/chpasswd" ]; then
  SHADOW_LINE=$(grep '^root:' "$R/etc/shadow" 2>/dev/null | cut -d: -f2)
  case "$SHADOW_LINE" in
    ''|\!*|\**)
      printf 'root:%s\n' "$DEF_PW" | chroot "$R" /usr/sbin/chpasswd 2>>"$LOG"
      say "root password was unset; set to the documented default '$DEF_PW' (change it)"
      ;;
    *)
      say "root password already set; leaving it alone"
      ;;
  esac
fi

# verify
if (ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null) | grep -q ":1304"; then
  say "RESULT: debian sshd listening on 1304"
else
  say "RESULT: sshd NOT listening"
fi
