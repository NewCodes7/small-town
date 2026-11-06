#!/bin/bash
# Invoked by certbot.service on the host; uses the live Docker certificate store.
set -euo pipefail
exec 9>/run/lock/newcodes-certbot.lock
flock -n 9 || exit 0
cd /home/ubuntu/small-town
/usr/bin/docker run --rm --name newcodes-certbot-renew \
  --entrypoint certbot \
  -v "$PWD/certbot/conf:/etc/letsencrypt" \
  -v "$PWD/certbot/www:/var/www/certbot" \
  -v /var/log/letsencrypt-docker:/var/log/letsencrypt \
  certbot/certbot:latest renew --cert-name newcodes.net \
  --webroot --webroot-path /var/www/certbot --non-interactive \
  --no-random-sleep-on-renew "$@"
# Reload even when no issuance was needed, so a previously failed reload recovers.
/usr/bin/docker exec newcodes-nginx nginx -t
/usr/bin/docker exec newcodes-nginx nginx -s reload
if [[ " $* " != *" --dry-run "* ]]; then
  sleep 2
  for domain in newcodes.net www.newcodes.net; do
    pem=$(timeout 20 openssl s_client -connect "$domain:443" -servername "$domain" \
      -verify_hostname "$domain" -verify_return_error </dev/null 2>/dev/null)
    openssl x509 -noout -checkend 1814400 <<<"$pem"
  done
fi
