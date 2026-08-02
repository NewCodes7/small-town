# 상시 LLM Mock 서비스 — 최초 1회 세팅

`run-prod-test.sh`(원커맨드 부하테스트 스크립트)가 동작하려면 먼저 이 문서의 절차를 **한 번만** 실행해 둬야 한다. 이후에는 다시 반복할 필요 없음 — mock은 상시(desired count 1) ECS 서비스로 떠 있고, 게이트/nginx bypass도 상시 활성 상태로 유지된다.

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
ACCOUNT_ID=<ACCOUNT_ID>
REGION=<REGION>

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
aws ecs register-task-definition --cli-input-json file://load-test/fargate/mock-task-definition.json --region "$REGION"
```

## 4. Cloud Map(프라이빗 DNS)으로 고정 엔드포인트 만들기

Fargate 서비스는 재시작마다 IP가 바뀌므로, 운영 백엔드가 항상 같은 주소로 mock을 호출할 수 있게 Cloud Map 네임스페이스를 만든다. **운영 백엔드 EC2와 같은 VPC**에 만들어야 DNS가 해석된다.

```bash
VPC_ID=<운영-백엔드-VPC_ID>

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

엔드포인트는 `http://llm-mock.loadtest.local:9099`로 고정된다.

## 5. 보안그룹

mock을 public 서브넷에 두더라도 실제 노출면은 이 보안그룹이 결정한다 — 인바운드를 운영 백엔드 SG로만 좁히면 public IP가 있어도 그 외에서는 접근 불가.

```bash
MOCK_SG=$(aws ec2 create-security-group --group-name newcodes-llm-mock-sg \
  --description "llm-mock inbound from prod backend only" --vpc-id "$VPC_ID" \
  --query 'GroupId' --output text)

# 운영 백엔드 SG에서만 9099 인바운드 허용
aws ec2 authorize-security-group-ingress --group-id "$MOCK_SG" \
  --protocol tcp --port 9099 --source-group <운영-백엔드-SG_ID>

# 아웃바운드(ECR pull, CloudWatch Logs)는 기본 all-outbound로 충분 — 필요시 443만 허용하도록 좁혀도 됨
```

## 6. ECS 서비스 생성 (상시 desired-count 1)

public 서브넷 + `assignPublicIp=ENABLED`로 띄운다 (NAT 없이 ECR pull/CloudWatch Logs 전송이 되려면 인터넷 경로가 필요 — 5번 보안그룹이 실제 접근 통제를 담당).

```bash
aws ecs create-service \
  --cluster newcodes-loadtest \
  --service-name llm-mock \
  --task-definition newcodes-llm-mock \
  --desired-count 1 \
  --launch-type FARGATE \
  --network-configuration "awsvpcConfiguration={subnets=[<PUBLIC_SUBNET_ID>],securityGroups=[$MOCK_SG],assignPublicIp=ENABLED}" \
  --service-registries "registryArn=$SD_SERVICE_ARN" \
  --region "$REGION"
```

기동 확인:

```bash
aws ecs describe-services --cluster newcodes-loadtest --services llm-mock \
  --query 'services[0].{desired:desiredCount,running:runningCount}'
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
curl -s -o /dev/null -w "%{http_code}\n" -H "X-LoadTest-Token: <TOKEN>" https://newcodes.net/api/rag/answer/loadtest
# → RAG_CHAT_LOADTEST_ENABLED=true라면 404 대신 다른 응답(POST 바디 없어 400 등)이면 정상, 403만 아니면 됨
```

## 10. `load-test/fargate/env` 채우기

```bash
cp load-test/fargate/env.example load-test/fargate/env
```

`LT_CLUSTER`, `LT_SUBNETS_PUBLIC`, `LT_SG`(k6용, 기존), `LT_BASE_URL=https://newcodes.net`, `LT_BYPASS_TOKEN`(7번과 동일한 값), `LT_MOCK_CLUSTER`(비우면 `LT_CLUSTER`와 동일), `LT_MOCK_SERVICE=llm-mock` 값을 채운다.

여기까지 끝나면 `./load-test/fargate/run-prod-test.sh -s rag-answer -v 1 -d 30s`로 스모크 테스트할 수 있다.

## mock 이미지 갱신(코드 수정 후)

```bash
docker build -t "$ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com/newcodes-llm-mock:latest" load-test/mock
docker push "$ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com/newcodes-llm-mock:latest"
aws ecs update-service --cluster newcodes-loadtest --service llm-mock --force-new-deployment --region "$REGION"
```
