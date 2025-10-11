#!/bin/bash

# SSL 인증서 갱신 후 nginx 재시작 스크립트
echo "Starting certificate renewal check..."

# certbot 갱신 실행
docker exec small-town_certbot_1 certbot renew --webroot --webroot-path=/var/www/certbot

# 갱신이 성공했으면 nginx 재시작
if [ $? -eq 0 ]; then
    echo "Certificate renewal successful, reloading nginx..."
    docker exec newcodes-nginx nginx -s reload
    echo "Nginx reloaded successfully"
else
    echo "Certificate renewal failed"