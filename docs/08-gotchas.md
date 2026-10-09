# 08, Gotchas: the recurring traps

Every item here cost real debugging time, and most of them bit **more than once**.
If something is inexplicably broken, check this list first.

---

## 1. `$(cat ...)` inside the chroot returns nothing

```sh
chroot "$ROOT" /bin/sh -c 'cat /etc/hostname' # -> cat: not found
```

`chroot` inherits **Android's** PATH, which has no `cat`, `hostname`, `grep`, `head`,
`cp`… The failure is easy to miss when stderr is redirected.

This broke **two** things:

- `hostname` was never set -> Debian kept reporting `localhost`
- the Cloudflare connector started with an **empty token** -> no error, just no tunnel

**Rule: read files that live outside the chroot on the *host* side and pass values in;
inside, `export PATH=...` as the very first line.**

## 2. CRLF from PowerShell breaks shell scripts

```
/etc/update-motd.d/30-sshguard: 19: Syntax error: end of file unexpected (expecting "fi")
/usr/local/lib/motd-geoip.sh: 42: Syntax error: end of file unexpected (expecting "}")
```

PowerShell's `Set-Content` writes `\r\n`. Always:

```sh
sed -i 's/\r$//' <file>
```

## 3. Absolute-target symlinks do not resolve from outside the chroot

```sh
[ -x "$R/usr/local/bin/cloudflared" ] # FALSE
```

That path is a symlink to `/usr/bin/cloudflared`, an *absolute* target, which from
the host means the **host's** `/usr/bin`, where nothing exists. The real file is at
`"$R/usr/bin/cloudflared"`. Test that.

## 4. Mount namespaces: a new one is a *copy*

`sshd` ended up with its own mount namespace, so anything mounted **after** it started
was invisible to every SSH session:

```
init : mnt:[4026532524]
su session : mnt:[4026532524]
sshd (pid 12937) : mnt:[4026535120] <- its own
sshd sees opt/suid entries: 0 init sees: 1
```

A namespace starts as a **snapshot**, so mounting **before** starting sshd fixes it, 
and that is also why restarts matter. Note `unshare -u` alone does **not** split the
mount namespace (verified), so the source of this one is elsewhere; ordering is the
reliable fix.

## 5. sshd's `PermitRootLogin` is evaluated by UID, not name

`PermitRootLogin no` refuses **any** uid-0 account — and the account that holds uid 0
is the one you log in with:

```
sshd-session: ROOT LOGIN REFUSED FROM 192.0.2.20
sshd-session: Connection reset by authenticating user root [preauth]
```

The shipped bootstrap sets `PermitRootLogin yes` and no `AllowUsers`, so `root` is a
normal login. If you block it, `AllowUsers` refuses by **name** before the key is even
considered, so a rejected login there is a name problem, not a bad key.

## 6. An app cannot write `/data/local/tmp`, and cannot overwrite root's files there either

The shell-uid service stops with `EACCES` on any destination previously created by
**root**, even though the shell-owned parent directory *does* permit unlinking it.

Fix: `rm -f` the destination before writing. This silently broke the `debian` launcher
refresh.

## 7. sshguard's backend protocol is on **stdin**

```sh
while read -r cmd address addrtype cidr; do
 case $cmd in block) … release) … flush) … flushonexit) … esac
done
```

It is **not** `$1`/`$2`. A backend that exits immediately SIGPIPEs the pipeline and
takes the entire `sshguard` wrapper down with it, which looked exactly like
"sshguard refuses to start".

## 8. `errno=0` means no syscall failed

```
[preflight] invalid root helper errno=0
```

That is not a filesystem error. It means the launcher never exported
`CVE43499_ROOT_HELPER`, because **the `--helper` path was relative**. Use absolute
paths.

## 9. `-Include` needs a wildcard path in PowerShell

```powershell
Get-ChildItem $dir -File -Include '*.sh' # returns NOTHING
Get-ChildItem "$dir\*.sh" -File # works
```

## 10. PowerShell mangles `$()`, `|`, `&&` and `>` in device strings

A device-side `$(...)` is interpreted by PowerShell as a subexpression; `|` inside a
quoted adb command breaks parsing; `> /dev/null` gets treated as a file redirect. A
single parse error also means **the whole command never runs**, which once silently
skipped a deployment I thought had happened.

**Rule: put anything non-trivial in a device-side script file and run that.**

## 11. A running shell script does not pick up edits to itself

The watchdog loop was `sed`-fixed on disk from port 2222 -> 1304, but the **running**
process had already parsed its `while` body, so it kept checking 2222 and logged
**232 false alarms** overnight. Restart the process, not just the file.

## 12. Symlinked launchers and stale PIDs

`pidof sshguard` returns nothing because the wrapper is a **shell script** (its comm
is `sh`). Check for the real binary: `sshpg-blocker`. And a pidfile alone is not proof
, verify the pid is alive **and** is the expected program, or a recycled pid blocks a
legitimate start.

## 13. Debian package names: one bad name aborts the whole transaction

```
E: Unable to locate package neofetch
```

`neofetch` was removed from Debian 13, and that single unknown name meant **apt
installed nothing at all**, no error beyond that line. Install in small independent
groups so one bad name cannot block the rest.

## 14. `dumpsys` output for uninstalled-for-0 packages is binary XML

`/data/system/users/0/package-restrictions.xml` is **ABX**, so `grep` reports
*"Binary file matches"* and shows nothing. Use `grep -a`, or just trust
`dumpsys package <pkg> | grep 'User 0:'`.
