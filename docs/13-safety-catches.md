# 13, Safety catches: the 60-second settle window

The app's flow is fully automatic: boot -> Shizuku -> exploit -> KernelSU late-load -> 
OTA lockdown -> Debian. That is the point of it. But it also means **a mistake in the
flow repeats on every reboot**, with no window to intervene, and root restoration is
the one thing that cannot be fixed from a phone that is already misbehaving.

So there are three independent brakes, plus deliberately wasted time.

## The settle window

After a reboot the app starts its foreground service immediately (Android requires a
running service for the work), but **touches nothing for 60 seconds**:

```
--- service start ---
settling 60s before touching anything
 (stop it: turn off autostart in the app, create the pause file, or kill the app)
 settle 15s/60s
 settle 30s/60s
 settle 45s/60s
settle done - starting the root flow
```

`RootService.SETTLE_SECONDS = 60`. That window is the whole point: it is the time in
which to notice something is wrong and stop it.

## The three brakes

| Brake | How | Survives a service restart |
|---|---|---|
| **UI toggle** | "Root + start Debian automatically" switch in the app | yes (SharedPreferences) |
| **Pause file** | `touch files/autoroot.pause` in the app's private dir | yes (a file) |
| **Force-stop** | kill the app | no, see below |

```sh
# from a PC
adb shell run-as com.dsh.autoroot touch files/autoroot.pause # pause
adb shell run-as com.dsh.autoroot rm files/autoroot.pause # resume
```

### Why a toggle and not just a delay

A delay alone is not a brake. `RootService` is `START_STICKY`, so Android can restart
the service after it is killed, a kill during the settle window would simply be
resumed. The toggle and the pause file are **persistent state**, so they survive
that restart, and they are the actual brakes. The delay exists only to give time to
set one of them.

### Why both are checked during the wait, not only at the start

Both conditions are re-tested **every second** of the settle window, so turning
autostart off (or creating the pause file) 40 seconds in aborts the flow that is
already underway, rather than only affecting the next boot:

```kotlin
for (i in 1..SETTLE_SECONDS) {
 Thread.sleep(1_000)
 if (isPaused()) { aborted = true; break }
 if (!autoStartEnabled(applicationContext)) { aborted = true; break }
}
```

A manual "Run now" from the dashboard goes through the same checks, so a paused
phone stays paused until it is deliberately resumed.

## The price

Root takes ~76-90 s after a reboot instead of ~20 s, and the first ~60 s of that is
purely this window. That is the trade: an unattended recovery you can stop, versus a
fast one you cannot.
