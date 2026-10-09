# 07, Dead ends: everything that did not work, and why

This is the most valuable file here. Each item was a real attempt with a measured
result. Do not retry these without new information.

---

## 1. Container runtimes, impossible

### Droidspaces

```
$ droidspaces check
[✓] Root privileges
[✗] PID namespace
[✗] IPC namespace
```

```
$ zcat /proc/config.gz | grep -E 'CONFIG_(PID_NS|IPC_NS|USER_NS) '
# CONFIG_USER_NS is not set
# CONFIG_PID_NS is not set
```

The app installs and runs, and its KernelSU module even loads, but it cannot create a
container without `PID_NS`. Compile-time config, and the **locked bootloader** means no
custom kernel can ever be flashed. **Permanent.**

### LXC / `lxc-android` / Docker

Same requirement, same answer. Docker additionally needs device cgroups **and an ext4
backing filesystem**; `/data` here is **f2fs**.

### `Ubuntu-Chroot` (by the Droidspaces author)

Its headline feature is namespace isolation (`mount, PID, UTS, IPC`). From its own
README, the kernel requirements include:

```
CONFIG_NAMESPACES=y CONFIG_PID_NS=y CONFIG_UTS_NS=y
CONFIG_MNT_NS=y CONFIG_IPC_NS=y CONFIG_SYSVIPC=y CONFIG_CGROUPS=y
```

We have **only `CONFIG_UTS_NS`**. It also requires an **unlocked bootloader**. Its
Docker feature needs device cgroups and ext4. **Unusable.**

### The Droidspaces author's other repositories, checked, nothing applies

`ravindu644`: `VirtualAP` (needs hostapd + unlocked bootloader), `adb-root-enabler`
(a KernelSU module, so it needs root already, circular), `Kitchen`, `dt-tools`,
`Android_Image_Tools`, `LKM_Tools`, `android-partition-tools` (kernel/image tooling, 
only useful when flashing, which the locked bootloader rules out).

**`chroot` is the answer**, and it needs no namespaces at all.

---

## 2. A single self-rooting app, impossible

Every claim here was **measured**, not assumed. Run in the app sandbox (`run-as`,
`u:r:runas_app:s0`, uid 10374):

```
read tracing_on : Permission denied write tracing_on : WRITE DENIED
write enable : WRITE DENIED list per_cpu : Permission denied
read raw ring : READ DENIED /data/local/tmp : WRITE DENIED
exec own data dir : EXEC DENIED
```

The exploit's KASLR step must **write** `/sys/kernel/tracing/tracing_on`, read the
per-CPU trace rings, and create `/data/local/tmp/.oss-clone-trace-io-<pid>`. An app
SELinux domain may do **none** of these, not even read tracefs.

**No Android app SELinux domain is permitted to write tracefs**, deliberately, because
it is a kernel-info-leak vector. So merging Root My Galaxy's source into ours cannot
help: the privilege is not the apps' to grant.

That is the *entire* reason Root My Galaxy demands Shizuku, Shizuku's job is to
manufacture **shell** context. Verified in its own logs: `runner=shizuku`, and
`Could not connect to Shizuku after 20 seconds` when it is missing.

The app still exists and is fully automatic, but it goes *through* Shizuku.

---

## 3. `sudo` / `su` inside the chroot, impossible

