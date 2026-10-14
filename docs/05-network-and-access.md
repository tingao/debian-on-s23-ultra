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
  all. Nothing in this repo or the app depends on it. Cloudflare Tunnel is written up
  [below](#setting-up-a-cloudflare-tunnel) because it is the one that has been run against
  this setup, so its traps are known rather than guessed.

Whichever you pick, **change the root password first** (see the README). The default is
`root`, it is written in this repository, and port 1304 does not care who connects.

## Setting up a Cloudflare Tunnel

You need a domain whose DNS is on Cloudflare. The free plan is enough.

**1. Create the tunnel.** In the [Zero Trust dashboard](https://one.dash.cloudflare.com), go
to Networks then Tunnels, create a tunnel, and choose `cloudflared` as the connector. Give it
any name you like. It then shows an install command containing a long token.

**2. Put the token on the phone.** The app reads it from this path inside the chroot:

```sh
/data/local/chroot/debian/etc/cloudflared/token
```

The file holds the token and nothing else. Restrict it:

```sh
adb shell su -c 'chmod 600 /data/local/chroot/debian/etc/cloudflared/token'
```

That file is the only place it goes. `debian-start.sh` starts the connector on every boot
when it is present and non-empty, so the tunnel comes back with the rest of the stack and
there is no manual step.

**A tunnel token and a Cloudflare API token are different credentials, and only the tunnel
token belongs on the phone.** This trips people up because both are long strings issued by
Cloudflare. An API token is for driving Cloudflare from a script, Terraform, or the API
itself; if you use one, it lives on the machine running that script.

**3. Route the hostname.** In the tunnel, add a public hostname such as
`phone.example.com`, with service type **SSH** and URL `127.0.0.1:1304`. Localhost is the
right address because the connector runs inside the chroot, which shares the phone's network
stack, so that address is the phone itself.

**4. Put an Access policy in front of it.** Add an Access application for the hostname with a
policy you trust; an email one-time PIN is the simplest. Without it, anyone who learns the
hostname reaches your sshd, and only the root password is in the way.

**5. Client side.** With `cloudflared` installed locally:

```
Host phone
  HostName phone.example.com
  User root
  Port 1304
  ProxyCommand cloudflared access ssh --hostname %h
```

Keep `%h` in the ProxyCommand. Naming a hostname literally there means the client ends up
talking to whatever that name resolves to, and ssh then reports `REMOTE HOST IDENTIFICATION
HAS CHANGED` against a host key that was never the phone's.

### Traps worth knowing before you debug it

- **`cloudflared service install` appears to work and does nothing.** There is no init system
  inside a chroot, so the unit it writes never runs. Start the connector directly, which is
  what `debian-start.sh` does.
- **Quick tunnels cannot carry this.** `cloudflared tunnel --url ...` is HTTP-only, so SSH
  will not pass through it.
- **An empty token gives you no error.** The connector starts, stays up, and quietly never
  connects. This is why `debian-start.sh` reads the token on the host side: inside the
  chroot, `/bin/sh` inherits Android's `PATH`, where `cat` does not exist, so a `$(cat ...)`
  there returns nothing.
- **Access sessions expire.** You will need a browser re-authentication every so often. The
  LAN address on port 1304 keeps working in the meantime, which is worth knowing before
  concluding the phone is down.

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
