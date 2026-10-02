# 임시 EC2 벤치 — dev 사양(t3.small)에서 k6 부하 재현 (MSG-609)

노트북이 아니라 dev 와 같은 사양에서 부하를 재는 절차. 같은 VPC 에 앱 박스(t3.small)와 부하 발생기
박스(t3.medium)를 띄우고 사설 IP 로만 때린다. 첫 실측과 결과는 `docs/reports/2026-09-29-cloud-load-test-t3small.md`.

## 파일

| 파일 | 역할 |
|---|---|
| `ud-app.sh` | 앱 박스 user-data: docker·compose·ECR credential helper·psql·redis-cli, swap 2G(dev 와 동일) |
| `ud-load.sh` | 부하기 박스 user-data: docker·compose·k6(공식 apt 저장소) |
| `bench.env` | 앱 env. `dev` 프로파일 + 로컬 DB 계정 + 로깅 INFO·워밍업/AI/알림/CloudFront off. 비밀값은 벤치 전용 더미 |
| `docker-compose.bench-app.yml` | 앱 컨테이너(ECR 이미지, host 네트워크, `bench.env`). DB·Redis 는 레포 `docker-compose.yml` |
| `app-sampler.sh` | 앱 박스 5초 자원 샘플러 → `~/app-samples.csv` (loadavg·컨테이너 CPU/메모리·PG 세션·가용 메모리) |
| `run-phase1.sh` | 뷰포트 s1·s3·s4 + 핫구역 expiry·cap 순차 실행, 회차 사이 60초 휴지 |
| `run-phase2.sh` | 뷰포트 s1 재실행 / 도감 요약 smoke·ramp (인자 `s1` `s1-40` `summary` `summary-smoke`) |

## 절차 (2026-09-29 실측 순서, 소요 약 10분 + 시드)

```bash
export AWS_PROFILE=soma AWS_DEFAULT_REGION=ap-northeast-2
MYIP=$(curl -s checkip.amazonaws.com)/32
VPC=vpc-0079b780d6767bc1f; SUBNET=subnet-0d065d025fa7884b7        # dev 와 같은 2a 퍼블릭 서브넷
AMI=$(aws ssm get-parameter --name /aws/service/canonical/ubuntu/server/24.04/stable/current/amd64/hvm/ebs-gp3/ami-id --query Parameter.Value --output text)

# 1) SG — 22/3000/9090 은 내 IP, 박스끼리는 전부 허용
SG=$(aws ec2 create-security-group --group-name fillmap-bench-sg --description "temporary load-test bench" --vpc-id $VPC --query GroupId --output text)
aws ec2 authorize-security-group-ingress --group-id $SG --ip-permissions \
  "IpProtocol=tcp,FromPort=22,ToPort=22,IpRanges=[{CidrIp=$MYIP}]" \
  "IpProtocol=tcp,FromPort=3000,ToPort=3000,IpRanges=[{CidrIp=$MYIP}]" \
  "IpProtocol=tcp,FromPort=9090,ToPort=9090,IpRanges=[{CidrIp=$MYIP}]" \
  "IpProtocol=-1,UserIdGroupPairs=[{GroupId=$SG}]"

# 2) 인스턴스 — 앱은 dev 인스턴스 역할(ECR pull)을 붙인다. 둘 다 Temporary 태그
aws ec2 run-instances --image-id $AMI --instance-type t3.small --key-name fillmap-key-soma --subnet-id $SUBNET \
  --security-group-ids $SG --iam-instance-profile Name=FillMapEc2DevRole --associate-public-ip-address \
  --block-device-mappings 'DeviceName=/dev/sda1,Ebs={VolumeSize=30,VolumeType=gp3,DeleteOnTermination=true}' \
  --credit-specification CpuCredits=unlimited --user-data file://load-test/bench-ec2/ud-app.sh \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=fillmap-bench-app},{Key=Project,Value=fillmap-bench},{Key=Temporary,Value=true}]'
aws ec2 run-instances --image-id $AMI --instance-type t3.medium --key-name fillmap-key-soma --subnet-id $SUBNET \
  --security-group-ids $SG --associate-public-ip-address \
  --block-device-mappings 'DeviceName=/dev/sda1,Ebs={VolumeSize=20,VolumeType=gp3,DeleteOnTermination=true}' \
  --credit-specification CpuCredits=unlimited --user-data file://load-test/bench-ec2/ud-load.sh \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=fillmap-bench-load},{Key=Project,Value=fillmap-bench},{Key=Temporary,Value=true}]'
# user-data 가 끝나면 ~/READY 가 생긴다 (약 2~3분)
```

