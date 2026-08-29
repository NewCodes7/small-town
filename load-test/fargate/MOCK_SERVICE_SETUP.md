# LLM Mock 서비스 — 최초 1회 세팅

`run-prod-test.sh`(원커맨드 부하테스트 스크립트)가 동작하려면 먼저 이 문서의 절차를 **한 번만** 실행해 둬야 한다. 이후에는 다시 반복할 필요 없음 — mock은 ECS 서비스로 등록해두되 평시 desired-count **0**(미기동)이 기본이고, `run-prod-test.sh`가 테스트 실행마다 desired-count를 1로 올렸다가 종료 후 자동으로 다시 0으로 내린다. 게이트/nginx bypass는 (재배포가 필요해 자동 토글 대상이 아니므로) 상시 활성 상태로 유지된다.

이 아키텍처를 고른 이유와 트레이드오프는 [`README.md`](../README.md)의 "LLM Mock 모드" 섹션 참고.

> 아래 `<PLACEHOLDER>` 값은 사용자 AWS 계정/네트워크에만 있는 정보라 채워 넣어야 한다. AWS CLI가 설치·인증된 본인 머신(또는 CloudShell)에서 실행할 것 — 이 devcontainer에는 AWS 자격증명이 없다.

## -1. IAM 사용자 준비 (최초 1회, 콘솔)

이 문서의 명령어들을 실행할 전용 IAM 사용자를 먼저 만든다. 루트 계정 액세스 키를 직접 쓰지 않기 위함. 필요한 권한은 `iam-loadtest-policy.json`에 정리해둠 (ECR/ECS/Cloud Map/보안그룹 관리 + `ecsTaskExecutionRole`로의 `iam:PassRole`만 — IAM 사용자/역할을 새로 만드는 권한은 의도적으로 뺐다, 그건 아래처럼 콘솔에서 admin으로 한 번만 함).

**콘솔에서 (루트 또는 기존 admin 계정으로 로그인 후):**

1. IAM 콘솔 → **정책** → **정책 생성** → JSON 탭 → `load-test/fargate/iam-loadtest-policy.json` 내용 붙여넣기 → 이름 `NewCodesLoadTestSetup` → 생성
2. IAM 콘솔 → **사용자** → **사용자 추가** → 사용자 이름(예: `newcodes-loadtest-deployer`) → **액세스 키 - 프로그래밍 방식 액세스**만 체크(콘솔 로그인 불필요)
3. 권한 설정 → **기존 정책 직접 연결** → `NewCodesLoadTestSetup` 선택 → 사용자 생성
4. 생성 완료 화면(또는 사용자 → 보안 자격 증명 → 액세스 키 만들기)에서 **Access Key ID / Secret Access Key를 그 자리에서 CSV로 다운로드** — Secret은 이때만 보이고 다시 조회 불가

이 Access Key로 devcontainer에서 `! aws configure`를 실행하면 됨.

**CLI로 (이미 admin 자격 있는 프로필이 있다면 콘솔 대신):**

```bash
aws iam create-policy --policy-name NewCodesLoadTestSetup \
  --policy-document file://load-test/fargate/iam-loadtest-policy.json
aws iam create-user --user-name newcodes-loadtest-deployer
aws iam attach-user-policy --user-name newcodes-loadtest-deployer \
  --policy-arn arn:aws:iam::<ACCOUNT_ID>:policy/NewCodesLoadTestSetup
aws iam create-access-key --user-name newcodes-loadtest-deployer   # 출력의 AccessKeyId/SecretAccessKey를 즉시 안전한 곳에 저장
```

**`ecsTaskExecutionRole` 확인/생성** (계정에 ECS를 한 번도 안 써봤다면 없을 수 있음 — task definition들이 이 역할 이름을 참조):

```bash
aws iam get-role --role-name ecsTaskExecutionRole   # 있으면 여기서 끝, 없으면 아래 실행

aws iam create-role --role-name ecsTaskExecutionRole \
  --assume-role-policy-document file://load-test/fargate/ecs-task-execution-trust-policy.json
aws iam attach-role-policy --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy
```

이 역할 생성은 admin 권한이 필요해서(IAM 역할 생성) 루트/기존 admin으로 한 번만 하면 됨 — `newcodes-loadtest-deployer`는 이후 `PassRole`만으로 이 역할을 task에 붙여 쓸 수 있다.

## 0. 사전 확인

