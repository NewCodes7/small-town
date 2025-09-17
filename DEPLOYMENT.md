# 무중단 배포 가이드

nginx와 docker를 이용한 Blue-Green 무중단 배포 시스템

## 구성

### 1. Docker 설정
- **blue-green 컨테이너**: `newcodes-backend-blue`, `newcodes-backend-green`
- **nginx 로드밸런서**: upstream을 통한 트래픽 라우팅
- **헬스체크**: Spring Boot Actuator `/actuator/health`

### 2. 파일 구조
```
├── docker-compose.yml       # Blue-Green 컨테이너 설정
├── nginx/
│   ├── nginx.conf          # nginx 메인 설정
│   └── default.conf        # 업스트림 및 프록시 설정
├── deploy.sh               # 배포 자동화 스크립트
└── Dockerfile              # 애플리케이션 이미지
```

## 사용 방법

### 1. 초기 배포
```bash
# 환경변수 설정
export SPRING_PROFILES_ACTIVE=prod
export DB_URL=mysql://localhost:3306/small_town
# ... 기타 환경변수

# 첫 배포 (blue 컨테이너로 시작)
./deploy.sh deploy
```

### 2. 새 버전 배포
```bash
# 코드 변경 후
./deploy.sh deploy
```

### 3. 롤백
```bash
# 이전 버전으로 롤백
./deploy.sh rollback
```

### 4. 상태 확인
```bash
# 현재 배포 상태 확인
./deploy.sh status
```

## 배포 플로우

1. **현재 활성 서버 확인**: blue 또는 green 중 현재 서비스 중인 컨테이너 파악
2. **새 컨테이너 빌드**: 새 버전의 Docker 이미지 빌드
3. **새 컨테이너 시작**: 대기 중인 컨테이너에 새 버전 배포
4. **헬스체크**: `/actuator/health`로 새 컨테이너 정상 동작 확인
5. **트래픽 전환**: nginx upstream 설정 변경으로 트래픽 라우팅
6. **검증**: 새 버전 서비스 정상 동작 확인
7. **정리**: 이전 버전 컨테이너 선택적 제거

## 헬스체크

### Spring Boot Actuator
- **엔드포인트**: `/actuator/health`
- **Docker**: 10초 간격으로 헬스체크, 3회 실패시 unhealthy
- **배포 스크립트**: 최대 5분간 헬스체크 대기

### nginx 설정
- **fail_timeout**: 30초 (서버 장애시 30초간 요청 차단)
- **max_fails**: 3회 (3회 연속 실패시 서버 제외)

## 환경변수

### 필수 환경변수
```bash
# 데이터베이스
DB_URL=mysql://localhost:3306/small_town
DB_USERNAME=your_db_user
DB_PASSWORD=your_db_password

# Spring 프로파일
SPRING_PROFILES_ACTIVE=prod

# OpenAI (선택사항)
OPENAI_API_KEY=your_openai_key
```

## 트러블슈팅

### 배포 실패시
```bash
# 상태 확인
./deploy.sh status

# 컨테이너 로그 확인
docker logs newcodes-backend-blue
docker logs newcodes-backend-green

# 수동 롤백
./deploy.sh rollback
```

### nginx 설정 확인
```bash
# nginx 설정 테스트
docker exec newcodes-nginx nginx -t

# nginx 리로드
docker exec newcodes-nginx nginx -s reload
```

### 헬스체크 직접 확인
```bash
# 컨테이너별 헬스체크
curl http://localhost:8080/actuator/health  # blue
curl http://localhost:8081/actuator/health  # green (실행중인 경우)

# nginx를 통한 헬스체크
curl http://localhost/actuator/health
```

## 고급 설정

### 카나리 배포
nginx upstream에서 weight 설정으로 카나리 배포 가능:
```nginx
upstream backend {
    server newcodes-backend-blue:8080 weight=9;
    server newcodes-backend-green:8080 weight=1;
}
```

### 모니터링
- Prometheus + Grafana 통합 (기존 설정 유지)
- 각 컨테이너별 메트릭 수집
- 배포 성공/실패 알림 설정 가능