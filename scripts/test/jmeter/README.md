# JMeter 테스트 환경

일반 REST와 AI 스텁 테스트를 별도 모드로 실행한다. 두 모드 모두 실제 AI 대신 스텁을 사용하며,
백엔드를 전용 PostgreSQL·스텁과 연결한다. 게시된 이미지는 `start.sh`,
현재 소스를 빌드해서 실행하려면 `scripts/build-start.sh`를 사용한다.

## 준비

- Bash 4 이상, Docker Engine, Docker Compose v2 (`up --wait` 지원).
- JMeter 5.6.3과 Java 21. JMeter의 `bin`을 PATH에 추가하거나 실행할 때 `--jmeter`로 경로를 지정한다.
- Python 3.10 이상과 `uv`. 실행기는 표준 라이브러리만 사용하며 패키지 설치가 필요 없다.
- 서버 환경 배포에는 `.env`, `.env.secret`, `docker-compose-deployment.yaml`, `start.sh` 네 파일만 필요하다.
- JMeter 실행기와 테스트 계획은 저장소에서 실행한다. 네 파일 배포에 부하 발생기는 포함되지 않는다.
- `start.sh`는 DockerHub에 게시된 앱·AI 스텁 이미지가 필요하다. `build-start.sh`는 소스와 Docker buildx가 필요하다.

일반·테스트 실행 구성을 [docker-compose-deployment.yaml](../../../docker-compose-deployment.yaml)에서
함께 관리한다. `.env`와 `.env.secret`은 Compose 변수 치환에 사용하지만, 테스트 컨테이너에는
명시한 테스트 설정만 전달한다. 앱의 JWT·AI·메일·OCI 값은 고정된 더미 값이다.
실제 AI를 사용하는 테스트 모드는 제공하지 않는다.

## 환경 시작

저장소 루트에서 실행한다.

이전 테스트 구성을 사용했다면 최초 전환 전에 기존 컨테이너를 종료한다. 서비스명 변경으로
기존 DB와 앱·프록시가 남을 수 있다. 다음 명령은 `mateon-loadtest` 프로젝트의 컨테이너만
정리하고 테스트 DB 볼륨은 보존한다.

```bash
docker compose --env-file .env --env-file .env.secret -p mateon-loadtest \
  -f docker-compose-deployment.yaml --profile '*' down --remove-orphans
```

```bash
./start.sh --mode loadtest
./start.sh --mode loadtest --db-pool-size 20 --port 18081
./start.sh --mode loadtest --tag v1.2.3
./start.sh --mode ai-stub
./start.sh --help
```

`--tag`는 예시이며 실제로 게시된 버전을 지정한다. 앱과 스텁은 같은 태그를 사용한다.
태그를 생략하면 `.env`의 `TAG`를 사용하며 기본값은 `latest`다.
`start.sh`는 앱·AI 스텁·DB 이미지를 pull한 뒤 `--no-build`로 실행한다.
pull에 실패하면 기존 이미지로 실행하지 않는다. 스텁 이미지 이름은 `AI_STUB_DOCKERHUB_IMAGE`로 지정한다.
기동 시 공개 설정만 출력하고, 앱의 `/health`가 성공할 때까지 최대 180초 기다린다.

로컬 소스를 빌드해 실행하려면 같은 옵션을 `build-start.sh`에 전달한다.

```bash
./scripts/build-start.sh --mode loadtest --db-pool-size 20 --port 18081
./scripts/build-start.sh --mode ai-stub --tag local --platform linux/amd64
./scripts/build-start.sh --help
```

