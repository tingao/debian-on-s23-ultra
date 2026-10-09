@echo off
rem Connect to the Debian server running on the phone.
rem  - LAN:      ssh -p 1304 root@192.0.2.10
rem  - Anywhere: forward TCP 1304 on your router, then use your public IP.
rem Root's password is LOCKED, so the SSH key below is the only way in.

set KEY=<repo>\tools\debian_key
set HOST=%~1
if "%HOST%"=="" set HOST=192.0.2.10

echo Connecting to Debian at %HOST%:1304 ...
ssh -i "%KEY%" -p 1304 -o StrictHostKeyChecking=no -o UserKnownHostsFile=NUL -o LogLevel=ERROR root@%HOST% %2 %3 %4
