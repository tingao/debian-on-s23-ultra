# 04, The Debian server (chroot)

Debian 13 (trixie) aarch64, ~962 packages, 2.7 GB, at `/data/local/chroot/debian`.
It shares the phone's kernel and network stack, so it has full hardware access.

## Why chroot and not a container

The kernel has no `CONFIG_PID_NS` / `CONFIG_IPC_NS` / `CONFIG_USER_NS`, and the
bootloader is locked so no custom kernel can be flashed. `chroot` needs no
namespaces, so it is the only option that works. See
`docs/07-dead-ends.md` for the full list of what was tried.

## Entering it

```sh
su -c /data/local/tmp/debian # interactive
su -c '/data/local/tmp/debian -c "apt update"' # one command
```

`payload/debian-launcher.sh` is the launcher. It bind-mounts `/dev`, `/proc`, `/sys`,
mounts a real `devpts` for PTY allocation, writes `/etc/resolv.conf`, and then:

```sh
exec unshare -u chroot "$ROOT" /usr/local/bin/enter.sh "$@"
```

### The hostname trick

`CONFIG_UTS_NS` **is** enabled, so Debian gets its own hostname without touching
Android's:

- `/etc/hostname` contains `server`
- the launcher runs inside `unshare -u`, and `enter.sh` writes that value to
 `/proc/sys/kernel/hostname`

Verified: `sshd ns=uts:[4026535658]` vs `init ns=uts:[4026531838]`, and Android keeps
reporting `localhost`.

**Two traps here:** `/etc/hostname` must be read *after* `PATH` is set (Android's
PATH has no `hostname`), and writing the proc file directly is more reliable than
calling the `hostname` binary.

## sshd

| | |
|---|---|
| Port | **1304** (matches the user's convention on other hosts) |
| Listen | `0.0.0.0` |
| Root login | allowed **by uid**, see below |
| Allowed users | `root` — the shipped config sets no `AllowUsers` |
| Auth | password **and** key |
| `UsePAM` | `no` |

Config: `/etc/ssh/sshd_config.d/99-droid.conf`. Background on uid-0 logins and why
`PermitRootLogin` is set the way it is: `docs/05-network-and-access.md`.

**sshd runs inside its own UTS namespace**, started by `sshd-ns.sh`, which writes
`/etc/hostname` to the proc file and then `exec`s `sshd -p 1304 -D`. The `-D` matters:
without it sshd **daemonises its way back out of the namespace** and loses the
hostname.

 **Mount-namespace ordering is critical.** sshd ends up with a mount-namespace copy
of whatever existed when it started. Anything mounted *after* sshd starts is
**invisible to SSH sessions**. This cost real debugging time with the suid tmpfs.

## Session environment

`/etc/profile.d/00-server-motd.sh` prints the dynamic MOTD for interactive shells
(this chroot has no systemd and `UsePAM no` rules out `pam_motd`). See
`docs/04b-motd.md`.

`tmux` runs a persistent session named `server`, so there is always an interactive
Debian available without SSH.

## Services started by `debian-start.sh`

Called after every root re-establishment. Idempotent, safe to run repeatedly.

| Service | Why |
|---|---|
| **sshd** on 1304 | the point of the whole thing |
| **rsyslogd** | sshd's `auth.log` destination (sshguard reads it) |
| sshguard | **not started at all.** It cannot block on this kernel; see below. |
| **tmux** `server` | always-available shell |

Plus, separately, `ota_lockdown`'s **watchdog**, which re-asserts the OTA lockdown and
restarts sshd and rsyslog if they die.

### Two PATH traps inside that script

1. **`$(cat ...)` inside the chroot returns nothing.** `chroot "$R" /bin/sh -c '...'`
 inherits *Android's* PATH, where `cat` does not exist, so anything read that way comes
 back empty and the service silently starts with nothing. Fix: read such values on the
 **host** side and pass them in.
2. **Testing a symlink from outside the chroot is misleading.** `[ -x "$R/usr/local/bin/x" ]`
 is false when `x` is a symlink whose target is absolute, because that target does not
 resolve on the host. Test the real path under `$R` instead.

## Logging and sshguard

`rsyslog` is installed so sshd's auth messages land in `/var/log/auth.log`, which is
what sshguard reads (`LOGREADER="LANG=C tail -F -n 0 /var/log/auth.log"`).

 **The Android kernel is extremely chatty**, and rsyslog was writing **~89,000
kernel lines / 24 MB per hour** to `syslog` and `kern.log`. Fixed with:

```
# /etc/rsyslog.d/00-drop-kernel-spam.conf
kern.* stop
```

The file must sort before Debian's RULES section, it does, because
`$IncludeConfig /etc/rsyslog.d/*.conf` appears *before* the rules in
`/etc/rsyslog.conf`. `authpriv` is untouched, so `auth.log` still works. Result:
`/var/log` went from **27 MB -> 780 KB**, with zero growth.

### There is no brute-force protection, on purpose

The setup deliberately does not run sshguard or fail2ban. The section below is what
was tried and why it was dropped.

```
nft add table inet sshtest -> Error: Operation not supported
iptables -I INPUT ... -j DROP -> No chain/target/match by that name
sshd libwrap -> 0 (so /etc/hosts.deny is ignored too)
```

Android 16 enforces its firewall in eBPF and refuses rule changes even for
Android-side root. A custom backend was written that attempts the block and otherwise records
`WOULD-BLOCK <ip>`. It was removed, because a detector that can never act is just a
log of attacks it did not stop. If you want this protection, put it in front of port
1304 — whatever terminates your remote access can usually drop traffic properly.

**Its backend protocol arrives on stdin**, `block|release|flush <ip> <type> <cidr>`.
A backend that reads `$1`/`$2` instead exits immediately, SIGPIPEs the pipeline and
takes the whole wrapper down. That mistake was made once already.

## The login account

`root`, uid **0**, gid 0, home `/root`, password set during bootstrap. That is what the
bootstrap creates and there is nothing else to know.

**If you have renamed it** to keep `root@` off the remote surface, the replacement is a
uid-0 account under a different name with the same key installed — the full reasoning is
in `docs/05-network-and-access.md`. The short version is that `nosuid` on `/data` makes
`sudo`/`su` impossible, so uid 0 under another *name* is the only way to keep root
powers while refusing the `root` login.
