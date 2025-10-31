FROM nginx:1.26-alpine

# ngx_cache_purge 모듈 설치
RUN apk add --no-cache nginx-mod-http-cache-purge

# 캐시 디렉토리 생성 및 권한 설정
RUN mkdir -p /var/cache/nginx/home \
    && mkdir -p /var/cache/nginx/corporation \
    && mkdir -p /var/cache/nginx/static \
    && chmod -R 777 /var/cache/nginx

EXPOSE 80 443

CMD ["nginx", "-g", "daemon off;"]
