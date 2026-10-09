<#
 RootAndLock.ps1 - one-click root + OTA lockdown for the Galaxy S23 Ultra
 ---------------------------------------------------------------------------
 Double-click RootAndLock.cmd instead of running this directly.

 What it does, in order:
 1. waits for the phone over adb
 2. runs the CVE-2026-43499 exploit as the shell user (adb == shell, so
 Shizuku is NOT needed for this route)
 3. waits for KernelSU Next to come up in LKM mode
 4. (KernelSU then auto-runs the ota_lockdown module)
 5. re-runs the OTA lockdown explicitly and verifies it

 Safe to run repeatedly. It refuses to re-run the exploit twice on the same
 boot, because the exploit mutates kernel memory and its own supervisor
 refuses an unsafe retry - if you see that message, reboot first.
#>

$ErrorActionPreference = 'Continue'

# ---------------------------------------------------------------- config ----
$RK = '/data/local/tmp/rootkit'
$Launcher = "$RK/stability-launcher-afzh3"
$Payload = "$RK/cve-2026-43499-app-afzh3.so"
$Helper = "$RK/libcve43499root-afzh3.so"
$Lockdown = '/data/local/tmp/ota_lockdown/lockdown.sh'

# Optional extras after rooting (set to $true if you want them):
$StartDroidspaces = $false # start the Droidspaces daemon + the 'debian' container
$OpenShizukuApp = $true # just brings the Shizuku app to the front so you can tap Start

# --------------------------------------------------------------- helpers ----
function Step($n, $msg) { Write-Host "`n=== [$n] $msg" -ForegroundColor Cyan }
function Ok($msg) { Write-Host " $msg" -ForegroundColor Green }
function Warn($msg) { Write-Host " $msg" -ForegroundColor Yellow }
function Die($msg) { Write-Host "`n!! $msg" -ForegroundColor Red; Read-Host "`nPress Enter to close"; exit 1 }

