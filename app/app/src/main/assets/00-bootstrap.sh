#!/system/bin/sh
# ============================================================================
#  00-bootstrap.sh - get a Debian 13 chroot running on the phone, from scratch.
#
#  Run this AS ROOT on the phone. The app calls it automatically when it finds a
#  rootfs tarball waiting in /sdcard/Download, so normally you never run it by
#  hand; see docs/15-quickstart.md.
#
#  It does the four things that are easy to get wrong:
#
#    1. lays down a Debian 13 arm64 rootfs at /data/local/chroot/debian
#    2. bind-mounts /dev, /proc and /sys, without which nothing works
#    3. gives the chroot working DNS
#    4. configures sshd on port 1304 and sets a documented default password
#
#  The rootfs: this setup used a prebuilt Debian 13 base image for arm64 (the one
#  Droidspaces publishes as a rootfs tarball, which is a plain Debian rootfs and
#  works fine under a plain chroot). Any Debian 13 arm64 rootfs works, including
#  one you build on a Linux box:
#
#      debootstrap --arch=arm64 --variant=minbase trixie ./rootfs \
#                  http://deb.debian.org/debian
#      tar -cJf debian13-arm64.tar.xz -C rootfs .
#
#  The tarball is NOT in this repo. Point this script at it:
#      su -c 'sh 00-bootstrap.sh /sdcard/Download/debian13-arm64.tar.xz'
# ============================================================================
set -u

ROOT=/data/local/chroot/debian
PORT=1304
TARBALL="${1:-}"
DEFAULT_PASSWORD=root
LOG=/data/local/tmp/bootstrap.log

say() { echo "$*"; echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG"; }

say "=== 0. checks ==="
if [ "$(id -u)" != "0" ]; then
  say "must run as root:  su -c 'sh $0 <tarball>'"
  exit 1
fi

# busybox gives xz and tar without depending on toybox's applet coverage, which
# varies between builds.
BB=""
for c in /data/local/Droidspaces/bin/busybox /data/adb/ksu/bin/busybox /system/bin/busybox; do
  if [ -x "$c" ]; then BB="$c"; break; fi
done
if [ -z "$BB" ]; then
  if command -v xz >/dev/null 2>&1 && command -v tar >/dev/null 2>&1; then
    say "no busybox found, falling back to system xz/tar"
  else
    say "ERROR: need either busybox or system xz and tar"
    exit 1
  fi
fi
say "busybox: ${BB:-none (using system tools)}"

say ""
say "=== 1. rootfs at $ROOT ==="
mkdir -p "$ROOT"
if [ -x "$ROOT/usr/bin/bash" ] || [ -x "$ROOT/bin/bash" ]; then
  say "  already present, leaving it alone"
elif [ -n "$TARBALL" ] && [ -f "$TARBALL" ]; then
  say "  extracting $TARBALL (this takes a few minutes)"
  if [ -n "$BB" ]; then
    if "$BB" xz -dc "$TARBALL" | "$BB" tar -xf - -C "$ROOT"; then :; else
      say "  EXTRACT FAILED"; exit 1
    fi
  else
    if xz -dc "$TARBALL" | tar -xf - -C "$ROOT"; then :; else
      say "  EXTRACT FAILED"; exit 1
    fi
  fi
  say "  extracted, size $(du -sh "$ROOT" 2>/dev/null | cut -f1)"
else
  say "  ERROR: no rootfs present and no tarball given."
  say "         su -c 'sh $0 /sdcard/Download/debian13-arm64.tar.xz'"
  exit 1
fi

say ""
say "=== 2. bind /dev /proc /sys (skip this and nothing works) ==="
for m in dev proc sys; do
  mkdir -p "$ROOT/$m"
  if grep -q " $ROOT/$m " /proc/mounts 2>/dev/null; then
    say "  /$m already bound"
  else
    mount --bind "/$m" "$ROOT/$m" && say "  bound /$m"
  fi
done
mkdir -p "$ROOT/dev/pts" "$ROOT/dev/shm" "$ROOT/run/sshd"
# A real devpts is needed for PTYs, which is what interactive ssh and tmux use.
grep -q " $ROOT/dev/pts " /proc/mounts 2>/dev/null || \
  mount -t devpts devpts "$ROOT/dev/pts" -o gid=5,mode=620
[ -e "$ROOT/dev/ptmx" ] || ln -sf /dev/pts/ptmx "$ROOT/dev/ptmx" 2>/dev/null

say ""
say "=== 3. DNS inside the chroot ==="
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "$ROOT/etc/resolv.conf"
say "  wrote /etc/resolv.conf"

say ""
say "=== 4. sanity: does it run? ==="
chroot "$ROOT" /bin/sh -c 'export PATH=/usr/bin:/bin:/usr/sbin:/sbin
  . /etc/os-release 2>/dev/null
  echo "  os  : $PRETTY_NAME"
  echo "  arch: $(uname -m)"
  echo "  bash: $(command -v bash || echo MISSING)"
  echo "  apt : $(command -v apt-get || echo MISSING)"' 2>&1 | sed 's/^/  /'

say ""
say "=== 5. sshd ==="
mkdir -p "$ROOT/etc/ssh/sshd_config.d" "$ROOT/root/.ssh"
chmod 700 "$ROOT/root/.ssh"
cat > "$ROOT/etc/ssh/sshd_config.d/99-chroot.conf" <<EOF
Port $PORT
ListenAddress 0.0.0.0
# Password and key login are both allowed. Replace PermitRootLogin with
# "prohibit-password" once your key works; see docs/05.
PermitRootLogin yes
PasswordAuthentication yes
PubkeyAuthentication yes
UsePAM no
X11Forwarding no
AllowTcpForwarding yes
EOF
say "  wrote sshd config on port $PORT"

# A freshly built image has root locked, which means nobody can log in and the
# error is just "Permission denied". Set a documented default, but only when
# there is no password already, so a real one is never overwritten.
SHADOW_LINE=$(grep '^root:' "$ROOT/etc/shadow" 2>/dev/null | cut -d: -f2)
case "$SHADOW_LINE" in
  ''|!*|\**)
    printf 'root:%s\n' "$DEFAULT_PASSWORD" | chroot "$ROOT" /usr/sbin/chpasswd 2>>"$LOG"
    say "  root password was locked, set to the documented default '$DEFAULT_PASSWORD'"
    say "  CHANGE IT:  su -c '/data/local/tmp/debian -c \"echo root:NEWPASS | chpasswd\"'"
    ;;
  *)
    say "  root already has a password, leaving it alone"
    ;;
esac

chroot "$ROOT" /bin/sh -c 'export PATH=/usr/sbin:/usr/bin:/sbin:/bin; ssh-keygen -A 2>&1 | tail -2' | sed 's/^/  /'
chroot "$ROOT" /bin/sh -c "export PATH=/usr/sbin:/usr/bin:/sbin:/bin
  mkdir -p /run/sshd; pkill -x sshd 2>/dev/null; sleep 1
  /usr/sbin/sshd -p $PORT && echo '  sshd started'" | sed 's/^/  /'
sleep 2
say "  listeners on $PORT: $( (ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null) | grep -c ":$PORT")"

say ""
say "=== done ==="
say "Enter the chroot:  su -c /data/local/tmp/debian"
say "Or over SSH on port $PORT, with that password, then change it."
say "The whole stack on every boot:  su -c 'sh /data/local/tmp/debian-start.sh'"
exit 0
