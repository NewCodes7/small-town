FROM nginx:1.28-alpine

# 캐시 디렉토리 생성 및 권한 설정
RUN mkdir -p /var/cache/nginx/home \
    && mkdir -p /var/cache/nginx/corporation \
    && mkdir -p /var/cache/nginx/static \
    && chmod -R 777 /var/cache/nginx

EXPOSE 80 443

CMD ["nginx", "-g", "daemon off;"]
