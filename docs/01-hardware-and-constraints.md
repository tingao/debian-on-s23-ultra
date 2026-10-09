# 01, Hardware and the constraints that shape everything

Read this first. Almost every "why is it done that way" question is answered here.

## The device

| | |
|---|---|
| Model | Samsung Galaxy S23 Ultra `SM-S918B` (codename `dm3q`) |
| Android | 16 / One UI 8.5 |
| Build | `BP4A.251205.006.S918BXXSAFZI1` |
| Kernel | `5.15.189-android13-8-33413713-abS918BXXSAFZI1` |
| SoC | Snapdragon 8 Gen 2 (`kalama`), 8 cores |
| Storage | ~460 GB on `/data`, **f2fs** |
| Bootloader | **LOCKED**, `WARRANTY VOID 0x0`, `RP SWREV : B10` |
| Serial | `REDACTED_SERIAL` |

## The four hard constraints

### 1. The bootloader is locked -> nothing persists in the boot chain

We cannot flash a patched `boot`/`init_boot`, so KernelSU **cannot** be installed the
normal way. Root exists only as a **LKM late-load into RAM** and dies at every
reboot. This single fact is why the whole "root yourself automatically" machinery
exists.

The same lock rules out any custom kernel, which kills everything in constraint 2.

### 2. The kernel lacks the namespace features containers need

Measured, not assumed:

```
$ zcat /proc/config.gz | grep -E 'CONFIG_(PID_NS|IPC_NS|USER_NS) '
# CONFIG_USER_NS is not set
# CONFIG_PID_NS is not set
# CONFIG_IPC_NS is not set
```

`CONFIG_UTS_NS` **is** enabled (worth remembering, it is used to give Debian its own
hostname).

Consequence: **no container runtime can work.** Not Droidspaces, not LXC, not Docker,
not Ubuntu-Chroot. All of them need `PID_NS` at minimum. This is confirmed
independently by:

- `droidspaces check` -> `Root privileges`, `PID namespace`, `IPC namespace`
- ravindu644's `Ubuntu-Chroot` README requiring `CONFIG_PID_NS=y`, `CONFIG_IPC_NS=y`,
 `CONFIG_MNT_NS=y`, an **unlocked bootloader**, device cgroups and ext4, we have
 none of those
- Docker additionally needs device cgroups + **ext4**; our `/data` is **f2fs**

The working substitute is a plain **`chroot`**, which needs no namespaces at all.

### 3. `/data` and `/cache` are mounted `nosuid`

```
/dev/block/dm-63 /data f2fs rw,...,nosuid,nodev,...
/dev/block/sda33 /cache ext4 rw,...,nosuid,nodev,...
```

So **every setuid binary inside the chroot is silently ignored.** `sudo`, `su`,
`doas`, `passwd`, all useless. This is why:

- The remote login account is **uid 0** — `root`, or a renamed uid-0 account if you
 avoid a `root` login — instead of a normal user with `sudo`.
- Any attempt to work around it with a suid **tmpfs** gets the process **SIGKILLed**
 by Android (see `docs/07-dead-ends.md`, and note **no AVC is logged**, so it is not
 SELinux and no sepolicy rule fixes it).

### 4. Android 16 enforces its firewall in eBPF -> no local packet filtering

```
nft add table inet sshtest -> Error: Operation not supported
iptables -I INPUT ... -j DROP -> No chain/target/match by that name
```

Even Android-side root cannot change firewall rules. This is why `sshguard` can
**detect and report but not block**, and why real blocking lives at Cloudflare.

## Things that are *not* problems (don't waste time on them)

- **No USB-OTG/gadget limits**, irrelevant here.
- **No `/proc` restrictions**, `/proc/config.gz`, dmesg, `/proc/*/ns/*` all readable.
- **The chroot shares the full network stack.** sshd bound to `0.0.0.0:1304` is
 reachable on the phone's LAN IP directly; no forwarding layer is needed.
- **Hardware access is complete.** The battery, thermals, all CPU freq/sysfs nodes
 are visible from inside the chroot, because it is the same kernel.

## Root method in use

**KernelSU Next 3.4.0 in LKM late-load mode**, driven by the **CVE-2026-43499**
("BOPE") exploit from `soumarcelino/Root-My-Galaxy-SM-S918B`, community port
`S918BXXSAFZI1` (`rafsxd`/Root My Galaxy v0.6.0, `io.github.rootmygalaxy.s23ultra`).

`KernelSU Next "Direct Install" must never be used`, it patches `init_boot` in place
on a locked bootloader and bricks the device:

```
SECURE CHECK FAIL : init_boot (3)
```

Recovery requires Odin + stock firmware (`C:\Program Files\Odin\Odin3 v3.14.1.exe`):
`Re-Partition` unticked, `Nand Erase` unticked, `F.Reset Time` + `Auto Reboot` ticked.
**Never flash `CSC_`** (contains `DM3Q_EUR_OPENX.pit` -> wipes data); use `HOME_CSC`,
which has no PIT.
