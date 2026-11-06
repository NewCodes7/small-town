# HTTPS renewal (2026-11-06)

The public certificate expired at 2026-11-06 00:03:50 UTC (09:03:50 KST).
Docker `newcodes-nginx` serves certificates from `certbot/conf`, not the host's
`/etc/letsencrypt`. The host Certbot nginx plugin failed because Docker already
owned ports 80/443; the compose Certbot container was absent.

The host `certbot.timer` now runs `renew.sh` through the supplied service override.
It uses Docker Certbot with the live webroot and certificate mounts, tests and
reloads Docker nginx, and checks both public hostnames for trusted certificates
with at least 21 days remaining. Failures make the systemd service fail and are
recorded in the journal. No external notification destination is configured.

Install on this host:

```sh
sudo mkdir -p /etc/systemd/system/certbot.service.d
sudo cp ops/https/certbot.service.override.conf /etc/systemd/system/certbot.service.d/override.conf
sudo systemctl daemon-reload
sudo systemctl enable --now certbot.timer
```

Validate and inspect:

```sh
sudo bash ops/https/renew.sh --dry-run
sudo systemctl start certbot.service
sudo journalctl -u certbot.service -n 80
systemctl list-timers certbot.timer
```

Do not start the compose `certbot` loop alongside this host timer. It does not
reload nginx. Keep the `certbot/conf` and `certbot/www` directories across deploys.