function Find-Adb {
 $c = Get-Command adb -ErrorAction SilentlyContinue
 if ($c) { return $c.Source }
 # Standard locations only; no machine-specific path.
 foreach ($p in @(
  (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'),
  (Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe'),
  (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'))) {
  if ($p -and (Test-Path $p)) { return $p }
 }
 return $null
}

$Adb = Find-Adb
if (-not $Adb) { Die "adb.exe not found. Put it on PATH, or set ANDROID_HOME / ANDROID_SDK_ROOT." }

function Sh([string]$cmd) { & $Adb shell $cmd 2>&1 }

Write-Host "==========================================================" -ForegroundColor White
Write-Host " Galaxy S23 Ultra - root + OTA lockdown (one click)" -ForegroundColor White
Write-Host "==========================================================" -ForegroundColor White
Write-Host " adb: $Adb"

# ------------------------------------------------------------ 1. device -----
Step 1 "Waiting for the phone"
& $Adb wait-for-device | Out-Null
$state = (& $Adb get-state 2>&1) -join ''
if ($state -notmatch 'device') {
 Die "Phone not in 'device' state (got: $state). Unlock it and accept the USB debugging prompt."
}
$model = (Sh "getprop ro.product.model") -join ''
Ok "connected: $model"

$booted = (Sh "getprop sys.boot_completed") -join ''
if ($booted -notmatch '1') { Warn "boot not complete yet (sys.boot_completed=$booted) - the exploit needs a booted system" }

# check whether the exploit already ran on this boot
$tainted = (Sh "cat /proc/modules | grep -c kernelsu") -join ''
if ($tainted -match '^[1-9]') {
 Warn "KernelSU is ALREADY loaded on this boot."
 $go = Read-Host " Skip the exploit and just re-apply the lockdown [Y/n]"
 if ($go -notmatch '^[Nn]') { $skipExploit = $true } else { $skipExploit = $false }
} else {
 $skipExploit = $false
}

# ------------------------------------------------------------ 2. payloads ---
Step 2 "Checking the staged exploit payloads"
$ready = (Sh "test -x $Launcher && test -r $Payload && test -r $Helper && echo READY") -join ''
if ($ready -notmatch 'READY') {
 Die "payloads missing/incomplete under $RK. Re-run the staging step (see README.md)."
}
Ok "payloads present in $RK"

# ------------------------------------------------------------ 3. exploit ----
if ($skipExploit) {
 Step 3 "Exploit skipped (already rooted on this boot)"
} else {
 Step 3 "Running the exploit - this takes ~15-45s, leave the phone alone"
 Write-Host " (KASLR sampling, slab grooming, futex trigger, then KernelSU late-load)`n" -ForegroundColor DarkGray

 & $Adb shell "$Launcher --payload $Payload --helper $Helper"
 $rc = $LASTEXITCODE
 Write-Host ""
 if ($rc -ne 0) { Warn "exploit exited with code $rc - checking for root anyway" }
}

# ------------------------------------------------------------ 4. root -------
Step 4 "Waiting for root"
$rooted = $false
$idOut = ''
for ($i = 0; $i -lt 45; $i++) {
 $idOut = (Sh "su -c id") -join ''
 if ($idOut -match 'uid=0') { $rooted = $true; break }
 Start-Sleep -Seconds 2
 Write-Host "." -NoNewline
}
Write-Host ""
if (-not $rooted) {
 Die @"
root did not come up. Things to try:
 - reboot the phone and run this script again (the exploit refuses an unsafe
 retry after it has already mutated the kernel this boot)
 - make sure the phone is not hot and has been booted >60s
 - last output from 'su -c id': $idOut
"@
}
Ok "root acquired: $idOut"

# ------------------------------------------------------------ 5. lockdown ---
Step 5 "Applying + verifying the OTA lockdown"
& $Adb shell "su -c 'sh $Lockdown'" | ForEach-Object { Write-Host " $_" }

Step 6 "Verification"
# Quoted patterns are handled by status.sh on the device - nesting quotes through
# powershell -> adb -> su -> sh is not something you want to do by hand.
$st = @{}
(Sh "su -c 'sh /data/local/tmp/ota_lockdown/status.sh'") | ForEach-Object {
 if ($_ -match '^\s*([a-z_]+)\s*=\s*(.*)$') { $st[$Matches[1]] = $Matches[2].Trim() }
}
Write-Host " OTA clients disabled : $($st['disabled'])" -ForegroundColor $(if ($st['disabled'] -match '^3') {'Green'} else {'Red'})
Write-Host " hostnames blocked : $($st['hosts_blocked'])" -ForegroundColor $(if ($st['hosts_blocked'] -match '^[1-9]') {'Green'} else {'Red'})
Write-Host " staging files left : $($st['staging_files'])" -ForegroundColor $(if ($st['staging_files'] -eq '0') {'Green'} else {'Red'})
Write-Host " watchdog running : $($st['watchdog'])" -ForegroundColor $(if ($st['watchdog'] -eq 'yes') {'Green'} else {'Red'})
Write-Host " FOTA DNS resolves to : $($st['dns'])" -ForegroundColor $(if ($st['dns'] -match '127\.0\.0\.1') {'Green'} else {'Red'})
if ($st['staged_ota'] -ne '0') { Warn "a staged OTA package is present again - the watchdog will remove it" }

# ------------------------------------------------------------ 6. extras -----
if ($StartDroidspaces) {
 Step 7 "Starting Droidspaces"
 Sh "su -c '/data/local/Droidspaces/bin/droidspaces daemon >/dev/null 2>&1 &'" | Out-Null
 Start-Sleep -Seconds 4
 Sh "su -c '/data/local/Droidspaces/bin/droidspaces start -C /data/local/Droidspaces/Containers/debian/container.config'" |
 ForEach-Object { Write-Host " $_" }
}

if ($OpenShizukuApp) {
 Step 8 "Bringing Shizuku to the front"
 Sh "am start -n moe.shizuku.privileged.api/moe.shizuku.manager.MainActivity" | Out-Null
 Warn "Shizuku cannot be started headlessly (its server needs a token from its own UI)."
 Warn "If you need Shizuku, tap 'Start' in the app that just opened."
}

Write-Host "`n==========================================================" -ForegroundColor White
Write-Host " DONE - rooted and OTA updates are locked out." -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor White
Write-Host " log: /data/local/tmp/ota_lockdown.log"
Read-Host "`nPress Enter to close"