테스트 모드에서는 앱과 AI 스텁을 모두 빌드하고 같은 Docker 데몬에 로드한 뒤 실행한다.
태그 기본값은 `local`이며, `--tag`를 지정하면 두 이미지에 같은 태그를 붙인다.
이미지 이름은 `--username`·`--image-name` 또는 `DOCKERHUB_USERNAME`·`DOCKERHUB_IMAGE`로 지정한다.
스텁 이름은 셸의 `AI_STUB_DOCKERHUB_IMAGE`를 사용하며 기본값은 `mateon-ai-stub`이다.
앱·스텁 이미지는 pull하지 않고, DB는 로컬에 없으면 pull한다. DockerHub에 push하지 않는다.
어느 빌드든 실패하면 컨테이너를 재생성하지 않는다.

두 스크립트의 공통 실행 옵션:

| 옵션 | 동작 |
| --- | --- |
| `--mode server` | 기본 모드. `start.sh`: 배포 Compose·8081. `build-start.sh`: 개발 Compose·8080. 둘 다 `.env` / `.env.secret` 사용 |
| `--mode loadtest` | 일반 REST 환경. 스텁은 업무 호출을 기록하고 503으로 거절 |
| `--mode ai-stub` | 가짜 AI 응답 제공. 요청 지연을 설정해 대기를 재현 가능 |
| `--tag VERSION` | 앱·스텁의 동일한 태그. `start.sh`: 게시된 버전. `build-start.sh`: 빌드할 버전(기본 `local`). 위치 인자 `VERSION`도 동일 |
| `--db-pool-size NUMBER` | 앱의 DB 풀 크기. 1~10000 |
| `--port NUMBER` | 선택한 모드의 앱 공개 포트. 1~65535 |

DB 풀·포트의 우선순위는 명시한 옵션, 셸 환경변수, `.env` / `.env.secret`, Compose 기본값 순서다.
일반 개발 포트는 `DEV_APP_PORT`, 일반 배포 포트는 `APP_PORT`, 테스트 포트는 `LOADTEST_PORT`다.
`start.sh`의 이미지 설정도 같은 순서다. `build-start.sh`는 빌드 대상 이미지·태그·플랫폼을
Compose에 전달하므로, 설정 파일의 원격 이미지 설정으로 빌드 결과를 덮어쓰지 않는다.
AI 주소·라우터·백필·자격증명은 Compose의 테스트 서비스에 고정되어 이 우선순위로 변경되지 않는다.
옵션은 해당 실행에만 적용된다. 다음 실행에도 유지하려면 `.env`의 설정을 수정한다.
변경 후 선택한 시작 스크립트를 다시 실행하면 Compose가 컨테이너를 재생성한다. `restart`만으로는
환경변수가 바뀌지 않는다.

게시된 이미지로 테스트 프로필을 직접 실행하는 동등한 명령:

```bash
COMPOSE_PROFILES=loadtest docker compose --env-file .env --env-file .env.secret \
  -p mateon-loadtest -f docker-compose-deployment.yaml --profile loadtest \
  pull --policy always
COMPOSE_PROFILES=loadtest docker compose --env-file .env --env-file .env.secret \
  -p mateon-loadtest -f docker-compose-deployment.yaml --profile loadtest \
  up -d --no-build --pull never --wait --wait-timeout 180
```

첫 명령이 성공했을 때만 두 번째 명령을 실행한다.
`ai-stub` 모드를 직접 실행할 때는 각 명령 앞에 `MATEON_TEST_MODE=ai-stub`을 지정한다.
CLI의 `--env-file`은 Compose 치환용이다. 테스트 컨테이너에는 `environment`에 명시한 값만 전달한다.

### CPU 아키텍처

`start.sh`의 기본 `APP_PLATFORM=linux/arm64`는 현재 배포 서버와 같다. AMD64에서 측정하려면
앱과 스텁의 AMD64 이미지를 게시하고, `.env`의 `APP_PLATFORM`과 `TAG`를 맞춘다.
로컬 소스는 `build-start.sh --mode loadtest`로 실행하면 Docker 데몬의 아키텍처로 빌드한다.
`--platform linux/amd64`처럼 직접 지정할 수도 있다.
에뮬레이션 성능을 배포 서버의 성능으로 해석하지 않는다.