```bash
aws sts get-caller-identity          # ACCOUNT_ID 확인
aws ec2 describe-vpcs --query 'Vpcs[].{Id:VpcId,Cidr:CidrBlock,Default:IsDefault}'
# 운영 백엔드 EC2가 속한 VPC ID를 알아야 함 — 인스턴스에서:
aws ec2 describe-instances --filters "Name=tag:Name,Values=<운영-백엔드-인스턴스-태그>" \
  --query 'Reservations[].Instances[].{Id:InstanceId,Vpc:VpcId,Subnet:SubnetId,SG:SecurityGroups}'
```

## 1. mock 이미지 ECR에 push

```bash
ACCOUNT_ID=503561419347
REGION=ap-northeast-2

aws ecr create-repository --repository-name newcodes-llm-mock --region "$REGION"
aws ecr get-login-password --region "$REGION" | \
  docker login --username AWS --password-stdin "$ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com"

docker build -t "$ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com/newcodes-llm-mock:latest" load-test/mock
docker push "$ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com/newcodes-llm-mock:latest"
```

## 2. k6 부하테스트용 인프라(없다면 같이 세팅)

README "Fargate 실행 → 1회 세팅"과 동일. k6 task용 ECR/cluster/task-definition을 이미 만들어뒀다면 건너뛰고 클러스터명만 아래에서 재사용한다. NAT Gateway는 필요 없다 — k6 task도 mock 서비스도 public 서브넷 + `assignPublicIp=ENABLED`로 뜬다(고정 IP가 필요 없는 이유는 아래 7번 "시크릿 토큰" 참고).

```bash
aws ecr create-repository --repository-name newcodes-k6-sse --region "$REGION"
docker build -t "$ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com/newcodes-k6-sse:latest" \
  -f load-test/docker/Dockerfile load-test
docker push "$ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com/newcodes-k6-sse:latest"

aws ecs create-cluster --cluster-name newcodes-loadtest --region "$REGION"

# task-definition.json의 <ACCOUNT_ID>/<REGION> 치환 후 등록
aws ecs register-task-definition --cli-input-json file://load-test/fargate/task-definition.json --region "$REGION"
```

## 3. mock task definition 등록

`load-test/fargate/mock-task-definition.json`의 `<ACCOUNT_ID>`/`<REGION>`을 치환한 뒤:

```bash
aws ecs register-task-definition --cli-input-json file://load-test/fargate/mock-task-definition.json --region ap-northeast-2
```

## 4. Cloud Map(프라이빗 DNS)으로 고정 엔드포인트 만들기

Fargate 서비스는 재시작마다 IP가 바뀌므로, 운영 백엔드가 항상 같은 주소로 mock을 호출할 수 있게 Cloud Map 네임스페이스를 만든다. **운영 백엔드 EC2와 같은 VPC**에 만들어야 DNS가 해석된다.

```bash
VPC_ID=vpc-0ba62c17ad21f8f49

NS_OP=$(aws servicediscovery create-private-dns-namespace \
  --name loadtest.local --vpc "$VPC_ID" --region "$REGION" --query 'OperationId' --output text)
sleep 5
NS_ID=$(aws servicediscovery get-operation --operation-id "$NS_OP" --region "$REGION" \
  --query 'Operation.Targets.NAMESPACE' --output text)

SD_SERVICE_ARN=$(aws servicediscovery create-service \
  --name llm-mock --namespace-id "$NS_ID" \
  --dns-config "NamespaceId=$NS_ID,DnsRecords=[{Type=A,TTL=10}]" \
  --health-check-custom-config FailureThreshold=1 \
  --region "$REGION" --query 'Service.Arn' --output text)
```
NS_ID=ns-ht26gby5u6wtmfqd
aws cloud shell에서 함 

엔드포인트는 `http://llm-mock.loadtest.local:9099`로 고정된다.

## 5. 보안그룹

mock을 public 서브넷에 두더라도 실제 노출면은 이 보안그룹이 결정한다 — 인바운드를 운영 백엔드 SG로만 좁히면 public IP가 있어도 그 외에서는 접근 불가.

```bash
MOCK_SG=$(aws ec2 create-security-group --group-name newcodes-llm-mock-sg \
  --description "llm-mock inbound from prod backend only" --vpc-id "$VPC_ID" \
  --query 'GroupId' --output text)

# 운영 백엔드 SG에서만 9099 인바운드 허용
aws ec2 authorize-security-group-ingress --group-id "$MOCK_SG" \
  --protocol tcp --port 9099 --source-group sg-002078560e9771f7c

# 아웃바운드(ECR pull, CloudWatch Logs)는 기본 all-outbound로 충분 — 필요시 443만 허용하도록 좁혀도 됨
```

**`<운영-백엔드-SG_ID>` 알아내는 법:**

