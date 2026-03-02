# Nginx 502 장애 회고 (2026-03-02)

## 1) 문제 현상
- 시각: `2026-03-02 01:28 ~ 01:34 UTC` (KST `2026-03-02 10:28 ~ 10:34`)
- 사용자 증상: `newcodes.net` 접속 시 간헐/연속 `502 Bad Gateway`
- Nginx 에러 로그 핵심:
  - `connect() failed (113: Host is unreachable) while connecting to upstream`
  - upstream 대상이 `http://172.19.0.10:8080/...`로 고정되어 실패

## 2) 조사 과정 요약
1. 최근 커밋 확인 결과:
- `ebf5a02`: `application-prod.properties` 변경(커넥션/스레드 풀)
- `bdac5e2`: Nginx DNS 재해석 방식 도입(`$backend`, `resolver 127.0.0.11`)

2. 실제 런타임 상태 확인:
- 실행 중 백엔드: `newcodes-backend-blue`만 존재
- `newcodes-backend-green`는 미실행

3. 설정 파일 불일치 확인:
- 호스트 파일(`/home/ubuntu/small-town/nginx/default.conf`): 최신 포맷
  - `set $backend "http://newcodes-backend-blue:8080";`
- 컨테이너 파일(`/etc/nginx/conf.d/default.conf`): 구버전 포맷
  - `upstream backend { server newcodes-backend-green:8080 ... }`

4. inode 비교로 확정:
- 호스트/컨테이너 `default.conf`의 inode와 size가 서로 다름
- 컨테이너 쪽 `Links: 0` 상태 확인

## 3) 원인 (배경지식 포함)
근본 원인은 **Nginx 컨테이너가 오래된 설정 파일 inode를 계속 참조한 상태(stale bind mount)**였다.

### 왜 이런 일이 생기나?
운영 구성이 아래 조합일 때 발생할 수 있다.
- `docker-compose`에서 `nginx/default.conf`를 **파일 단위 bind mount**로 연결
- 배포 스크립트에서 `sed -i`로 해당 파일 수정

여기서 핵심 배경지식:
- Linux에서 파일은 "경로(path)"가 아니라 내부적으로 "inode(실제 파일 객체)"를 참조한다.
- `sed -i`는 환경에 따라 "같은 파일을 직접 수정"이 아니라, 임시 파일을 만든 뒤 `rename`으로 교체한다.
- 이 경우 경로는 같아 보여도 inode가 바뀐다.

즉, 이번 케이스는 아래 흐름이었다.
1. 호스트의 `nginx/default.conf`는 새 inode(최신 내용)로 교체됨
2. 컨테이너 bind mount는 이전 inode(구버전 내용)를 계속 참조
3. Nginx는 컨테이너 내부에서 여전히 구버전 upstream 설정 사용
4. `newcodes-backend-green`가 없는데 green으로 프록시하려다 502 발생

추가로 중요한 점:
- `nginx -s reload`는 설정 재로드이지, mount를 다시 연결하지는 않는다.
- 그래서 stale 상태에서는 reload만으로 해결되지 않고, 컨테이너 재시작/재생성이 필요하다.

## 4) 해결 과정
1. 현재 활성 백엔드 확인 (`blue`만 실행 중)
2. Nginx 컨테이너 설정 테스트 실패 확인 (`host not found in upstream`)
3. Nginx 컨테이너 재시작 수행
4. 재시작 후 설정 재로드/적용 정상화
5. 서비스 정상 응답 복구 확인

## 5) 이번 장애에서 배운 점
- `git HEAD`가 최신이어도 **컨테이너 내부 실적용 설정**이 다를 수 있다.
- 장애 시에는 반드시 아래 3가지를 함께 확인해야 한다.
  1. 실행 중 컨테이너 상태
  2. 컨테이너 내부 설정 파일 내용
  3. Nginx 설정 테스트(`nginx -t`)
- 파일 단위 bind mount + `sed -i` 조합은 운영에서 드리프트를 만들 수 있다.

## 6) 재발 방지 액션 아이템
### 운영 절차
- 배포 전환 직후 아래 검증을 필수화:
  - `docker exec newcodes-nginx nginx -t`
  - `docker exec newcodes-nginx getent hosts newcodes-backend-blue newcodes-backend-green`
  - 활성 백엔드 컨테이너 존재 여부 확인