### 테스트 이미지 게시

`build-push.sh --mode loadtest`는 앱과 AI 스텁을 같은 태그로 게시한다. 아래 사용자명과 태그는
게시할 DockerHub 저장소에 맞게 바꾸고, 해당 계정으로 `docker login`한 뒤 실행한다.

```bash
./build-push.sh --mode loadtest --username eagleindesert \
  --tag v1.2.3 --platform linux/arm64
```

게시 후 `.env`의 `DOCKERHUB_USERNAME`, `AI_STUB_DOCKERHUB_IMAGE`, `TAG`를 맞추거나
`start.sh --tag v1.2.3`으로 실행한다. 앱과 스텁은 같은 DockerHub 사용자명과 태그를 사용한다.
스텁 Dockerfile은 빌드 머신에서만 사용한다. 통합 배포 Compose에는 `build`가 없으며,
일반 개발용 `docker-compose.yml`은 직접 빌드도 지원한다.

## 일반 REST 실행

```bash
./scripts/test/jmeter/run.sh \
  --suite core-rest --users 30 --ramp-up 60 --duration 600
```

실행기는 다음 순서로 동작한다.

1. 로컬 Docker 연결, 테스트 서비스의 프로젝트·AI 설정·허용된 네트워크·스텁 모드를 확인하고,
   테스트 앱에 실제 공개된 포트에서 `/health`를 확인한다.
2. Flyway가 준비한 테스트 DB에 사용자, 활동, 팀, 채팅 데이터를 직접 적재한다. 준비 과정에 AI·메일은 호출하지 않는다.
3. 공개 테스트 키로 가상 사용자별 JWT와 CSV를 생성한다. 로그인 요청 제한과 SMTP를 거치지 않는다.
4. 진행 중 스텁 요청이 없고 카운터가 5초간 안정된 것을 확인한 뒤 초기화한다.
5. JMeter CLI를 실행하고, 종료 후 같은 안정화 확인을 거쳐 호출 수와 JTL을 검사한다.
6. 결과를 저장하고 CSV의 테스트 토큰은 삭제한다.

각 가상 사용자는 실행 내내 같은 계정을 사용한다. 기본 계획은 활동 목록·검색, 팀 목록·상세,
내 프로필·마이페이지, 채팅방·메시지, 알림, 북마크 추가·목록·삭제를 반복한다.
요청마다 1~3초 대기가 있다. 이는 예제 사용자 흐름이며 실제 서비스 트래픽 비율을 뜻하지 않는다.

기본 데이터는 사용자 수만큼의 계정·채팅방, 활동 1000건, 팀 100건, 채팅방당 메시지 20건이다.
`--events 10000 --teams 1000`으로 늘릴 수 있다. 준비 SQL은 기존 데이터를 지우지 않으므로
설정 건수를 낮춰도 이전 행은 남는다. 실제 테이블 건수는 결과에 기록한다.

## AI 스텁 실행

```bash
./start.sh --mode ai-stub
./scripts/test/jmeter/run.sh \
  --suite ai-stub --users 10 --ramp-up 60 --duration 300
```

매칭 세션 초기화 → 첫 발화 → 추가 답변 → 세션 조회를 반복한다. 완료 시 1536차원 가짜 벡터가
DB에 저장되는 경로까지 통과한다. LLM 분류·실제 모델 품질·메일·파일 업로드는 측정하지 않는다.
`AI_STUB_DELAY_MS`를 `.env`에서 변경하고 선택한 시작 스크립트를 `--mode ai-stub`로 다시 실행하면
요청별 지연을 재현할 수 있다. 지연의 허용 범위는 0~120000ms다.

## 결과와 실패 판정

```text
build/reports/jmeter/<UTC 시각>-<시나리오>-<실행 ID>/
├── dashboard/index.html
├── results.jtl
├── jmeter.log
└── verification.json
```