인스턴스 ID를 이미 안다면 바로 이걸로 조회 (가장 정확):
```bash
aws ec2 describe-instances --instance-ids i-0992927330c97f124 \
  --query 'Reservations[].Instances[].{Id:InstanceId,Vpc:VpcId,Subnet:SubnetId,SG:SecurityGroups}'
```
`SG` 배열의 `GroupId`(`sg-xxxxxxxx`)가 그것이다.

> **주의**: `--filters "Name=tag:Name,Values=<인스턴스 ID>"` 처럼 `tag:Name` 필터 값에 인스턴스 ID를 넣으면 빈 배열이 나온다 — 이 필터는 **Name 태그의 값**(예: `newcodes-backend-prod`)을 찾는 것이지 인스턴스 ID로 찾는 게 아니다. 인스턴스 ID를 알면 필터 없이 `--instance-ids`로 바로 조회한다.

Name 태그 값을 안다면:
```bash
aws ec2 describe-instances --filters "Name=tag:Name,Values=<운영-백엔드-인스턴스-태그>" \
  --query 'Reservations[].Instances[].{Id:InstanceId,SG:SecurityGroups}'
```

인스턴스 ID도 태그도 모르면, 운영 서버에 SSH로 접속해서 메타데이터로 확인하는 게 제일 확실:
```bash
curl -s http://169.254.169.254/latest/meta-data/security-groups
```

전체 목록에서 눈으로 찾는 경우:
```bash
aws ec2 describe-instances \
  --query 'Reservations[].Instances[].{Id:InstanceId,Name:Tags[?Key==`Name`]|[0].Value,PublicIp:PublicIpAddress,SG:SecurityGroups}' \
  --output table
```
`PublicIp`가 `newcodes.net`이 가리키는 IP와 일치하는 행을 찾는다.

주의: EC2에 SG가 여러 개 붙어 있을 수 있는데, `--source-group`에는 **애플리케이션이 실제로 쓰는 SG**(보통 default 말고 별도 앱 전용 SG)를 써야 한다 — default SG를 쓰면 다른 리소스까지 의도치 않게 mock에 접근 가능해질 수 있다.

**트러블슈팅 — `InvalidGroup.NotFound: ... two resources that belong to different networks`**

`authorize-security-group-ingress --source-group`은 **같은 VPC 안에서만** 동작한다. `MOCK_SG`를 만들 때 쓴 `$VPC_ID`와 운영 백엔드 SG가 속한 VPC가 다르면 이 에러가 난다. 확인:

```bash
aws ec2 describe-security-groups --group-ids "$MOCK_SG" --query 'SecurityGroups[0].VpcId'
aws ec2 describe-security-groups --group-ids sg-002078560e9771f7c --query 'SecurityGroups[0].VpcId'
```

두 값이 다르면 `$VPC_ID`가 잘못됐던 것 — 운영 백엔드 SG가 속한 VPC ID로 바로잡고 `MOCK_SG`를 그 VPC에 다시 생성한다:

```bash
aws ec2 delete-security-group --group-id "$MOCK_SG"   # 아직 ECS 서비스에 안 붙였다면 안전하게 삭제 가능

VPC_ID=vpc-0ba62c17ad21f8f49
MOCK_SG=$(aws ec2 create-security-group --group-name newcodes-llm-mock-sg \
  --description "llm-mock inbound from prod backend only" --vpc-id "$VPC_ID" \
  --query 'GroupId' --output text)

aws ec2 authorize-security-group-ingress --group-id "$MOCK_SG" \
  --protocol tcp --port 9099 --source-group sg-002078560e9771f7c
```

**후속 트러블슈팅 — `InvalidGroup.Duplicate` / `InvalidGroupId.Malformed`**

위 재생성을 시도하다 `create-security-group`이 `InvalidGroup.Duplicate`("already exists for VPC ...")로 실패하면, 그 실패한 커맨드가 `$MOCK_SG`에 빈 문자열을 할당하고, 그 뒤 `authorize-security-group-ingress --group-id ""`가 `InvalidGroupId.Malformed`로 연쇄 실패한다 — 두 에러는 원인이 하나다.

`newcodes-llm-mock-sg`가 그 VPC에 이미 있다는 건, 애초의 "different networks" 에러 원인이 `MOCK_SG` 쪽이 아니라 **source-group으로 넣은 SG ID 쪽이 다른 VPC**였을 가능성을 시사한다. 재생성 대신 기존 SG를 재사용하고, 프로덕션 인스턴스에서 직접 VPC/SG를 다시 대조한다:

```bash
# 기존 SG 재사용 (재생성 불필요)
MOCK_SG=$(aws ec2 describe-security-groups \
  --filters "Name=group-name,Values=newcodes-llm-mock-sg" "Name=vpc-id,Values=$VPC_ID" \
  --query 'SecurityGroups[0].GroupId' --output text)
echo "MOCK_SG=$MOCK_SG"

# 프로덕션 인스턴스의 실제 VPC/SG 재확인
aws ec2 describe-instances --instance-ids sg-002078560e9771f7c \
  --query 'Reservations[].Instances[].{Vpc:VpcId,SG:SecurityGroups}'
```

