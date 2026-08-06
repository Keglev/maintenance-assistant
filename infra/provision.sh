#!/usr/bin/env bash
# =============================================================================
# provision.sh — prepares a fresh Ubuntu server to run the maintenance-assistant
# stack. Idempotent: every step checks its own end state, so re-running it is a
# no-op rather than a second installation.
#
#   scp infra/provision.sh root@<host>:/tmp/ && ssh root@<host> 'bash /tmp/provision.sh'
#
# What it does NOT do: deploy the application. It only produces a host that can
# run it — Docker, an unprivileged deploy user, a hardened sshd, fail2ban, and
# the directory the compose files live in.
#
# Run as root. Target: Ubuntu 24.04+ on a Hetzner Cloud instance.
# =============================================================================
set -euo pipefail

DEPLOY_USER="deploy"
APP_DIR="/opt/maintenance-assistant"

log() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok()  { printf '    \033[0;32m✓\033[0m %s\n' "$*"; }
skip() { printf '    \033[0;33m•\033[0m %s\n' "$*"; }

[ "$(id -u)" -eq 0 ] || { echo "Must run as root." >&2; exit 1; }

# ---------------------------------------------------------------------------
# 1. Base packages and unattended security upgrades
# ---------------------------------------------------------------------------
log "System packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get upgrade -y -qq
apt-get install -y -qq ca-certificates curl gnupg unattended-upgrades fail2ban
ok "base packages present"

# dpkg-reconfigure is the documented way to enable the timers, and is a no-op
# when they are already enabled.
log "Unattended upgrades"
dpkg-reconfigure -f noninteractive unattended-upgrades
systemctl enable --now unattended-upgrades >/dev/null 2>&1 || true
ok "unattended-upgrades enabled"

# ---------------------------------------------------------------------------
# 2. Docker Engine + compose plugin, from Docker's own apt repository
# ---------------------------------------------------------------------------
log "Docker Engine"
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  skip "docker $(docker --version | awk '{print $3}' | tr -d ,) already installed"
else
  install -m 0755 -d /etc/apt/keyrings
  if [ ! -f /etc/apt/keyrings/docker.asc ]; then
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
    chmod a+r /etc/apt/keyrings/docker.asc
  fi

  # Docker publishes per-codename. A brand-new Ubuntu release can land here
  # before Docker has built for it, so the repository is probed and the script
  # falls back to the newest codename Docker does publish. Ubuntu 26.04
  # (resolute) was already present when this was written, so the fallback stays
  # inert — it exists so a future release does not break provisioning.
  . /etc/os-release
  CODENAME="${VERSION_CODENAME}"
  if ! curl -fsSL -o /dev/null "https://download.docker.com/linux/ubuntu/dists/${CODENAME}/Release"; then
    for fallback in questing plucky noble jammy; do
      if curl -fsSL -o /dev/null "https://download.docker.com/linux/ubuntu/dists/${fallback}/Release"; then
        echo "    ! Docker has no repo for '${CODENAME}'; falling back to '${fallback}'"
        CODENAME="$fallback"
        break
      fi
    done
  fi

  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -qq
  apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  ok "docker installed from the ${CODENAME} repository"
fi
systemctl enable --now docker >/dev/null 2>&1 || true
ok "docker service enabled"

# ---------------------------------------------------------------------------
# 3. Unprivileged deploy user, key-only access
# ---------------------------------------------------------------------------
log "Deploy user"
if id "$DEPLOY_USER" >/dev/null 2>&1; then
  skip "user ${DEPLOY_USER} exists"
else
  adduser --disabled-password --gecos "" "$DEPLOY_USER"
  ok "user ${DEPLOY_USER} created"
fi

# Password login stays disabled for this account: the account exists to run
# containers over an SSH key, and never to be logged into with a secret.
passwd -l "$DEPLOY_USER" >/dev/null 2>&1 || true
usermod -aG docker "$DEPLOY_USER"
ok "${DEPLOY_USER} is in the docker group"

# The key that provisioned this box is the key that will deploy to it. Copying
# root's authorized_keys is what makes the deploy user usable without a second
# key exchange, and it is why this script never needs a key as an argument.
install -d -m 0700 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "/home/${DEPLOY_USER}/.ssh"
if [ -f /root/.ssh/authorized_keys ]; then
  install -m 0600 -o "$DEPLOY_USER" -g "$DEPLOY_USER" \
    /root/.ssh/authorized_keys "/home/${DEPLOY_USER}/.ssh/authorized_keys"
  ok "authorized_keys copied to ${DEPLOY_USER}"
fi

# ---------------------------------------------------------------------------
# 4. SSH hardening
# ---------------------------------------------------------------------------
log "SSH hardening"
# Written as a drop-in rather than by editing sshd_config: the distribution owns
# that file and a package upgrade may rewrite it, while drop-ins survive and are
# read last. Ubuntu 26.04's sshd_config includes /etc/ssh/sshd_config.d/*.conf.
cat > /etc/ssh/sshd_config.d/10-hardening.conf <<'CONF'
# Managed by infra/provision.sh — edit there, not here.
PasswordAuthentication no
PermitRootLogin prohibit-password
KbdInteractiveAuthentication no
CONF
chmod 0644 /etc/ssh/sshd_config.d/10-hardening.conf

# Validate before reloading: a bad config plus a reload is how a remote host
# locks everyone out.
if sshd -t; then
  systemctl reload ssh 2>/dev/null || systemctl reload sshd 2>/dev/null || true
  ok "sshd hardened and reloaded"
else
  echo "    ! sshd config invalid — drop-in left in place, service NOT reloaded" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# 5. fail2ban for sshd, stock settings
# ---------------------------------------------------------------------------
log "fail2ban"
# Ubuntu ships the sshd jail disabled by default; this enables it and nothing
# else. Defaults for bantime/findtime/maxretry are deliberately untouched.
cat > /etc/fail2ban/jail.d/sshd.local <<'CONF'
# Managed by infra/provision.sh — edit there, not here.
[sshd]
enabled = true
backend = systemd
CONF
systemctl enable fail2ban >/dev/null 2>&1 || true
systemctl restart fail2ban
ok "fail2ban running with the sshd jail enabled"

# ---------------------------------------------------------------------------
# 6. Application directory
# ---------------------------------------------------------------------------
log "Application directory"
install -d -m 0750 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "$APP_DIR"
ok "${APP_DIR} owned by ${DEPLOY_USER}"

log "Provisioning complete"
printf '    docker:   %s\n' "$(docker --version)"
printf '    compose:  %s\n' "$(docker compose version --short 2>/dev/null || echo unknown)"
printf '    next:     copy the compose files to %s and run docker compose up -d\n' "$APP_DIR"
