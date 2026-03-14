# 로그 조회 명령어 가이드

## 기본 로그 파일 위치
```bash
/app/logs/
```

## 로그 파일 종류
- `small-town.log` - 전체 애플리케이션 로그
- `small-town-error.log` - 전체 애플리케이션 ERROR 로그
- `crawler.log` - 크롤러 전용 로그 (DEBUG 이상)
- `crawler-error.log` - 크롤러 ERROR 로그

## 기본 조회 명령어

### 실시간 로그 확인 (tail)
```bash
# 전체 로그 실시간 확인
tail -f /app/logs/small-town.log

# 크롤러 로그 실시간 확인
tail -f /app/logs/crawler.log

# 크롤러 에러 실시간 확인
tail -f /app/logs/crawler-error.log

# 마지막 100줄 보기
tail -n 100 /app/logs/crawler.log

# 마지막 500줄 실시간 확인
tail -n 500 -f /app/logs/crawler.log
```

### 로그 파일 전체 보기
```bash
# less로 보기 (위아래 스크롤 가능, q로 종료)
less /app/logs/crawler.log

# 특정 날짜 로그 보기
less /app/logs/crawler-2025-10-17.0.log
```

## 검색 명령어

### grep을 이용한 검색

#### 기본 검색
```bash
# ERROR 로그만 찾기
grep "ERROR" /app/logs/crawler.log

# 특정 클래스 로그 찾기
grep "CrawlingService" /app/logs/crawler.log

# 대소문자 구분 없이 검색 (-i)
grep -i "error" /app/logs/crawler.log

# 검색 결과 앞뒤 3줄 함께 보기 (-C 3)
grep -C 3 "ERROR" /app/logs/crawler.log

# 검색 결과 앞 5줄 보기 (-B 5)
grep -B 5 "ERROR" /app/logs/crawler.log

# 검색 결과 뒤 5줄 보기 (-A 5)
grep -A 5 "ERROR" /app/logs/crawler.log
```

#### 여러 조건 검색
```bash
# 여러 키워드 OR 검색
grep -E "ERROR|Exception|Failed" /app/logs/crawler.log

# 특정 회사 크롤링 로그만 찾기
grep "토스" /app/logs/crawler.log
grep "당근" /app/logs/crawler.log
grep "네이버" /app/logs/crawler.log

# 날짜로 필터링
grep "2025-10-17" /app/logs/crawler.log

# 특정 시간대 로그 찾기
grep "2025-10-17 14:" /app/logs/crawler.log
```

#### 복합 검색
```bash
# ERROR이면서 특정 클래스
grep "ERROR" /app/logs/crawler.log | grep "CrawlingService"

# Exception 찾고 줄 번호 표시 (-n)
grep -n "Exception" /app/logs/crawler.log

# 여러 파일에서 동시 검색
grep "ERROR" /app/logs/crawler*.log

# 오늘 날짜 에러 로그만 검색
grep "2025-10-17" /app/logs/crawler-error.log
```

### 고급 검색

#### 특정 패턴 찾기
```bash
# NullPointerException 찾기
grep "NullPointerException" /app/logs/crawler.log

# Selenium 관련 에러
grep -i "selenium" /app/logs/crawler-error.log

# WebDriver 에러
grep "WebDriver" /app/logs/crawler-error.log

# 크롤링 실패 로그
grep -E "크롤링 실패|Failed to crawl" /app/logs/crawler.log

# 타임아웃 관련
grep -i "timeout" /app/logs/crawler.log

# 연결 에러
grep -E "Connection|refused|timeout" /app/logs/crawler-error.log
```

#### 통계 및 카운트
```bash
# ERROR 발생 횟수 세기
grep -c "ERROR" /app/logs/crawler.log

# 특정 회사 크롤링 횟수
grep -c "토스" /app/logs/crawler.log

# 각 에러 타입별 카운트
grep -o "Exception[^:]*" /app/logs/crawler-error.log | sort | uniq -c | sort -rn
```

## 조합 명령어 (파이프라인)

### 실시간 필터링
```bash
# 실시간으로 ERROR만 보기
tail -f /app/logs/crawler.log | grep "ERROR"

# 실시간으로 특정 회사 크롤링만 보기
tail -f /app/logs/crawler.log | grep "토스"

# 실시간으로 ERROR와 WARN 보기
tail -f /app/logs/crawler.log | grep -E "ERROR|WARN"
```

### 로그 분석
```bash
# 최근 100줄에서 ERROR 찾기
tail -n 100 /app/logs/crawler.log | grep "ERROR"

# 오늘 발생한 에러 종류별 정리
grep "2025-10-17" /app/logs/crawler-error.log | grep -o "Exception[^:]*" | sort | uniq -c

# 가장 많이 발생한 에러 10개
grep "Exception" /app/logs/crawler-error.log | sort | uniq -c | sort -rn | head -10

# 특정 시간대 에러만 보기
grep "2025-10-17 14:" /app/logs/crawler.log | grep "ERROR"
```