### 스크립트/구성 개선
- `deploy.sh`에 가드 추가:
  - 전환 대상 hostname 해석 실패 시 즉시 중단
  - `nginx -t` 실패 시 reload/배포 진행 금지
- 파일 수정 방식 개선:
  - `sed -i` 사용 시 inode 교체 리스크 제거 방식으로 변경
  - 또는 Nginx 설정 mount를 디렉터리 단위로 재구성해 드리프트 가능성 축소

## 7) 빠른 점검 명령어
```bash
# 실행 중 백엔드 확인
docker ps --format 'table {{.Names}}\t{{.Status}}' | grep newcodes-backend

# 컨테이너 내부 nginx 설정 확인
docker exec newcodes-nginx sh -lc 'nl -ba /etc/nginx/conf.d/default.conf | sed -n "20,70p"'

# nginx 설정 유효성 검사
docker exec newcodes-nginx nginx -t

# DNS 해석 확인(nginx 컨테이너 내부)
docker exec newcodes-nginx getent hosts newcodes-backend-blue newcodes-backend-green
```

## 8) 결론
이번 502는 애플리케이션 로직 문제가 아니라, **Nginx가 오래된 upstream 설정을 참조한 인프라 설정 동기화 문제**였다. 
재시작으로 즉시 복구되었고, 이후에는 배포 검증 절차 강화와 설정 파일 반영 방식 개선이 핵심이다.

## 9) 상황 파악에 사용한 주요 명령어와 결과
아래는 실제 원인 확정에 결정적으로 사용된 명령과 핵심 출력이다.

### 9-1. 현재 실행 중 백엔드 확인
```bash
docker ps --format 'table {{.Names}}\t{{.Status}}' | grep newcodes-backend
```
출력:
```text
newcodes-backend-blue   Up 13 hours (healthy)
```
해석:
- blue만 실행 중, green은 미실행

### 9-2. Nginx 설정 테스트 실패 확인
```bash
docker exec newcodes-nginx nginx -T
```
출력(핵심):
```text
host not found in upstream "newcodes-backend-green:8080" in /etc/nginx/conf.d/default.conf:33
nginx: configuration file /etc/nginx/nginx.conf test failed
```
해석:
- 컨테이너 내부 설정이 green upstream을 참조 중

### 9-3. 호스트 파일(최신) 확인
```bash
nl -ba /home/ubuntu/small-town/nginx/default.conf | sed -n '20,60p'
```
출력(핵심):
```text
53      set $backend "http://newcodes-backend-blue:8080";
```
해석:
- 호스트 파일은 최신 방식(`$backend`) + blue 지정

### 9-4. 컨테이너 내부 파일(구버전) 확인
```bash
docker exec newcodes-nginx sh -lc 'nl -ba /etc/nginx/conf.d/default.conf | sed -n "20,60p"'
```
출력(핵심):
```text
28  upstream backend {
33      server newcodes-backend-green:8080 max_fails=3 fail_timeout=30s;
```
해석:
- 컨테이너 내부는 구버전 upstream 블록 + green 활성

### 9-5. DNS 해석 확인
```bash
docker exec newcodes-nginx getent hosts newcodes-backend-blue newcodes-backend-green
```
출력:
```text
172.19.0.8        newcodes-backend-blue  newcodes-backend-blue
```
해석:
- blue만 해석됨, green 해석 실패

### 9-6. inode 불일치 확인 (원인 확정)
```bash
stat /home/ubuntu/small-town/nginx/default.conf
docker exec newcodes-nginx stat /etc/nginx/conf.d/default.conf
```
출력(핵심):
```text
# host
Inode: 1058089   Size: 6953

# container
Inode: 1058027   Size: 7154   Links: 0
```
해석:
- 같은 경로처럼 보여도 실제로는 서로 다른 파일 객체(inode)
- `Links: 0`는 컨테이너가 삭제/교체된 옛 파일을 잡고 있을 때 나타나는 전형적 신호

### 9-7. 에러 로그 패턴 확인
```bash
docker logs --since 24h newcodes-nginx 2>&1 | grep -En 'host not found|connect\(\) failed|upstream'
```
출력(예시):
```text
... connect() failed (113: Host is unreachable) ... upstream: "http://172.19.0.10:8080/"
... connect() failed (113: Host is unreachable) ... upstream: "http://172.19.0.10:8080/favicon.ico"
```
해석:
- 존재하지 않는(또는 도달 불가한) 고정 upstream IP로 계속 연결 시도