- 여기 나온 `Vpc`가 `$VPC_ID`와 다르면 → `MOCK_SG`를 만든 VPC 자체가 틀린 것 (그 VPC로 재생성 필요)
- `Vpc`는 같은데 `SG` 목록에 source-group으로 쓰려던 ID가 없다면 → 그 ID가 이 인스턴스 것이 아님 — 여기 나온 `SG` 목록에서 실제 ID를 다시 골라 사용한다

## 6. ECS 서비스 생성 (평시 desired-count 0)

public 서브넷 + `assignPublicIp=ENABLED`로 띄운다 (NAT 없이 ECR pull/CloudWatch Logs 전송이 되려면 인터넷 경로가 필요 — 5번 보안그룹이 실제 접근 통제를 담당).

`$SD_SERVICE_ARN`이 새 셸 세션이라 비어있다면(4번에서 만든 `$NS_ID`는 알고 있는 상태), 재생성 없이 다시 조회:

```bash
NS_ID=ns-ht26gby5u6wtmfqd
REGION=ap-northeast-2

SD_SERVICE_ARN=$(aws servicediscovery list-services --region "$REGION" \
  --filters "Name=NAMESPACE_ID,Values=$NS_ID,Condition=EQ" \
  --query "Services[?Name=='llm-mock'].Arn" --output text)
echo "SD_SERVICE_ARN=$SD_SERVICE_ARN"
```

네임스페이스 ID 없이 서비스 이름만으로 바로 찾아도 된다:
```bash
aws servicediscovery list-services --region ap-northeast-2 \
  --query "Services[?Name=='llm-mock'].{Id:Id,Arn:Arn}"
```

```bash
aws ecs create-service \
  --cluster newcodes-loadtest \
  --service-name llm-mock \
  --task-definition newcodes-llm-mock \
  --desired-count 0 \
  --launch-type FARGATE \
  --network-configuration "awsvpcConfiguration={subnets=[subnet-09aae0b5172d9c43f],securityGroups=[$MOCK_SG],assignPublicIp=ENABLED}" \
  --service-registries "registryArn=$SD_SERVICE_ARN" \
  --region ap-northeast-2
```

`desired-count 0`으로 만들어두면 서비스는 등록만 되고 태스크는 뜨지 않는다 — `run-prod-test.sh`가 테스트마다 1로 올렸다가 종료 시 다시 0으로 내리므로, 이 단계에서 직접 1로 켤 필요는 없다(스모크 테스트로 기동을 바로 확인하고 싶다면 아래처럼 수동으로 한 번 올려볼 수 있다).

수동으로 기동 상태만 확인해보고 싶다면:

```bash
aws ecs update-service --cluster newcodes-loadtest --service llm-mock --desired-count 1 --region ap-northeast-2
```

기동 확인:

```bash
aws ecs describe-services --cluster newcodes-loadtest --services llm-mock \
  --query 'services[0].{desired:desiredCount,running:runningCount}'
```

확인이 끝났으면 다시 0으로 내려 평시 미기동 상태로 돌려놓는다(어차피 `run-prod-test.sh`가 자동으로 관리하므로 깜빡해도 다음 테스트 실행 시 알아서 정리되긴 하지만, 세팅 직후에는 명시적으로 내려두는 편이 안전하다):

```bash
aws ecs update-service --cluster newcodes-loadtest --service llm-mock --desired-count 0 --region ap-northeast-2
```

## 7. 시크릿 토큰 생성

Fargate 태스크는 매번 임의 public IP를 쓰므로(NAT 없음) 고정 IP allowlist가 성립하지 않는다 — 대신 nginx가 `X-LoadTest-Token` 헤더 값으로 bypass 여부를 판별한다. 토큰을 하나 생성해둔다(아래에서 재사용):

```bash
openssl rand -hex 24
```

## 8. 운영 호스트 `.env`에 상시 값 추가

운영 서버(`~/small-town`)의 `.env`(git 미추적)에 추가 — `<TOKEN>`은 7번에서 생성한 값과 동일해야 함:

```
RAG_CHAT_LOADTEST_ENABLED=true
RAG_CHAT_LOADTEST_BYPASS_TOKEN=<TOKEN>
RAG_LOADTEST_BEDROCK_ENDPOINT=http://llm-mock.loadtest.local:9099
CLOVA_LOADTEST_ENDPOINT=http://llm-mock.loadtest.local:9099/v1/api-tools/embedding/v2
```

