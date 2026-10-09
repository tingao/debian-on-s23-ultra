# 05, Network access and the login account

## What the server listens on

`sshd` listens on **0.0.0.0:1304**, so it answers on the Wi-Fi address first. Find it
in the app's **SSH access** panel, or:

```sh
adb shell "su -c 'ip -4 addr show wlan0 | grep inet'"
```

```sh
ssh -p 1304 root@<the-ip-shown-in-the-app>
```

That is enough for anything on the same network.

## Reaching it from outside your network

**This project does not ship a remote-access path**, and deliberately so: whatever you
put in front of port 1304 becomes part of your attack surface, and the right choice
depends on your setup. Two common routes:

- **Port forward.** Forward TCP 1304 on your router to the phone's LAN address. Simple,
  and the phone's address is DHCP, so reserve it or the forward will point at nothing.
- **A tunnel.** A WireGuard/Cloudflare/Tailscale-style tunnel avoids opening a port at
  all. This is what the author uses, but it is ordinary third-party software — set it up
  per its own documentation. Nothing in this repo or the app depends on it.

Whichever you pick, **change the root password first** (see the README). The default is
`root`, it is written in this repository, and port 1304 does not care who connects.

## The login account: `root`
## The login account: `root`

```sh
ssh server # ~/.ssh/config alias -> root@192.0.2.10:1304
```

| | |
|---|---|
| Login name | `root` — what the bootstrap creates, and the default |
| Effective uid | **0** |
| Password | the one set during bootstrap (change it) |
| SSH key | a key of your own, installed into the chroot |
| `root` over SSH | **allowed** on a fresh install |

The shipped bootstrap sets `PermitRootLogin yes` and no `AllowUsers`, so `root` is a
normal login name and there is no second account to know about.

### If you have renamed the uid-0 login

Some installs keep `root@` off the remote surface by renaming the uid-0 account and
allowing only that name. Substitute that name for `root` everywhere above; it is the
same account, the same key, the same uid.

**sshd evaluates `PermitRootLogin` by UID, not by name.** With a renamed account and
`AllowUsers` not listing `root`, the rejection looks like this:

```
sshd-session: ROOT LOGIN REFUSED FROM 192.0.2.20
sshd-session: Connection reset by authenticating user <name> [preauth]
User root from 192.0.2.20 not allowed because not listed in AllowUsers
```

That is a deliberate configuration, not a broken key: the key is fine and `root` is
simply not on the allow-list. Add `root` to `AllowUsers` to reverse it.

### Why not a normal user with sudo (tried, impossible)

| Attempt | Result |
|---|---|
| `sudo` / `su` on `/data` | `effective uid is not 0 'nosuid' option set?` |
| suid **tmpfs** for sudo (mounts fine, no `nosuid`) | sudo runs, then **SIGKILLed** |
| minimal setuid `id` in that tmpfs | **no output at all**, killed |
| KernelSU's own `su` | only exists inside its own mount namespace; `ksu/bin/` ships `busybox`/`ksud`/`resetprop` but no `su`; uid 1000 is not in `/data/adb/ksu/.allowlist` |
| `su` after copying the binary in | binary is unreadable from the chroot (`stat` says 5,518,544 bytes, `open` says *No such file or directory*) |

`/data` and `/cache` are both `nosuid`, and the suid-tmpfs escape hatch is killed by
Android with **no AVC logged**, so it is not SELinux and no sepolicy rule fixes it.

**Conclusion: a non-root uid cannot escalate inside the chroot on this platform.**
That is why the alternative is uid 0 under a different name; the *name* is the gate.

That provides a different login name, **not privilege separation**. There is no
password prompt and nothing to audit. If real separation is ever wanted, the only
route left is adding uid 1000 to KernelSU's allowlist and copying its `su` in, see
`docs/07-dead-ends.md` for the cost/benefit of that.

## Client configuration

`~/.ssh/config` on the machine you connect from:

```
Host server
 HostName <the-ip-shown-in-the-app>
 User root
 Port 1304
 IdentityFile ~/.ssh/debian_key
 StrictHostKeyChecking accept-new
 ServerAliveInterval 30
```

The key path is yours: generate one with `ssh-keygen`, put the public half in
`/root/.ssh/authorized_keys` inside the chroot, and point `IdentityFile` at the private
half. There is nothing to copy out of this repository.

## Tunnels inside the chroot that do not work

Recorded so nobody spends the time again:

- **Tailscale inside the chroot.** Installed and started, but the phone has **no
  IPv6** (`ipv6 curl -> 000`, no IPv6 default route) and tailscaled's Go resolver keeps
  choosing AAAA addresses, so it reported `network is unreachable` while `curl -4` to
  the same host returned 302. `/etc/gai.conf` does not help because Go uses its own
  resolver.
- **ZeroTier inside the chroot.** Installed cleanly (1.16.2), but the daemon is
  **`Killed`** the moment it starts.

Neither is needed from inside: the chroot shares the phone's network stack, so sshd on
`0.0.0.0:1304` is directly reachable on the LAN, and a tunnel can run on whatever
machine already terminates your remote access.
