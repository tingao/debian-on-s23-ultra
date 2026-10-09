# 04b, The MOTD (matched to the `tokyo` server)

The owner's other server (reached as `ssh <your-other-server>`) has a
hand-crafted MOTD. Rather than invent one, the whole tree was copied from it and
adapted. See `reference/tokyo-motd/` for the originals.

## What a login looks like

```
Last login: Thu Oct 8 19:47:24 2026 from 192.0.2.20

 SYSTEM: server (Kernel: 5.15.189-android13-8-33413713-abS918BXXSAFZI1)
 TIME: Fri Oct 09 05:55 UTC | UPTIME: 10 hours, 39 minutes
 IPs: 192.0.2.10 (LAN) | 192.0.2.20 (You)
Linux server 5.15.189-android13-8-33413713-abS918BXXSAFZI1 #1 SMP PREEMPT aarch64

CPU [..............................] 2% (6 cores)
RAM [##########....................] 35% (3.8G/10.8G)
DISK [..............................] 3% (10G/460G)
BAT [##########################....] 88% (charging, 4.34V, +1073mA, 30C) <- handset battery

SSHGuard (sshd):
 |-- Failed-login bans triggered: 1
 `-- IPs blocked: 0 (kernel forbids nft/iptables here - see Cloudflare Gateway)

Listening TCP ports:
 1304 20241 39899

[fastfetch art]
root@server:~#
```

## Layout (identical to tokyo)

```
/etc/update-motd.d/
 05-lastlogin last login + GeoIP country
 10-sysinfo SYSTEM / TIME / UPTIME / IPs, with a "You" marker
 10-uname uname -snrvm
 15-mail new mail notices
 20-resources unicode bar graphs: CPU, RAM, DISK, BAT
 30-sshguard sshguard counters
 35-openports ufw rules, or just listening ports
 88-fastfetch fastfetch
 92-unattended-upgrades pending updates
 99-docker container counts
/usr/local/lib/motd-geoip.sh shared helpers
/etc/profile.d/00-server-motd.sh the runner
```

## Three adaptations were required

1. **`current_session_user_ip()`** read the login IP from
 `journalctl -u ssh`. There is no journald here, so it now uses
 **`$SSH_CONNECTION`**, which sshd always exports, same information, works
 anywhere, and much simpler.
2. **`30-sshguard`** used `systemctl is-active` + `journalctl`. It now checks for the
 `sshg-blocker` process and reads `/var/log/sshguard.log`.
3. **A runner was needed.** tokyo gets its MOTD from systemd + `pam_motd`; this chroot
 has neither, and `UsePAM no` means no PAM session hooks either. So
 `/etc/profile.d/00-server-motd.sh` runs `run-parts /etc/update-motd.d/` for
 interactive shells, guarded by `MOTD_SHOWN` to avoid repeats in nested shells.

## Notable details worth preserving

- **`20-resources` needed no changes at all**, it was already written for a handset
 container. It reads `/sys/class/power_supply/battery` directly and documents the
 `max77705` fuel-gauge units (voltage in µV, temp in 0.1 °C, current in mA, *not* the
 sysfs-conventional µA). That is why tokyo was the right blueprint.
- **The bar drawing is deliberate.** The escape bytes live in the `printf` *format*
 string, because `printf '%s'` does not interpret `\ddd` and would print literal
 `\xe2\x96\x88` text.
- **`geo_country()` needs `mmdblookup` + `/var/lib/GeoIP/GeoLite2-Country.mmdb`.**
 `mmdb-bin` is installed but the database is **not**, tokyo does not have it either,
 so the function falls through to `Unknown` by design. Private addresses report
 `LAN`. Drop a GeoLite2 database in place to get real countries.

 **Line endings matter.** These files were written from PowerShell, which emits
CRLF, and every script failed with `Syntax error: end of file unexpected`. Always
`sed -i 's/\r$//'` after transferring. This is the same class of trap as the `cat`
PATH issue.
