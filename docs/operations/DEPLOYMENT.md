# 무중단 배포 가이드

nginx와 docker를 이용한 Blue-Green 무중단 배포 시스템

## 구성

### 1. Docker 설정
- **blue-green 컨테이너**: `newcodes-backend-blue`, `newcodes-backend-green`
- **nginx 로드밸런서**: `set $backend` + Docker DNS(`127.0.0.11`) 기반 트래픽 라우팅
- **헬스체크**: Spring Boot Actuator `/actuator/health`

### 2. 파일 구조
```
├── docker-compose.yml       # Blue-Green 컨테이너 설정
├── nginx/
│   ├── nginx.conf          # nginx 메인 설정
│   └── default.conf        # 프록시/백엔드 설정
├── deploy.sh               # 배포 자동화 스크립트
└── Dockerfile              # 애플리케이션 이미지
```

## 핵심 원칙
- 전환 대상 백엔드 컨테이너가 실제로 실행 중일 때만 트래픽을 전환한다.
- Nginx 컨테이너 내부에서 대상 백엔드 hostname 해석이 가능할 때만 전환한다.
- `nginx -t`를 통과한 설정만 reload한다.
- 호스트/컨테이너 설정 불일치(stale bind mount)가 감지되면 Nginx 컨테이너 재시작으로 자동 복구한다.

## 사용 방법

### 1. 초기 배포
```bash
# 환경변수 설정
export SPRING_PROFILES_ACTIVE=prod
export DB_URL=postgresql://localhost:5432/small_town
# ... 기타 환경변수

# 첫 배포
./deploy.sh deploy
```

### 2. 새 버전 배포
```bash
./deploy.sh deploy
```

### 3. 특정 색상으로 배포
```bash
./deploy.sh deploy blue
./deploy.sh deploy green
```

### 4. 롤백
```bash
./deploy.sh rollback
```

### 5. 상태 확인
```bash
./deploy.sh status
```

## 배포 플로우

1. **현재 활성 서버 확인**: blue/green 중 현재 서비스 중인 컨테이너 파악
2. **새 컨테이너 기동**: GHCR 최신 이미지 pull 후 대상 컨테이너 시작
3. **헬스체크**: `/actuator/health`로 새 컨테이너 정상 동작 확인
4. **전환 사전 검증**:
   - 전환 대상 컨테이너 실행 여부 확인
   - Nginx 컨테이너에서 `getent hosts newcodes-backend-<color>` 확인
5. **트래픽 전환**:
   - `nginx/default.conf`의 `set $backend "http://newcodes-backend-<color>:8080"` 갱신
   - `nginx -t` 통과 시 `nginx -s reload`
6. **런타임 반영 검증**:
   - 컨테이너 내부 `/etc/nginx/conf.d/default.conf` 값 확인
   - 불일치 시 Nginx 컨테이너 재시작 후 재검증
7. **정리**: 이전 버전 컨테이너 제거

## 헬스체크

### Spring Boot Actuator
- **엔드포인트**: `/actuator/health`
- **Docker**: 10초 간격으로 헬스체크, 3회 실패 시 unhealthy
- **배포 스크립트**: 최대 5분간 헬스체크 대기

### Nginx
- **resolver**: `127.0.0.11 valid=5s` (Docker DNS 재해석)
- **업스트림 대상**: `set $backend` 변수 기반 동적 전환

## 환경변수

### 필수 환경변수
```bash
# 데이터베이스
DB_URL=postgresql://localhost:5432/small_town
DB_USERNAME=your_db_user
DB_PASSWORD=your_db_password

# Spring 프로파일
SPRING_PROFILES_ACTIVE=prod

# OpenAI (선택)
OPENAI_API_KEY=your_openai_key
```

## 트러블슈팅

### 배포 실패 시
```bash
# 상태 확인
./deploy.sh status

# 컨테이너 로그 확인
docker logs newcodes-backend-blue
docker logs newcodes-backend-green
docker logs newcodes-nginx

# 수동 롤백
./deploy.sh rollback
```

### Nginx 설정/해석 확인
```bash
# nginx 설정 테스트
docker exec newcodes-nginx nginx -t

# backend DNS 해석 확인
docker exec newcodes-nginx getent hosts newcodes-backend-blue newcodes-backend-green

# 현재 컨테이너 내부 backend 설정 확인
docker exec newcodes-nginx sh -lc 'grep -n "set \$backend" /etc/nginx/conf.d/default.conf'

# nginx 리로드
docker exec newcodes-nginx nginx -s reload
```

### stale bind mount 의심 시
```bash
# 호스트 파일 vs 컨테이너 파일 inode 비교
stat /home/ubuntu/small-town/nginx/default.conf
docker exec newcodes-nginx stat /etc/nginx/conf.d/default.conf

# 설정 불일치/해석 실패 시 nginx 재시작
docker restart newcodes-nginx
docker exec newcodes-nginx nginx -t
```

## 운영 주의사항
- 파일 단위 bind mount 환경에서 `sed -i`는 inode 교체를 유발해 설정 드리프트를 만들 수 있다.
- 현재 `deploy.sh`는 in-place 갱신 + 런타임 검증 + 자동 복구를 수행한다.
- 배포 실패 시 부분 전환 상태를 방치하지 말고 `rollback` 또는 설정 점검 후 재배포한다.

## 모니터링
- Prometheus + Grafana 통합 (기존 설정 유지)
- 각 컨테이너별 메트릭 수집
- 배포 성공/실패 알림 설정 가능