## 9. nginx에 시크릿 토큰 등록 (+ docker-compose 볼륨 최초 1회 반영)

운영 서버(`~/small-town`)에서, git에는 안 올라가는 `nginx/loadtest_token.conf`를 직접 만든다 — `<TOKEN>`은 7번과 동일한 값:

```bash
cp nginx/loadtest_token.conf.example nginx/loadtest_token.conf
# <SECRET_TOKEN>을 <TOKEN>으로 교체 (에디터로 직접, 대화창/git에 토큰 노출 금지)
```

이 문서 배포분(`docker-compose.yml`에 `nginx/loadtest_token.conf` 볼륨 마운트 추가, `nginx/default.conf`에서 IP allowlist 제거)이 main에 merge되면, CI가 `git pull` → `deploy.sh deploy`를 실행한다. 다만 **새 볼륨 마운트는 `nginx -s reload`로는 반영되지 않는다** — nginx 컨테이너를 한 번 재생성해야 한다. 배포 직후 운영 서버에서 한 번만:

```bash
docker compose up -d nginx   # 새 볼륨 마운트 반영 (재생성). loadtest_token.conf가 이 시점에 반드시 존재해야 함
```

이후 토큰을 바꾸고 싶으면 파일 내용만 고치고 `docker exec newcodes-nginx nginx -s reload`면 충분(재생성 불필요). 배포 직후 확인:

```bash
curl -s -o /dev/null -w "%{http_code}\n" https://newcodes.net/api/rag/answer/loadtest   # 토큰 없이 호출 시 403이면 정상
```

토큰이 있는 상태에서 실제 흐름을 확인하려면 반드시 **POST + JSON 바디**로 호출한다 (`GET`으로 테스트하면 `HttpRequestMethodNotSupportedException`이 `RestApiExceptionHandler`의 catch-all에 걸려 405 대신 500이 찍히는 기존 버그가 있음 — nginx bypass/토큰 자체는 정상이어도 500처럼 보이니 혼동하지 말 것):

```bash
curl -N -s -X POST https://newcodes.net/api/rag/answer/loadtest \
  -H "X-LoadTest-Token: <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"question":"테스트 질문입니다"}'
# → SSE 스트림으로 mock 응답이 흘러나오면 정상. 403만 아니면 게이트는 통과한 것.
```

## 10. `load-test/fargate/env` 채우기

```bash
cp load-test/fargate/env.example load-test/fargate/env
```

`LT_CLUSTER`, `LT_SUBNETS_PUBLIC`, `LT_SG`(k6용, 기존), `LT_BASE_URL=https://newcodes.net`, `LT_BYPASS_TOKEN`(7번과 동일한 값), `LT_MOCK_CLUSTER`(비우면 `LT_CLUSTER`와 동일), `LT_MOCK_SERVICE=llm-mock` 값을 채운다.

여기까지 끝나면 `./load-test/fargate/run-prod-test.sh -s rag-answer -v 1 -d 30s`로 스모크 테스트할 수 있다.

## 유입 제어 검증 런 (11장 조치의 실측)

`RagConcurrencyLimiter`(상한 45)가 실제로 셰딩하는지 확인한다. **배포 후 가장 먼저 돌릴 런이다** —
이게 통과해야 그 위의 용량 작업이 의미를 갖는다.

> 🔴 **시나리오를 고쳤으면 k6 이미지를 먼저 다시 빌드해 push할 것.**
> `docker/Dockerfile`이 `scenarios/`·`lib/`·`data/`를 이미지에 굽기 때문에, 로컬 파일만 고치고
> 실행하면 Fargate는 **ECR의 옛 코드를 조용히 그대로 돌린다.** 절차는
> [`../README.md`](../README.md)의 "실행" 절 참고.
> **2026-08-29에 실제로 이걸 밟았다** — 429 백오프를 넣고 이미지를 안 굽고 돌려서 거절이
> 초당 5건 대신 78건으로 나왔다(재시도 폭풍). 서버측 판정은 무사했지만 `shed` 건수는 못 썼다.
> (2026-08-08에도 같은 함정에 걸린 기록이 README에 있다 — 두 번째다.)

```bash
cd load-test/fargate
./run-prod-test.sh -s ramp-limit-finder-rag -e MODE=cache-miss -e VU_LEVELS=45,70,140

VU_LEVELS=45,70,140 python3 ../scripts/collect-rag-results.py <testid>
```

기본 mock(페이싱 유지)으로 돈다 — 여기서 보려는 건 코어 비용이 아니라 거절 동작이다.

