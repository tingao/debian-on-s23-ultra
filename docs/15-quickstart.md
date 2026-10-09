# 15. Quickstart: one app, one tap

This is the short path from a stock phone to a running Debian server. You need the
right model on the right firmware, because the exploit is calibrated to one kernel
build and there is no way around that.

Everything else is in one APK.

## Before you start

- A **Galaxy S23 Ultra `SM-S918B`** on build **`BP4A.251205.006.S918BXXSAFZI1`**
- **Developer options and USB debugging** enabled, with `adb` working on your
  computer (`adb devices` shows the phone as `device`, not `unauthorized`)
- About 4 GB free on the phone, for the Debian rootfs
- The phone **cool**, ideally under 40 C. The exploit refuses to run on a hot phone
  and will simply wait, which looks like a hang if you do not know about it.

The APK carries the exploit payloads, Shizuku, the device-side scripts and the
chroot bootstrap. The only thing it fetches at runtime is a Debian rootfs, about
86 MB.

## The whole procedure

### 1. Install the app

```sh
adb install -r -g AutoRoot.apk
```

Open it. The top of the screen is a **SET UP THIS PHONE** list showing what is
missing, one line per step. On a fresh phone the first three will be red.

### 2. Tap "Install Shizuku"

Shizuku is bundled inside this app, so there is nothing to download. Tapping the
button opens the system installer for it; accept the prompt.

The app cannot do this step silently. Installing an app always requires a user tap,
and without Shizuku there is no shell context, and without a shell context the
exploit cannot run at all. That is the one unavoidable interaction.

### 3. Start Shizuku once

You have two options here. One needs a computer for about thirty seconds; the other
needs nothing but the phone.

**Without a computer.** Shizuku's server has to run as the *shell* user, and normally
only `adb` can arrange that. Android 11 and later can pair with itself over Wireless
debugging, so the app can do it:

1. Developer options > **Wireless debugging** > turn it on
2. Tap **Pair device with pairing code**. Note the six digit code.
3. In the app, under SET UP THIS PHONE, type that code into the pairing row and tap
   **Pair and start Shizuku**.

The port is not asked for. Android advertises the pairing service over mDNS while
that screen is open, and the app reads it from there. Both ports involved are random
and change on every reconnect, so discovering them is the only thing that works.

That is a one time step. Pairing exchanges keys, the app keeps its key in its own
private storage, and the phone remembers it. From then on Shizuku starts itself at
boot and nothing asks for a code again, not after a reboot and not on any later run.
The only things that would make it ask again are clearing the app's data,
uninstalling it, or resetting Wireless debugging on the phone.

Wireless debugging can be turned back off afterwards.

**With a computer**, if you prefer:

```sh
adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
adb shell pm grant moe.shizuku.privileged.api android.permission.WRITE_SECURE_SETTINGS
```

The first command starts it. The second is what lets it restart itself at every boot,
so this is the last time you need the computer. Without that grant Shizuku logs
`No support start on boot` and nothing will bring root back after a reboot. The app
does this grant itself once it has root, so it is only strictly needed if you are
doing everything by hand.

Tap **Set up everything** again. The Shizuku lines should turn green. If it asks for
permission, accept.

### 4. Tap "Set up everything"

That is the whole thing. It walks the list in order:

1. **root** - runs the exploit, then `ksu-helper --late-load`, which is the step that
   actually installs `su`. If the phone is warm it waits for the thermal gate first.
2. **Debian rootfs** - downloads about 86 MB, unpacks it, binds `/dev`, `/proc` and
   `/sys`, sets DNS, configures sshd and starts it on port 1304.
3. **Debian server** - confirms sshd is listening.

It tells you what it is doing as it goes, in the console at the bottom of the
screen, and the list at the top turns green line by line. If a step fails, the
reason is in that console.

The download and the unpack are the slow parts: a few minutes each.

### 5. Log in

Set a password, or better, your key:

```sh
adb shell "su -c '/data/local/tmp/debian -c \"echo root:YOURPASSWORD | chpasswd\"'"
ssh -p 1304 root@<phone-ip>
```

The phone's address is on the app's dashboard. It comes from DHCP, so set a
reservation on your router or it will move.

**The default password is `server`** if you did not set one during bootstrap. It is
published here, so it is not a secret. Change it.

### 6. Reboots look after themselves

Once Shizuku holds that permission and the app is installed, a reboot runs the whole
sequence unattended in about 90 seconds. Nothing needs to be plugged in.

To stop it, open the app and turn autostart off, or create the pause file. There is a
60 second window after every boot in which to do it. See
[docs/13](13-safety-catches.md).

## Doing it by hand instead

If you would rather not use the app, or you are debugging why it failed, the pieces
are all here:

```sh
# 1. root, using the payloads from app/src/main/assets
adb push stability-launcher ksu-helper ksu-payload mm-exec-factory ksud-selected /data/local/tmp/
adb shell "su -c 'cd /data/local/tmp && ./stability-launcher \
    --payload /data/local/tmp/ksu-payload \
    --helper /data/local/tmp/ksu-helper \
    --mm-exec-factory /data/local/tmp/mm-exec-factory > bope.log 2>&1 &'"
# wait for "Root achieved" in bope.log, then:
adb shell "su -c 'cd /data/local/tmp && ./ksu-helper --late-load'"

# 2. the chroot
adb push 00-bootstrap.sh /data/local/tmp/
adb shell "su -c 'sh /data/local/tmp/00-bootstrap.sh /sdcard/Download/debian13-arm64.tar.xz'"

# 3. the whole stack, on every boot
adb shell "su -c 'sh /data/local/tmp/debian-start.sh'"
```

Three things catch everyone here, so they are worth stating plainly:

- **`ksu-helper --late-load` is a flag, not a path.** `ksu-helper <ksud> late-load`
  silently does nothing, and `ku-helper` (one letter short) exits 127.
- **Absolute paths are required.** A relative `--helper` produces
  `[preflight] invalid root helper errno=0`, which reads like a filesystem error and
  is not one.
- **Do not use KernelSU Next "Direct Install".** On a locked bootloader it patches
  `init_boot` in place and bricks the phone with `SECURE CHECK FAIL`. Recovery needs
  Odin and stock firmware.

## If something does not work

The app's console and its log carry the failure modes in the order you are likely to
meet them. The two most common:

- **`gate waiting ... temp=XXC`** - the exploit will not run above 48 C. It is
  working, not stuck. Put the phone somewhere cool.
- **The setup list shows a step red after tapping Set up everything** - read the
  console underneath. It names the step that failed and why.
