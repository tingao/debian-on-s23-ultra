@echo off
rem One-click: restore root and re-apply the OTA lockdown.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0RootNow.ps1"