| 레벨 | 기대 |
|---|---|
| 45 | 상한과 같음. `shed` 소수, `done` 정상, RPS 약 2.0 |
| 70 | **`shed`가 꾸준히 나오고 `bad`는 0**. `llm_stream max ≤ 45` |
| 140 | 같음. 붕괴(5xx/OOM/재기동) 없음 |

수집 스크립트가 자동으로 검증한다 — `bad`와 `shed`를 분리해 세고(429는 실패가 아니다),
다음이 깨지면 경고를 찍는다:

- `llm_stream max > 상한` → permit 누수이거나 리미터를 안 타는 경로(관리자 RAG 테스트 페이지)가 같이 돈 것
- `상한 >= 풀` → 초과분이 429가 아니라 풀 앞의 조용한 대기가 된다
- `VU > 상한인데 429가 0건` → 리미터가 안 걸렸다(배포/bypass 경로 확인)

동시에 볼 것: `process_uptime_seconds`가 **리셋되지 않아야 한다**(런 3에서는 12분간 세 번 재기동했다).
힙 committed는 310MB 부근에서 평평해야 한다.

> 시나리오는 429를 받으면 `Retry-After`만큼(기본 5초) 쉰다. 없으면 거절된 VU가 즉시 재발사해
> VU가 더 이상 "동시 사용자 수"를 뜻하지 않게 되고, 재시도 폭풍 자체가 부하가 된다
> (런 3의 http_502 38,543건이 그 모양이었다). 의도적으로 때리는 변형은 `-e REJECT_BACKOFF_MS=0`.

---

## 런 4 — 토큰 페이싱 제거 모드 (서버 코어 한계 측정)

기본 mock은 실제 LLM 지연을 재현하도록 인위적 대기를 넣는다(전처리 2,075ms / TTFT 1,650ms /
토큰 간 44ms). 이 상태로는 **서버가 실제로 일하는 비용을 볼 수 없다** — iteration 21초의 대부분이
유휴라, 처리량을 올리려면 동시 스트림을 늘려야 하고 그러면 힙이 먼저 터진다(결과 문서 5장).

`mock-task-definition-nopacing.json`은 유휴만 걷어낸 변형이다:

| | 기본 | nopacing |
|---|---|---|
| `MOCK_TOKEN_INTERVAL_MS` / `JITTER` | 44 / 14 | **0 / 0** |
| `MOCK_PREPROCESS_MEDIAN_MS` / `SIGMA` | 2075 / 0.4 | **50 / 0.1** |
| `MOCK_TTFT_MEDIAN_MS` / `SIGMA` | 1650 / 0.5 | **50 / 0.1** |
| `MOCK_ANSWER_TOKENS` | 410 | **410 (그대로)** |

**청크 수를 그대로 두는 게 핵심이다.** 없애야 하는 건 *기다림*이지 *일*이 아니다 —
요청당 SSE 릴레이 410회와 retrieval의 DB 비용은 유지된 채 유휴만 빠진다.

**왜 이게 힙 붕괴 없이 한계를 보나.** `L = λ × W`에서 W가 21초 → 약 1초가 되므로, 같은 처리량 λ를
**1/20의 동시성 L**로 낼 수 있다. 20 RPS를 보려면 기본 mock은 L≈420(불가능, 힙 502MB 초과)이지만
nopacing은 L≈20(힙 203MB)이면 된다. 그래서 **동시성이 아니라 처리량 축을 탐색할 수 있다.**

### 실행 절차

> 🔴 **시나리오를 고쳤으면 k6 이미지를 먼저 다시 빌드해 push할 것.**
> `docker/Dockerfile`이 `scenarios/`·`lib/`·`data/`를 이미지에 굽기 때문에, 로컬 파일만 고치고
> 실행하면 Fargate는 **ECR의 옛 코드를 조용히 그대로 돌린다.** 절차는
> [`../README.md`](../README.md)의 "실행" 절 참고.
> **2026-08-29에 실제로 이걸 밟았다** — 429 백오프를 넣고 이미지를 안 굽고 돌려서 거절이
> 초당 5건 대신 78건으로 나왔다(재시도 폭풍). 서버측 판정은 무사했지만 `shed` 건수는 못 썼다.
> (2026-08-08에도 같은 함정에 걸린 기록이 README에 있다 — 두 번째다.)

