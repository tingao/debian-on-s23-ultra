#!/system/bin/sh
# ============================================================================
#  debian-limits.sh - put the Debian chroot inside hard resource limits.
#
#  Designed to be SOURCED from debian-start.sh:
#      . /data/local/tmp/debian-limits.sh
#  It must be sourced, not executed. cgroups are inherited from the process
#  that spawns a child, so this has to run inside debian-start.sh's own shell
#  ($$ = that shell) for sshd/cloudflared/rsyslog and every session they later
#  fork to end up inside the limits. Executing it separately moves a throwaway
#  shell into the group and the real services stay at cgroup=/. That is exactly
#  the bug that made every service show "/" the first time.
#  Because it is sourced it must NOT call exit.
#
#  The cgroup filesystems are NOT visible inside the chroot: /sys/fs/cgroup and
#  /dev/cpuset are separate mounts and the chroot's binds of /sys and /dev are
#  not recursive. Irrelevant for enforcement - the kernel applies the limits to
#  the whole tree regardless of what the chroot can see.
#
#  MEMORY: hard cap via cgroup v2. memory.swap.max=0 so there is no swap escape
#          hatch. memory.high is the soft threshold where reclaim starts.
#  CPU   : no quota is possible. CONFIG_CFS_BANDWIDTH is not set, so cpu.max and
#          cpu.cfs_quota_us cannot exist at all - it is a kernel compile-time
#          limitation, not a config choice. The cpuset controller is used
#          instead: it hard-partitions which cores Debian may run on. cpu.shares
#          additionally lowers its weight when Android competes.
#  DISK  : /data is f2fs with usrquota,grpquota but no prjquota, and the kernel
#          has no project-quota support mounted, so there is no per-directory
#          hard limit available. The watchdog monitors size instead.
#
#  ARITHMETIC: Android's mksh is 32-bit. $((6144*1024*1024)) overflows to a
#  negative number and the write silently fails leaving memory.max="max". All
#  MB->bytes maths goes through awk (doubles).
# ============================================================================

# Default: 8192 MB, all 8 cores.
#
# MEMORY: measured, not guessed. Android's genuinely-used RAM is only ~3.75 GB
# (2.56 GB used pss + 1.19 GB kernel), with ~8.2 GB free/reclaimable: 3.6 GB free,
# 4.0 GB cached kernel, 0.64 GB cached pss. /proc/meminfo agrees - MemAvailable
# ~7.6 GB. Giving Debian 8 GB still leaves Android several GB of headroom, and
# zram (12 GB, 3.04x compression) absorbs any overshoot. Raise further with
# DEBIAN_MEM_MB=... if wanted.
#
# CPU: unrestricted - all 8 cores. cpu.shares stays at 128 so that when Android
# actually wants CPU it wins, which is the "let Android deal with it" behaviour.
# Be aware of the thermal consequence: heavy sustained load takes this SoC to
# ~90C, and root restoration needs the device at <=48C, so hammering the CPU
# delays re-rooting after a reboot by minutes. That is physics, not a cap.
MEM_MB=${DEBIAN_MEM_MB:-8192}
CPU_LIST=${DEBIAN_CPUS:-0-7}

G=/sys/fs/cgroup/debian
CS=/dev/cpuset/debian
CT=/dev/cpuctl/debian
LOG=/data/local/tmp/debian-limits.log

BYTES=$(awk -v m="$MEM_MB" 'BEGIN{printf "%.0f", m*1048576}')
HIGH=$(awk  -v m="$MEM_MB" 'BEGIN{printf "%.0f", m*1048576*0.90}')

_lim_say() { echo "$(date '+%H:%M:%S') $*" >> "$LOG"; }

# ---------------------------------------------------------------- memory (v2)
mkdir -p "$G" 2>/dev/null
grep -q memory /sys/fs/cgroup/cgroup.subtree_control 2>/dev/null || \
  echo "+memory" > /sys/fs/cgroup/cgroup.subtree_control 2>/dev/null

MEMSTATE="NOT APPLIED"
if [ -f "$G/memory.max" ]; then
  echo "$BYTES" > "$G/memory.max" 2>/dev/null
  echo "$HIGH"  > "$G/memory.high" 2>/dev/null
  echo 0        > "$G/memory.swap.max" 2>/dev/null
  echo 0        > "$G/memory.oom.group" 2>/dev/null
  NOW=$(cat "$G/memory.max" 2>/dev/null)
  [ "$NOW" = "$BYTES" ] && MEMSTATE="ok max=${MEM_MB}MB" || MEMSTATE="ERROR got=$NOW want=$BYTES"
fi

# ---------------------------------------------------------------- cpu (cpuset)
mkdir -p "$CS" 2>/dev/null
echo "$CPU_LIST" > "$CS/cpus" 2>/dev/null
echo 0           > "$CS/mems" 2>/dev/null
mkdir -p "$CT" 2>/dev/null
echo 128         > "$CT/cpu.shares" 2>/dev/null

# -------------------------------------------------- move THIS shell in (roots)
echo $$ > "$G/cgroup.procs" 2>/dev/null
echo $$ > "$CS/tasks"       2>/dev/null
echo $$ > "$CT/tasks"       2>/dev/null

# Re-capture a running chroot stack (re-runs, watchdog restarts)
N=0
for pid in $(pgrep -f 'chroot/debian|cloudflared tunnel|sshd: ' 2>/dev/null); do
  [ "$pid" = "$$" ] && continue
  echo "$pid" > "$G/cgroup.procs" 2>/dev/null && N=$((N + 1))
  echo "$pid" > "$CS/tasks"       2>/dev/null
  echo "$pid" > "$CT/tasks"       2>/dev/null
done

# Read values into vars FIRST - nesting quotes inside $( ) breaks mksh's parser
# with "no closing quote", which aborted this script mid-way.
CPUS_NOW=$(cat "$CS/cpus" 2>/dev/null)
SHARES_NOW=$(cat "$CT/cpu.shares" 2>/dev/null)
CUR_BYTES=$(cat "$G/memory.current" 2>/dev/null || echo 0)
CUR_MB=$(awk -v b="$CUR_BYTES" 'BEGIN{printf "%.1f", b/1048576}')
PIDS=$(wc -l < "$G/cgroup.procs" 2>/dev/null)

_lim_say "memory=$MEMSTATE cpuset=$CPUS_NOW shares=$SHARES_NOW shell=$$ recaptured=$N group=${PIDS}pids/${CUR_MB}MB"

unset BYTES HIGH MEMSTATE N CPUS_NOW SHARES_NOW CUR_BYTES CUR_MB PIDS
# NOTE: deliberately no exit - this file is sourced by debian-start.sh