### 로그 저장
```bash
# 검색 결과를 파일로 저장
grep "ERROR" /app/logs/crawler.log > ~/crawler-errors.txt

# 오늘 에러 로그만 추출
grep "2025-10-17" /app/logs/crawler-error.log > ~/today-errors.txt

# 특정 회사 크롤링 로그만 추출
grep "토스" /app/logs/crawler.log > ~/toss-crawl-logs.txt
```

## 유용한 조합 예제

### 크롤링 작업 모니터링
```bash
# 크롤링 시작 확인
tail -f /app/logs/crawler.log | grep "크롤링 시작"

# 크롤링 완료/실패 확인
tail -f /app/logs/crawler.log | grep -E "크롤링 완료|크롤링 실패"

# 크롤링된 게시글 수 확인
tail -f /app/logs/crawler.log | grep "게시글"
```

### 에러 디버깅
```bash
# 스택 트레이스 전체 보기
grep -A 20 "Exception" /app/logs/crawler-error.log

# 최근 에러 5개만 보기
grep "ERROR" /app/logs/crawler-error.log | tail -n 5

# 특정 에러의 발생 패턴 분석
grep "NullPointerException" /app/logs/crawler-error.log | grep -o "202.-..-.."

# 회사별 에러 발생 현황
grep "ERROR" /app/logs/crawler.log | grep -o "Corporation{[^}]*}" | sort | uniq -c
```

### 성능 분석
```bash
# 크롤링 소요 시간 확인
grep "소요 시간" /app/logs/crawler.log

# 느린 크롤링 찾기 (예: 30초 이상)
grep -E "소요.*[3-9][0-9]초|소요.*[0-9]{3,}초" /app/logs/crawler.log

# 메모리 관련 로그
grep -i "memory\|heap" /app/logs/small-town-error.log
```

## Docker 환경에서 로그 보기

### 컨테이너 로그 직접 조회
```bash
# Docker 로그 실시간 확인
docker logs -f small-town-app

# 마지막 100줄만
docker logs --tail 100 small-town-app

# 타임스탬프 포함
docker logs -t small-town-app

# 특정 시간 이후 로그
docker logs --since "2025-10-17T14:00:00" small-town-app
```

### 컨테이너 내부 로그 파일 접근
```bash
# 컨테이너 접속
docker exec -it small-town-app bash

# 접속 후 로그 조회
tail -f /app/logs/crawler.log

# 또는 직접 실행
docker exec -it small-town-app tail -f /app/logs/crawler.log
docker exec -it small-town-app grep "ERROR" /app/logs/crawler-error.log
```

## less 명령어 사용법 (로그 뷰어)

```bash
less /app/logs/crawler.log
```

### less 내부 명령어
- `Space` - 다음 페이지
- `b` - 이전 페이지
- `/검색어` - 앞으로 검색 (Enter로 검색, n으로 다음 결과, N으로 이전 결과)
- `?검색어` - 뒤로 검색
- `G` - 파일 끝으로
- `g` - 파일 처음으로
- `q` - 종료

### less 고급 사용
```bash
# 실시간 업데이트 모드 (tail -f와 유사)
less +F /app/logs/crawler.log

# 줄 번호 표시
less -N /app/logs/crawler.log

# 검색어 하이라이트
less -p "ERROR" /app/logs/crawler.log
```

## 주요 검색 키워드

### 크롤러 관련
- `크롤링 시작` - 크롤링 작업 시작
- `크롤링 완료` - 크롤링 정상 완료
- `크롤링 실패` - 크롤링 실패
- `게시글` - 수집된 게시글 정보
- `Corporation` - 회사 정보
- `BlogCrawler` - 크롤러 구현체
- `WebDriver` - Selenium WebDriver

### 에러 관련
- `ERROR` - 에러 레벨 로그
- `Exception` - 예외 발생
- `NullPointerException` - NPE
- `TimeoutException` - 타임아웃
- `NoSuchElementException` - 요소 없음 (Selenium)
- `SQLException` - DB 에러
- `Failed` - 실패 관련

### 성능 관련
- `소요 시간` - 작업 소요 시간
- `처리 완료` - 처리 완료
- `시작` - 작업 시작
- `종료` - 작업 종료

## 팁

1. **컬러 출력**: `grep --color=always`로 검색어를 색상으로 표시
   ```bash
   grep --color=always "ERROR" /app/logs/crawler.log | less -R
   ```

2. **정규표현식 사용**: `-E` 옵션으로 확장 정규표현식 사용
   ```bash
   grep -E "ERROR|WARN|Exception" /app/logs/crawler.log
   ```

3. **파일 크기 확인**: 로그 파일이 너무 크면 검색이 느릴 수 있음
   ```bash
   ls -lh /app/logs/
   du -sh /app/logs/
   ```

4. **압축 파일 검색**: 압축된 과거 로그 검색
   ```bash
   zgrep "ERROR" /app/logs/crawler-2025-10-16.0.log.gz
   ```
