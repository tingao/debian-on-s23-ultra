#!/system/bin/sh
# Install + start a real SSH server inside the Debian chroot, and wire it to
# auto-start every time root is re-established (KernelSU late-load).
ROOT=/data/local/chroot/debian
PORT=1304

echo "=== 1. mount pseudo-filesystems ==="
for m in dev proc sys; do
  mkdir -p "$ROOT/$m"
  grep -q " $ROOT/$m " /proc/mounts || mount --bind "/$m" "$ROOT/$m"
  echo "   /$m -> $([ -d "$ROOT/$m" ] && echo ok)"
done
mkdir -p "$ROOT/dev/pts" "$ROOT/dev/shm" "$ROOT/run/sshd" "$ROOT/root/.ssh"
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "$ROOT/etc/resolv.conf"

echo
echo "=== 2. sshd configuration ==="
mkdir -p "$ROOT/etc/ssh/sshd_config.d"
cat > "$ROOT/etc/ssh/sshd_config.d/99-droid.conf" <<EOF
Port $PORT
ListenAddress 0.0.0.0
PermitRootLogin yes
PasswordAuthentication yes
PubkeyAuthentication yes
UsePAM no
X11Forwarding no
AllowTcpForwarding yes
EOF
cat "$ROOT/etc/ssh/sshd_config.d/99-droid.conf"
chmod 700 "$ROOT/root/.ssh"
chmod 600 "$ROOT/root/.ssh/authorized_keys" 2>/dev/null

echo
echo "=== 3. generate host keys ==="
chroot "$ROOT" /bin/sh -c 'export PATH=/usr/sbin:/usr/bin:/sbin:/bin; ssh-keygen -A 2>&1 | tail -3; ls -la /etc/ssh/ssh_host_* 2>/dev/null | head -4'

echo
echo "=== 4. start sshd ==="
chroot "$ROOT" /bin/sh -c "export PATH=/usr/sbin:/usr/bin:/sbin:/bin; mkdir -p /run/sshd; pkill -x sshd 2>/dev/null; sleep 1; /usr/sbin/sshd -p $PORT && echo 'sshd started'"
sleep 2
echo "--- listening sockets ---"
chroot "$ROOT" /bin/sh -c 'export PATH=/usr/sbin:/usr/bin:/sbin:/bin; (ss -ltnp 2>/dev/null || netstat -ltnp 2>/dev/null) | head -8'
echo "--- sshd processes ---"
chroot "$ROOT" /bin/sh -c 'pgrep -a sshd | head -3'

echo
echo "=== 5. autostart hook: KernelSU module service scripts ==="
# The ota_lockdown module's service.sh runs on every late-load; add a Debian
# server launcher beside it so it starts whenever root comes back.
cat > /data/adb/service.d/debian-server.sh <<'EOS'
#!/system/bin/sh
# Starts the Debian SSH server once root is established.
i=0
while [ "$i" -lt 90 ] && [ "$(getprop sys.boot_completed)" != "1" ]; do i=$((i+1)); sleep 2; done
[ -x /data/local/tmp/debian-server.sh ] && sh /data/local/tmp/debian-server.sh >> /data/local/tmp/debian-server.log 2>&1
EOS
chmod 755 /data/adb/service.d/debian-server.sh
chown root:root /data/adb/service.d/debian-server.sh
ls -la /data/adb/service.d/

echo
echo "=== 6. also start it from the ota_lockdown module ==="
ls -la /data/local/tmp/debian-server.sh 2>&1
echo DONE-SSHD
