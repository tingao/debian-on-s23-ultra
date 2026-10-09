#!/system/bin/sh
# Enter the Debian chroot:   su -c /data/local/tmp/debian
#   or run one command:     su -c '/data/local/tmp/debian -c "apt update"'
#
# Droidspaces is impossible on this kernel (no PID_NS/IPC_NS/USER_NS) and the
# bootloader is locked, so this uses chroot, which needs no namespaces.
# A private UTS namespace (CONFIG_UTS_NS is enabled) gives Debian its own
# hostname from /etc/hostname without changing Android's.
ROOT=/data/local/chroot/debian

for m in dev proc sys; do
  mkdir -p "$ROOT/$m"
  grep -q " $ROOT/$m " /proc/mounts || mount --bind "/$m" "$ROOT/$m"
done
mkdir -p "$ROOT/dev/pts" "$ROOT/dev/shm"
# A real devpts mount is needed for PTY allocation (interactive ssh + tmux).
grep -q " $ROOT/dev/pts " /proc/mounts || mount -t devpts devpts "$ROOT/dev/pts" -o gid=5,mode=620 2>/dev/null
[ -e "$ROOT/dev/ptmx" ] || ln -sf /dev/pts/ptmx "$ROOT/dev/ptmx" 2>/dev/null
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "$ROOT/etc/resolv.conf"

# Self-heal the in-chroot entrypoint.
if [ ! -x "$ROOT/usr/local/bin/enter.sh" ]; then
  mkdir -p "$ROOT/usr/local/bin"
  cat > "$ROOT/usr/local/bin/enter.sh" <<'EOS'
#!/bin/sh
# PATH must be set BEFORE calling hostname: the PATH inherited from Android
# has no hostname, and the error would be silently swallowed.
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export HOME=/root TERM="${TERM:-xterm-256color}" LANG=C.UTF-8
hostname "$(cat /etc/hostname 2>/dev/null || echo debian)" 2>/dev/null
cd /root 2>/dev/null || cd /
exec /bin/bash -l "$@"
EOS
  chmod 755 "$ROOT/usr/local/bin/enter.sh"
fi

exec unshare -u chroot "$ROOT" /usr/local/bin/enter.sh "$@"