```bash
cd load-test/fargate
set -a; . ./env; set +a

# 1) nopacing task definition 등록 (최초 1회, 또는 파일 수정 후)
aws ecs register-task-definition   --cli-input-json file://mock-task-definition-nopacing.json --region ap-northeast-2

# 2) mock 서비스를 nopacing으로 전환 (서비스는 그대로, task def만 교체)
aws ecs update-service --cluster "${LT_MOCK_CLUSTER:-$LT_CLUSTER}"   --service "${LT_MOCK_SERVICE:-llm-mock}"   --task-definition newcodes-llm-mock-nopacing --region ap-northeast-2

# 3) 사다리 실행 — VU 레벨과 창 길이를 반드시 같이 내린다 (아래 주의 참고)
# (-v/-d는 이 시나리오가 안 읽는다 — 사다리는 VU_LEVELS/LEVEL_DURATION만 본다)
./run-prod-test.sh -s ramp-limit-finder-rag -e MODE=cache-miss \
  -e VU_LEVELS=5,10,20,35 -e LEVEL_DURATION=3m30s -e LEVEL_GAP=240

# 4) 수집 (시나리오에 넘긴 값과 같아야 한다)
VU_LEVELS=5,10,20,35 LEVEL_GAP=240 LEVEL_DURATION=210 TRANSIENT_SEC=60 \
  python3 ../scripts/collect-rag-results.py <testid>

# 5) 되돌리기 — 잊으면 이후 모든 런이 nopacing으로 돈다
aws ecs update-service --cluster "${LT_MOCK_CLUSTER:-$LT_CLUSTER}" \
  --service "${LT_MOCK_SERVICE:-llm-mock}" \
  --task-definition newcodes-llm-mock --region ap-northeast-2
```

> ⚠️ **5번을 잊는 것이 이 절차의 가장 큰 위험이다.** `run-prod-test.sh`는 desired-count만
> 되돌리고 task definition은 안 건드린다. 되돌리지 않으면 다음 용량 사다리가 조용히 nopacing으로
> 돌아 "용량이 10배 늘었다"는 가짜 결과가 나온다. 런 직후 반드시 확인:
> ```bash
> aws ecs describe-services --cluster "${LT_MOCK_CLUSTER:-$LT_CLUSTER}" \
>   --services "${LT_MOCK_SERVICE:-llm-mock}" --query 'services[0].taskDefinition' --output text
> ```

### 사다리·창 길이 주의

기본값(VU 10/20/35/55, 레벨 7분, 간격 480초)은 **iteration 21초 기준**이다. nopacing에서는
iteration이 약 1초라 그대로 쓰면 레벨당 완료가 수만 건이 되어 과잉이고 창도 낭비다.
검색과 같은 210초/240초로 내리고, 과도구간도 `TRANSIENT_SEC=60`으로 줄인다
(RAG의 120초는 전 VU가 21초에 첫 완료를 한꺼번에 쏟는 물결 때문인데, 1초면 그 물결이 없다).

VU 상한을 35로 잡은 이유: 유입 제어 상한이 45라 그 위로 가면 429가 섞여 코어 한계 측정이 흐려진다.
**35까지에서 무릎이 안 보이면** admin(`/admin/search/weights` 하단 RAG 섹션)에서 상한을 올린 뒤
VU를 더 밀어야 한다 — nopacing은 W가 짧아 힙 여유가 크므로(L=100이어도 353MB) 안전하다.
올린 상한은 런이 끝나면 45로 되돌릴 것.

### 무엇을 보나

기본 mock 런과 나란히 놓고 **`core-s/req`와 `blks/req`가 일치하는지**부터 본다 — 이 둘은
시간 불변량이라(결과 문서 7.2) 두 모드에서 같아야 한다. 다르면 nopacing이 일까지 줄인 것이다.
그 다음이 본론: DB CPU / appCPU가 어느 RPS에서 포화하는가. 결과 문서 10.7의 병목 순위표가
3순위로 적어 둔 **"DB CPU (9.2 RPS)"는 유도 과정이 어디에도 없는 추정치**다 — 이 런이 그걸 실측한다.

---

## mock 이미지 갱신(코드 수정 후)

```bash
docker build -t "$ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com/newcodes-llm-mock:latest" load-test/mock
docker push "$ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com/newcodes-llm-mock:latest"
aws ecs update-service --cluster newcodes-loadtest --service llm-mock --force-new-deployment --region "$REGION"
```

평시 desired-count 0(미기동) 상태라면 `force-new-deployment`는 사실상 no-op이다 — 다음 `run-prod-test.sh` 실행이 태스크를 새로 띄울 때 `:latest` 태그를 다시 pull하므로 별도 조치 없이 최신 이미지가 반영된다. 테스트가 실제로 돌고 있는 도중(desired-count 1)에 이미지를 갱신하는 경우에만 위 커맨드가 의미 있다.

## 트러블슈팅 — `UnknownHostException: llm-mock.loadtest.local` (앱 로그, RAG 호출 시)