```bash
# 3) 앱 박스 — 파일 복사 후 DB·Redis·앱 기동 (ECR 로그인은 credsStore 가 인스턴스 역할로 자동)
scp docker-compose.yml load-test/bench-ec2/{bench.env,docker-compose.bench-app.yml,app-sampler.sh} ubuntu@$APP:~/
scp -r load-test scripts/bench-msg596.sql scripts/bench-msg596-cleanup.sql ubuntu@$APP:~/
ssh ubuntu@$APP 'docker compose up -d postgres local-cache-server && TAG=sha-xxxxxxx docker compose -f docker-compose.bench-app.yml up -d'
#    regions 는 시더가 없다 — dev DB 에서 pg_dump 로 옮긴다 (3,558행, 24MB)
ssh ubuntu@52.79.187.34 'docker exec fillmap-postgres-dev pg_dump -U $(docker exec fillmap-postgres-dev printenv POSTGRES_USER) -d fillmap -t regions -a --no-owner' > regions.sql
scp regions.sql ubuntu@$APP:~/ && ssh ubuntu@$APP 'docker exec -i fillmap-postgres psql -U user -d fillmap -q < regions.sql'
#    시드 (5179 인코딩·Redis 핫구역까지) — 30만/6만/500 은 t3.small 2GB 에 맞춘 값, 약 3분
ssh ubuntu@$APP './load-test/seed-dev-data.sh 300000 60000 500'
#    벤치 사용자: dev 소셜 로그인 모의로 만들고 격자 1/3 을 점령시킨다 → 토큰은 ~/bench.token
ssh ubuntu@$APP 'curl -s -X POST localhost:8080/api/auth/dev/social-login -H "Content-Type: application/json" \
  -d "{\"provider\":\"KAKAO\",\"oid\":\"bench-oid\",\"email\":\"bench@fillmap.kr\",\"nickname\":\"bench\"}" | jq -r .data.accessToken > bench.token'
#    INSERT INTO user_grids ... FROM grids g WHERE (g.grid_y+g.grid_x)%3=0 → VACUUM ANALYZE (bench-user-grids.sql 참고)
ssh ubuntu@$APP 'nohup ./app-sampler.sh >/dev/null 2>&1 &'

# 4) 부하기 박스 — k6 스크립트·관측 스택·러너
scp -r load-test/k6 monitoring load-test/bench-ec2/run-phase*.sh bench.token ubuntu@$LOAD:~/
ssh ubuntu@$LOAD 'cd monitoring && sed -i "s#host.docker.internal:8080#10.0.1.APP:8080#" prometheus/prometheus.yml && POSTGRES_HOST=10.0.1.APP docker compose up -d'
ssh ubuntu@$LOAD 'nohup ./run-phase1.sh > phase1.out 2>&1 &'       # ~20분. 결과 ~/results/*.summary.json + timeline.log
#    도감 요약: 앱 박스에서 bench-msg596.sql keep=1 적재(10분+) 후 run-phase2.sh summary-smoke → summary
```

```bash
# 5) 정리 — 결과를 load-test/evidence/<날짜>/ 로 가져온 뒤
aws ec2 terminate-instances --instance-ids <app> <load>
aws ec2 wait instance-terminated --instance-ids <app> <load> && aws ec2 delete-security-group --group-id $SG
```

## 주의

- `viewport-ab-benchmark.js` 의 `strategy=A|B` 는 현재 컨트롤러에 없는 파라미터라 두 시나리오가 같은 쿼리의
  반복 측정이다. 응답 체크는 2026-09-29 에 현재 계약(`data.grids`)으로 고쳤다.
- `hotzone-benchmark.js` 의 서울 뷰포트는 `seed-dev-data.sh` 의 도심 집중 시드와 겹치는 면적이 작아 응답의
  92%가 빈 뷰포트다(지연 측정엔 영향 없음, `hotzone_empty_rate` 임계만 깨진다). 구 `seed-hotzone.sh` 는
  구 위경도 인코딩이라 5179 앱에서는 격자가 하나도 안 맞는다 — 쓰지 말 것.
- Prometheus 5초 scrape 는 앱이 포화되면 빠진다. 포화 구간 자원은 `app-sampler.sh` CSV 로 본다.
- `/actuator/metrics` 는 인증이 걸려 401 이다(`/actuator/prometheus` 만 열림). 샘플러가 그쪽을 안 쓰는 이유.
- 앱 박스 PostgreSQL 은 컨테이너 기본값(shared_buffers 128MB·work_mem 4MB)이다. dev 컨테이너도 같다.
- 비용: t3.small $0.026/h + t3.medium $0.052/h + gp3 50GB. 2026-09-29 회차는 종합 문서 "비용" 절 참고.

## 결과 표·그래프 재생성

```bash
python3 load-test/bench-ec2/k6-summarize.py load-test/evidence/2026-09-29          # k6 요약 JSON → 한 줄 요약
python3 load-test/bench-ec2/window-stats.py load-test/evidence/2026-09-29/timeline.log load-test/evidence/2026-09-29/app-box-samples.csv
python3 -m venv /tmp/v && /tmp/v/bin/pip install matplotlib && /tmp/v/bin/python load-test/bench-ec2/charts.py   # → docs/reports/assets/2026-09-29/*.png
```

그래프는 Grafana 캡처가 아니라 증거 파일에서 직접 그린다. Prometheus 는 포화 구간 scrape 가 빠져 대시보드 캡처가 끊기고,
인스턴스를 내리면 데이터도 사라지기 때문이다(retention 6h, 볼륨 없음).
