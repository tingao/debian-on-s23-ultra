# Print the dynamic MOTD on interactive logins.
# This chroot has no systemd and sshd runs with UsePAM no, so neither
# pam_motd nor a motd generator runs on its own. MOTD_SHOWN prevents repeats
# in nested shells.
case "$-" in
 *i*)
 if [ -z "$MOTD_SHOWN" ] && [ -d /etc/update-motd.d ]; then
 MOTD_SHOWN=1; export MOTD_SHOWN
 run-parts /etc/update-motd.d/ 2>/dev/null
 fi
 ;;
esac