ECS 태스크는 `HEALTHY`고 Cloud Map 네임스페이스/보안그룹도 다 맞는데 앱에서 이 호스트를 못 찾는다면, 백엔드가 **Docker 컨테이너 안**에서 돈다는 걸 봐야 한다 — Ubuntu 호스트는 보통 `/etc/resolv.conf`가 `systemd-resolved`(127.0.0.53, localhost)를 가리키는데, Docker는 이 주소가 컨테이너 네임스페이스에서 접근 불가능한 걸 감지하면 컨테이너에는 대신 공용 DNS(8.8.8.8 등)를 자동으로 넣어버린다. 그러면 EC2 호스트 자체는 VPC 프라이빗 DNS(`loadtest.local`)를 잘 풀어도, 그 안의 백엔드 컨테이너는 절대 못 푼다.

운영 서버에서 확인 (파괴적이지 않음):

```bash
echo "--- 호스트의 resolv.conf ---"
cat /etc/resolv.conf

echo "--- 호스트에서는 풀리는지 ---"
getent hosts llm-mock.loadtest.local

echo "--- 컨테이너 안의 resolv.conf ---"
docker exec newcodes-backend-green cat /etc/resolv.conf

echo "--- 컨테이너 안에서는 풀리는지 ---"
docker exec newcodes-backend-green getent hosts llm-mock.loadtest.local
```

호스트는 풀리는데 컨테이너 안은 안 풀리거나, 컨테이너 `resolv.conf`에 공용 DNS만 있으면 이 원인이 맞다. `docker-compose.yml`의 `newcodes-backend-blue`/`newcodes-backend-green` 서비스에 VPC가 어떤 VPC/서브넷에서든 공통으로 제공하는 Route53 Resolver 링크-로컬 주소를 명시적으로 지정해두면 해결된다:

```yaml
    dns:
      - 169.254.169.253
```

새 `dns:` 키 추가는 컨테이너 재생성이 필요하다 — 배포 파이프라인을 타거나, 운영 서버에서 직접:

```bash
docker compose up -d newcodes-backend-green   # 우선 green만 재생성해 확인
```

### 후속 진단 — 호스트에서도 `getent hosts`가 안 풀리는 경우

위 진단에서 **호스트조차** `llm-mock.loadtest.local`을 못 풀면(컨테이너만의 문제가 아니라면), `systemd-resolved`가 애초에 VPC DHCP가 내려준 리졸버를 안 쓰고 있는 것일 수 있다. 이 경우 `169.254.169.253`(VPC Route53 Resolver, 어떤 서브넷에서든 공통으로 접근 가능)에 직접 질의해서 원인을 좁힌다:

```bash
echo "--- systemd-resolved가 실제 쓰는 업스트림 DNS ---"
resolvectl status | grep -A5 "Link.*eth0\|Current DNS"

echo "--- VPC Route53 Resolver(169.254.169.253)에 직접 질의 ---"
dig @169.254.169.253 llm-mock.loadtest.local +short || nslookup llm-mock.loadtest.local 169.254.169.253
```

(`dig`/`nslookup`이 없으면 `sudo apt-get install -y dnsutils`)

- **`169.254.169.253`로 직접 질의했을 때 풀림** → `systemd-resolved`가 VPC 리졸버를 안 쓰고 있다는 뜻 확정. 위 `dns: [169.254.169.253]` 수정이 정확한 해결책 — 호스트 리졸버를 거치지 않고 컨테이너가 VPC 리졸버로 바로 질의하게 되므로 문제가 없어진다.
- **`169.254.169.253`로도 안 풀림** → DNS 설정 문제가 아니라 Cloud Map 프라이빗 호스팅존이 이 VPC에 실제로 연결(associate)됐는지부터 다시 확인해야 한다 (`aws servicediscovery get-namespace`, Route53 콘솔에서 프라이빗 호스팅존의 VPC associations 확인).

**실제 확인된 사례 (2026-08)**: `resolvectl status`는 `Current DNS Server: 10.0.0.2`(정상 VPC 리졸버)를 가리키고 있었는데도 호스트 `getent hosts`는 실패, `dig @169.254.169.253 llm-mock.loadtest.local`은 `10.0.11.232`로 정상 응답. 즉 VPC 리졸버·Cloud Map 프라이빗 DNS는 완전히 정상이고, `systemd-resolved`가 `loadtest.local` 도메인에 대한 라우팅 도메인을 갖고 있지 않아 해당 질의를 10.0.0.2로 포워딩하지 않는 것이 원인이었다(`ap-northeast-2.compute.internal` search domain 외의 프라이빗 존은 라우팅 대상에서 빠짐). `dns: [169.254.169.253]`로 systemd-resolved를 완전히 우회하는 것이 맞는 해결책 — 원인 확정.