| Attempt | Result |
|---|---|
| `sudo` / `su` on `/data` | `effective uid is not 0, is /usr/bin/sudo on a file system with the 'nosuid' option set?` |
| suid **tmpfs** (`mount -t tmpfs -o suid`), sudo copied in | sudo **executes**, reads the password, then is **SIGKILLed** |
| minimal setuid `id` in that same tmpfs | **no output at all**, killed |
| `/etc/hosts.deny` (sshguard's `hosts` backend) | sshd is **not linked with libwrap** (`libwrap linked: 0`), so it is ignored |
| KernelSU's own `su` | `/system/bin/su` exists only inside its own mount namespace; `/data/adb/ksu/bin/` has `busybox`/`ksud`/`resetprop` but **no `su`** |
| copying `su` into the chroot | binary is unreadable: `stat` reports 5,518,544 bytes, `open` returns *No such file or directory* |

`/data` and `/cache` are mounted `nosuid`:

```
/dev/block/dm-63 /data f2fs rw,...,nosuid,nodev,...
/dev/block/sda33 /cache ext4 rw,...,nosuid,nodev,...
```

The suid-tmpfs escape is **SIGKILLed with zero AVC lines in dmesg**, so it is *not*
SELinux, and no `sepolicy.rule` can fix it. It is Android policing setuid-root
transitions itself.

**Conclusion: a non-root uid cannot escalate inside the chroot.** The login account is
therefore uid 0 with a different *name*.

### The one remaining route, and its real cost

KernelSU's allowlist is a plain binary file, header `USK\x7f`, **784-byte records**,
uid as little-endian at record offset **+256** (confirmed: `68 28 00 00` = 10344 =
Shizuku's uid). `ksud` has **no** command to manage it, so it would be a hand edit.

Downsides, in order of importance:

1. **uid 1000 on Android is `system`.** Adding it grants Android's *system* uid root
 via `su`, not just the chroot login. That widens the hole on the Android side.
2. **It still would not give auditable escalation.** KernelSU's `su` has no password
 prompt and no per-command logging. The gain over today's uid-0 account is
 essentially cosmetic (`id -un` names another user; files owned by 1000).
3. **Private, version-specific format**, one bad write breaks root for all 7 existing
 entries.
4. The `su` binary is not stably readable from the chroot, so it would have to be
 re-copied by the app on every boot.

**Verdict: worst cost/benefit of the options.** If genuine separation is ever wanted,
the only sound route is a small root-owned **socket** service that verifies against
`/etc/shadow`, setuid is impossible, but a daemon is not.

---

## 4. VPN clients inside the chroot, both failed

### Tailscale 1.104.1

Installs, starts, and passes every connectivity pre-check, but the tunnel never
registers:

```
ERR fetch control key: dial tcp [2606:b740:49::105]:443: connect: network is unreachable
```

Root cause: **the phone has no IPv6** (`ipv6 curl -> 000`, no IPv6 default route) and
tailscaled's Go resolver keeps choosing AAAA addresses, while `curl -4` to the *same
host* returns 302. `/etc/gai.conf` does not help, because Go uses its own resolver.

### ZeroTier 1.16.2

Installs cleanly and its key lands in the account, but the daemon is **`Killed`**
immediately on start. Android will not let it run.

**Neither is needed**, the chroot shares the phone's network stack, so sshd on
`0.0.0.0:1304` is directly reachable. WARP routing at the Cloudflare layer does the
access control instead.

---

## 5. Miscellaneous things that looked promising and were not

| Thing | Why not |
|---|---|
| **KernelSU Next "Direct Install"** | Patches `init_boot` in place on a locked bootloader -> **bricks the phone** (`SECURE CHECK FAIL : init_boot (3)`). Recovering needs Odin + stock firmware, and wipes data if the wrong AP is loaded. **Never use it.** |
| **`cloudflared service install`** | Succeeds (falls back to SysV) but there is no init system in a chroot, so the unit never runs. The connector must be started directly. |
| **`cloudflared` quick tunnels** | `--url` tunnels are **HTTP-only** and cannot carry SSH. |
| **`pam_motd` for the MOTD** | sshd runs `UsePAM no` (which was chosen for simplicity), so no PAM session hooks fire. A `profile.d` runner is used instead. |
| **`sshguard` blocking** | `nft` -> `Operation not supported`; `iptables` -> `No chain/target/match by that name`; sshd has no libwrap. Android 16 enforces its firewall in eBPF. Detect-only. |
| **`nft` rules from Android-side root** | Same failure, it is not a chroot limitation. |
| **Hosts blocking via `/system/etc/hosts` editing** | `/system` is read-only EROFS. Bind mount required, plus the SELinux label. |
| **An app launched via `am` from adb to trigger root** | The service is unexported, so adb cannot start it. `run-as <pkg> am start-foreground-service --user 0 ...` works for testing because it runs as the app's own uid. |
