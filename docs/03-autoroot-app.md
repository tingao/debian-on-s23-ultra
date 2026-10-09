# 03, The AutoRoot app

`com.dsh.autoroot`, one app that roots the phone, re-applies the OTA lockdown and
starts the Debian server, with no PC and no taps.

Source: `app/` (build it yourself, see docs/15)

## Why it needs Shizuku at all

An app **cannot** run this exploit in its own context. Measured in the app sandbox
(`run-as`, `u:r:runas_app:s0`, uid 10374):

```
read tracing_on : Permission denied write tracing_on : WRITE DENIED
write enable : WRITE DENIED list per_cpu : Permission denied
read raw ring : READ DENIED /data/local/tmp : WRITE DENIED
exec own data dir : EXEC DENIED
```

The exploit's KASLR step must **write** `/sys/kernel/tracing/tracing_on` and read the
per-CPU trace rings; it also writes `/data/local/tmp/.oss-clone-trace-io-<pid>`. No
Android app SELinux domain may write tracefs, deliberately, since it is a
kernel-info-leak vector.

So the missing privilege is **shell** context, and that is precisely what Shizuku
manufactures. Merging Root My Galaxy's source into ours would not help: the
privilege is not the apps' to grant.

## The chain

```
BOOT_COMPLETED
 -> BootReceiver (waits for Shizuku; it self-starts)
 -> RootService (foreground service, wake lock)
 -> Shizuku UserService (a process running as uid 2000 = shell)
 -> extract payloads (unzip our own APK in shell context)
 -> thermal gate (waits for <=48C)
 -> stability-launcher (the exploit)
 -> ksu-helper --late-load (installs su)
 -> su -c 'ksud services' (fires the KernelSU module hooks)
 -> lockdown.sh (OTA lockdown)
 -> debian-start.sh (sshd, rsyslog, sshguard, tmux)
```

## Why a Shizuku *UserService* and not `newProcess`

Shizuku 13.1.5's `newProcess` is **private**. The supported way to run code as shell
is a UserService: an AIDL interface plus a class that Shizuku starts in its own
process with shell uid.

- `app/src/main/aidl/com/dsh/autoroot/IUserService.aidl`
- `app/src/main/java/com/dsh/autoroot/UserService.java`
- bound from `Sh.kt` via `Shizuku.bindUserService(...)`

The AIDL surface is deliberately tiny: `run(cmd)`, `extractAll(apkPath)`, `isRooted()`,
`uid()`, `destroy()`, `exit()`.

### Staging without transferring megabytes

`extractAll` opens our own APK with `java.util.zip.ZipFile` and writes each asset to
`/data/local/tmp`. The APK is world-readable, so shell can open it, and this avoids
pushing ~4.5 MB through a binder transaction.

Two details that matter:

- **Unlink before writing.** A destination previously created by *root* cannot be
 truncated by the shell-uid service (`EACCES`), even though the shell-owned parent
 directory permits unlinking it. So: `rm -f` first. This bug silently broke the
 `debian` launcher refresh.
- `.debuggable(...)` in `UserServiceArgs` must match the APK's debuggable flag or
 Shizuku refuses to start the service.

## The status dashboard

Tapping the app shows a live per-component view (green/amber/red), not just a log:

```
Shizuku service running
Shizuku permission granted
Shell context user service bound (uid 2000)
Root uid=0 u:r:ksu:s0
KernelSU driver loaded
FOTA clients blocked 3/3
Hosts blackholed 51 domains
Staged OTA files 0
Watchdog yes
Debian SSH server listening on 1304
Wi-Fi address 192.0.2.10
Temperature 48 C (gate needs <= 48 C)
```

Buttons, and why they are not always there:

- **Set up everything** — runs the missing steps in order. Always present.
- **Install Shizuku** — only while Shizuku is not installed. It used to sit on screen
  on a phone that already had it, so pressing it looked like a button that did nothing.
- **Get Debian** — only while the rootfs is not unpacked, for the same reason.
- **Refresh** — re-runs the checks and redraws the card.
- **Grant Shizuku** — appears in Components while Shizuku is installed but the app has
  not been allowed yet.
- **Copy SSH command** — puts the `ssh` line on the clipboard.

Under **Set up this phone** there is also a strip pinned above the scrolling area. It
takes the six digit pairing code by hand, and it stays put when the page scrolls.

**Open Wireless debugging** is the one button worth knowing about. It opens Developer
options already scrolled to the Wireless debugging entry, and starts the pairing
notification at the same time, so the code box is already waiting in the shade when
Settings shows you the code. That combination is the whole no-computer path: the code
is drawn by Settings, in another app, and a notification with an inline reply is the
only input that can float over it.

Below that is a **Debian console**, type a command, it runs inside the chroot as
root and prints the output. That is a shell into Debian without SSH:

```
$ hostname; id -u; head -1 /etc/os-release
server
0
PRETTY_NAME="Debian GNU/Linux 13 (trixie)"
```

It base64-encodes the command because `chroot` needs `CAP_SYS_CHROOT` (so it must go
through `su`) and base64 sidesteps every shell-quoting problem.

## Build instructions

```powershell
# toolchain: JDK 21 + Android SDK (platform 35, build-tools 35) + Gradle 8.11.1
$env:JAVA_HOME = '<jdk21>'
$env:ANDROID_HOME = '<android-sdk>'
gradle -p app assembleDebug
adb install -r -g app\app\build\outputs\apk\debug\app-debug.apk
```

`app/app/build.gradle.kts` needs `buildFeatures { aidl = true }`. The launcher icon is
generated by `scripts/make-icon.py` (crowned Android on a purple -> cyan gradient,
adaptive + legacy, 5 densities).

## The state the app depends on

- **Shizuku must be running.** It self-starts at boot because
 `WRITE_SECURE_SETTINGS` is granted to it, and its ADB key `shizuku` is already
 authorised in `/data/misc/adb/adb_keys`, so on *this* unit no WiFi-ADB pairing code
 is needed. That is a property of the already-provisioned phone, not of the app: a
 fresh install has neither the grant nor the authorised key, so the app carries a
 full first-run pairing flow (see above) to pair with the phone's own adb and start
 Shizuku without a computer. If the grant is lost, Shizuku logs
 `No support start on boot` and nothing recovers root.
- **Shizuku must allow our app.** Its allow-list is
 `/data/user_de/0/com.android.shell/shizuku.json`:
 ```json
 {"version":2,"packages":[
 {"uid":10374,"flags":2,"packages":["io.github.rootmygalaxy.s23ultra"]},
 {"uid":10348,"flags":2,"packages":["com.dsh.autoroot"]}]}
 ```
 Adding a uid there plus restarting Shizuku's server is enough. It is one JSON line and a
`su -c` restart; the author's helper scripts for it were machine-specific and are not
published.
