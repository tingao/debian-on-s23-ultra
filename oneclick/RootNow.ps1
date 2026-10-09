<#
 RootNow.ps1, one-click root restore + OTA lockdown for the
 Galaxy S23 Ultra (SM-S918B, S918BXXSAFZI1).

 Why adb and not an app: the exploit WRITES to /sys/kernel/tracing and to
 /data/local/tmp. An app (untrusted_app) can do neither, so it can never run
 the exploit in its own context, which is exactly why Root My Galaxy needs
 Shizuku (Shizuku supplies the shell context). adb supplies the same context.

 Proven sequence: exploit -> ksu-helper --late-load -> verify -> lockdown.
#>
$ErrorActionPreference = 'Continue'

$RK = '/data/local/tmp'
$localRK = '<repo>\tools\rootkit-fzi1'

$adbCmd = Get-Command adb -ErrorAction SilentlyContinue
if ($adbCmd) {
    $adb = $adbCmd.Source
} else {
    # No machine-specific path here: honour the standard Android env vars, then the
    # default SDK install location, and tell the user what to do if none match.
    $candidates = @(
        (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'),
        (Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe'),
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
    ) | Where-Object { $_ -and (Test-Path $_) }
    $adb = $candidates | Select-Object -First 1
}
if (-not $adb -or -not (Test-Path $adb)) {
    Write-Host "ERROR: adb not found. Put it on PATH, or set ANDROID_HOME / ANDROID_SDK_ROOT."
    Read-Host "Enter to close"; exit 1
}

Write-Host "====================================================="
Write-Host " Galaxy S23 Ultra - root restore + OTA lockdown"
Write-Host "====================================================="

Write-Host ""
Write-Host "[1/4] waiting for device ..."
& $adb wait-for-device | Out-Null
$model = (& $adb shell getprop ro.product.model) -join ''
Write-Host (" connected: " + $model.Trim())

Write-Host ""
Write-Host "[2/4] staging payloads ..."
if (Test-Path $localRK) {
 foreach ($f in Get-ChildItem $localRK) {
 & $adb push $f.FullName "$RK/$($f.Name)" | Out-Null
 }
 & $adb shell "cd $RK ; cp -f cve-2026-43499-root-fzi1 ksu-helper ; cp -f cve-2026-43499-app-fzi1.so ksu-payload ; cp -f stability-launcher-fzi1 stability-launcher ; cp -f mm-exec-factory-fzi1 mm-exec-factory ; cp -f ksud-next-v3.4.0-fzi1 ksud-selected ; chmod 755 ksu-helper ksu-payload stability-launcher mm-exec-factory ksud-selected"
}
Write-Host " payloads ready"

Write-Host ""
Write-Host "[3/4] running the sequence (1-3 min; the gate may wait for cool-down) ..."
Write-Host " NOTE: unplug the charger - the exploit gate wants <= 48 C"
& $adb shell "cd $RK ; rm -f root-now.log ; nohup sh root-now.sh > /dev/null 2>&1 &" | Out-Null
for ($i = 0; $i -lt 72; $i++) {
 Start-Sleep -Seconds 5
 $t = (& $adb shell "tail -1 $RK/root-now.log 2>/dev/null") -join ''
 if ($t.Trim()) { Write-Host (" " + $t.Trim()) }
 if ($t -match 'root-now end') { break }
}

Write-Host ""
Write-Host "[4/4] result"
& $adb shell "sh -c 'tail -16 $RK/root-now.log 2>/dev/null'"
Write-Host ""
Write-Host "--- lockdown status ---"
& $adb shell "su -c 'sh $RK/ota_lockdown/status.sh'"

Write-Host ""
Write-Host "====================================================="
Read-Host "Press Enter to close"
