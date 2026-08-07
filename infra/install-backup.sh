#!/usr/bin/env bash
# =============================================================================
# install-backup.sh — installs the nightly backup on the host.
#
#   scp -r infra root@<host>:/tmp/ && ssh root@<host> 'bash /tmp/infra/install-backup.sh'
#
# Idempotent, like provision.sh: it rewrites the same three files and re-enables
# the same timer, so running it again is how you deploy a change to the backup
# script rather than something to avoid.
#
# Root is needed for this installation and for nothing else. The backup itself
# runs as the unprivileged deploy user — see the unit file.
# =============================================================================
set -euo pipefail

DEPLOY_USER="deploy"
APP_DIR="/opt/maintenance-assistant"
SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

log()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok()   { printf '    \033[0;32m✓\033[0m %s\n' "$*"; }

[ "$(id -u)" -eq 0 ] || { echo "Must run as root." >&2; exit 1; }
id "$DEPLOY_USER" >/dev/null 2>&1 || { echo "User ${DEPLOY_USER} does not exist — run provision.sh first." >&2; exit 1; }

log "Backup script"
install -m 0755 -o root -g root "${SRC_DIR}/backup.sh" /usr/local/bin/maintenance-backup
ok "/usr/local/bin/maintenance-backup installed"

# Owned by deploy because the backup process writes here as that user; 0700
# because a dump of the database is every protocol in it.
log "Backup directory"
install -d -m 0700 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "${APP_DIR}/backups"
ok "${APP_DIR}/backups ready"

log "systemd units"
install -m 0644 -o root -g root "${SRC_DIR}/systemd/maintenance-backup.service" /etc/systemd/system/
install -m 0644 -o root -g root "${SRC_DIR}/systemd/maintenance-backup.timer"   /etc/systemd/system/
systemctl daemon-reload
# The timer is enabled, not the service: enabling a oneshot service would run it
# at every boot, which is not what nightly means.
systemctl enable --now maintenance-backup.timer >/dev/null
ok "maintenance-backup.timer enabled"

log "Installed"
systemctl list-timers maintenance-backup.timer --no-pager || true
printf '\n    run it now:  systemctl start maintenance-backup\n'
printf '    read the log: journalctl -u maintenance-backup -n 50\n'
printf '    backups:      ls -lh %s/backups\n' "$APP_DIR"