HTTP 상태와 `success=true`를 검증한다. HTTP/Assertion 오류, 표본 0건, JMeter 실행 오류,
스텁 재시작·카운터 초기화가 감지되면 종료 코드 1이다. 일반 REST에서는 AI 업무 호출 1건도 실패이고,
AI 스텁 계획에서는 실제 스텁 호출이 없거나 거절되면 실패다. 환경·인자 준비 오류도 성공으로 보고하지 않는다.

`verification.json`에는 API별 표본·오류 수·성공 요청 p50/p95/p99, 이미지, DB 풀, 실제 데이터 양,
스텁 호출 수와 경로가 남는다. 지연 임계값에 의한 합격 판정은 아직 없으며, HTML에서 처리량과 함께 비교한다.
기간은 Ramp-up을 포함한다. 워밍업과 안정 구간을 분리해 평가하려면 JTL의 시각으로 구간을 나누거나
별도 실행 결과를 사용한다. 작은 표본의 p99만으로 성능을 판단하지 않는다.

스텁 안정화 검사는 스텁에 도착한 요청을 본다. 앱 내부에 아직 제출되지 않은 비동기 작업의 완료까지
증명하지는 않는다. 제공한 계획은 동기 AI 요청과 AI를 호출하지 않는 REST에 한정한다.
스텁 신원 조회와 카운터 관리는 컨테이너 내부에서만 수행하며 외부 포트로 공개하지 않는다.
같은 스택을 상대로 테스트를 동시에 실행하지 않는다. 같은 체크아웃의 실행기는 파일 잠금으로 중복 실행을 막는다.

## 네트워크와 접속

PostgreSQL·스텁은 `internal: true`인 `isolated` 네트워크에만 연결된다. 앱은 호스트에 HTTP 포트를
게시할 수 있도록 같은 프로젝트의 일반 bridge 네트워크 `entrance`에도 연결한다.
앱에는 외부 통신 경로가 있지만, AI 주소는 스텁으로 고정하고 실제 자격증명은 전달하지 않는다.
실행기는 이 두 네트워크의 프로젝트 라벨·드라이버·내부망 설정을 검사하며 임의의 추가 연결은 거절한다.
DB와 스텁 관리 포트는 공개하지 않는다. 기본 HTTP 주소는 `http://127.0.0.1:18081`이다.
VM 호스트에 설치된 Nginx는 이 프로젝트에서 설치하거나 설정하지 않는다.

현재 실행기는 Docker 데몬과 JMeter가 같은 머신에 있는 구성을 지원하며 원격 Docker 연결은 거절한다.
별도 부하 발생기를 사용할 때는 네트워크 경로와 계정 데이터 전달을 별도로 구성해야 한다.
`LOADTEST_BIND_ADDRESS`로 앱의 바인딩
주소를 정할 수 있지만, 공개 테스트 키와 데이터를 사용하는 환경이므로 접근 가능한 테스트망 주소를 사용한다.

## 종료

```bash
docker compose --env-file .env --env-file .env.secret \
  -p mateon-loadtest -f docker-compose-deployment.yaml --profile loadtest down
```

DB 볼륨은 남는다. 완전 초기화가 필요할 때만 위 명령에 `--volumes`를 붙인다.
서버 모드와 테스트 모드는 별도 스택이므로 서버를 시작해도 테스트 스택이 자동 종료되지는 않는다.

## 구현 검증

```bash
uv run --offline --no-project scripts/test/test_deployment.py
uv run --offline --no-project scripts/test/jmeter/test_loadtest.py
uv run --offline --no-project -m unittest discover -s scripts/debug/ai-stub/tests -v
```

위 검증은 임시 설정과 가짜 HTTP 소켓을 사용한다. 실제 Docker 컨테이너 기동이나 JMeter 성능 측정을
대체하지 않는다. Compose 설정만 확인하려면 시작 명령의 `up ...` 대신 `config --quiet`를 사용한다.
