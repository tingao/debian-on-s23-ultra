# 06, OTA lockdown (stopping Samsung's automatic updates)

> "i connected a samsung rooted on this computer … lets make it so the OS wont be
> able to auto update, i run things in here I need to not break with updates"

## The problem

Samsung FOTA can install a system update that replaces the kernel, which would
break the exploit (it is calibrated to `S918BXXSAFZI1`) and possibly the whole setup.
It must not happen.

## Defence in depth (four layers)

| # | Layer | Persists across reboot |
|---|---|---|
| 1 | FOTA client packages **uninstalled for user 0** | yes, in `/data` |
| 2 | Same packages **disabled** | yes |
| 3 | OTA + Samsung hostnames **blackholed** via a bind-mounted `hosts` | needs root |
| 4 | Staging dirs **cleared and locked** `0700 root` | perms persist |
| 5 | **Watchdog** re-asserting everything every 60 s | needs root |

Plus `settings put global ota_disable_automatic_update 1`.

### The packages

```
com.wssyncmldm FotaAgent
com.sec.android.soagent SOAgent77
com.samsung.android.sdm.config SDMConfig
```

### The hostnames

51 domains are redirected to `0.0.0.0` from `payload/hosts.block`, the Samsung
FOTA/DM endpoints plus a wider set of Samsung services. Verified without root:

```
$ ping -c1 fota-cloud-dn.ospserver.net
PING fota-cloud-dn.ospserver.net (127.0.0.1) 56(84) bytes of data.
```

`/system` is read-only EROFS, so this must be a **bind mount**:

```sh
mount -o bind /data/local/tmp/ota_lockdown/hosts.live /system/etc/hosts
```

 **The file needs the SELinux label `u:object_r:system_file:s0`**, or the resolver
silently ignores the mount. `chcon` is in `lockdown.sh` for that reason.

The bind mount is **global** (`/proc/self/ns/mnt` == `/proc/1/ns/mnt`), so it affects
Android's own DNS resolution, which is the point.

## What survives a reboot (measured)

A real reboot was performed and checked. Survived **with no action at all**:

```
com.wssyncmldm installed=false enabled=3
com.sec.android.soagent installed=false enabled=3
com.samsung.android.sdm.config installed=false enabled=3
appearances in installed list: 0
ota_disable_automatic_update : 1
```

Lost on reboot (root-only, restored by the app): the hosts bind mount, the watchdog,
and the staging-dir lock enforcement.

Also survived: the payload directory, the KernelSU module, `/data/adb/service.d`,
and the whole Debian chroot.

## The uninstall layer is not tamper-proof

Testing found that **an unprivileged shell can undo it**:

```
$ pm install-existing --user 0 com.wssyncmldm
Package com.wssyncmldm installed for user: 0
state now: installed=true enabled=3
```

So `installed=false` is not protection against an attacker with adb, only against
the OS doing it to itself, which is the actual threat. Likewise `pm enable` flips the
disabled flag.

**This is exactly why the hosts block matters.** If the FOTA client is ever restored,
the endpoints are still blackholed. The layers cover each other's weaknesses rather
than being redundant.

The watchdog was strengthened after that discovery: it now checks for **both**
conditions on every pass and re-uninstalls *and* re-disables, so a restored package
is caught (v1 only re-disabled).

## Files

| File | Purpose |
|---|---|
| `payload/hosts.block` | the 51-domain blacklist |
| `payload/lockdown.sh` | idempotent: disable, lock staging, mount hosts, start watchdog |
| `payload/watchdog2.sh` | 60 s loop re-asserting everything |
| `payload/status.sh` | prints the current state (this is what the app's dashboard reads) |
| `app/app/src/main/assets/*` | the same files, shipped inside the APK |

`status.sh` counts a FOTA client as blocked if `installed=false` **or** `enabled` is
2/3/4, so it cannot be fooled by one layer alone. It emits
`fota_blocked=`, `hosts_blocked=`, `staging_files=`, `watchdog=`, `root=`, `dns=`,
`staged_ota=`.

## The incident that started this

A **537 MB `update.zip`** was already staged at `/data/fota/update.zip` when work
began. It was deleted:

```
SHA-256 3185ecdf96153ba27f9491f28b3247418699a7a48ac0e4ee61200c45eb763df5
```

If updating is ever deliberately wanted, that is the file to look for.

## Verification

```sh
su -c 'sh /data/local/tmp/ota_lockdown/status.sh'
```

Expected:

```
fota_blocked=3/3
hosts_blocked=51
staging_files=0
watchdog=yes
root=0
dns=PING fota-cloud-dn.ospserver.net (127.0.0.1) 56(84) bytes of data.
staged_ota=0
```

The two bugs found along the way, and how each claim was tested, are recorded in the
lockdown scripts themselves.
