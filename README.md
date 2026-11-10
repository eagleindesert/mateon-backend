# Mateon Backend

팀 매칭 / 활동 모집 서비스 **Mateon** 의 백엔드 애플리케이션입니다.
사용자 인증, 팀 모집·지원, 활동(이벤트) 매칭, 실시간 알림 등의 REST API 를 제공합니다.

## 기술 스택

- **Java 21** / **Spring Boot 4.0**
- Spring Web MVC, Spring Security (JWT 인증), Spring Data JPA
- **PostgreSQL 16** (로컬은 Docker Compose 로 자동 기동)
- Gmail SMTP (이메일 인증), 별도 AI 서버(의도 추출/임베딩) 연동
- SSE 기반 실시간 알림, springdoc OpenAPI (Swagger UI)

## 주요 도메인

| 도메인 | 설명 | 엔드포인트 |
| --- | --- | --- |
| `auth` | 회원가입·로그인·JWT 재발급·이메일 인증 | `/api/auth` |
| `user` | 사용자 정보 / 마이페이지 | `/api/users` |
| `events` | 활동(이벤트) 조회 및 매칭 | `/api/events` |
| `teams` | 팀 모집·지원 관리 | `/api/teams` |
| `notification` | SSE 실시간 알림 | `/api/notifications` |
| `common` | 헬스체크 | `/health` |

전체 API 명세의 정본은 **Swagger UI** (`/swagger-ui.html`) 입니다 — 스키마를 DTO 에서 직접
생성하므로 코드와 어긋나지 않습니다. 손으로 쓴 개정 이력은 [docs/api-spec/](docs/api-spec/) 에 있습니다.

## 로컬 실행

`bootRun` 실행 시 Spring Boot Docker Compose 지원이 [docker-compose.yml](docker-compose.yml) 의
PostgreSQL 을 자동으로 기동합니다. (Docker 가 실행 중이어야 합니다.)

```bash
./gradlew bootRun        # macOS / Linux
.\gradlew.bat bootRun    # Windows
```

- 애플리케이션: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui.html`

앱도 컨테이너로 실행하려면 `./scripts/build-start.sh`를 사용합니다. Docker 안에서 Gradle로
JAR을 만들고 이미지를 빌드한 뒤, 앱과 PostgreSQL을 시작합니다. `bootRun`과 같은 개발 Compose,
`.env` / `.env.secret`, 개발 DB 볼륨을 사용하며 기본 접속 주소는 `http://localhost:8080`입니다.
`/health`가 성공해야 실행 완료로 처리하므로 Flyway 오류로 앱이 종료되면 스크립트도 실패합니다.

```bash
./scripts/build-start.sh
./scripts/build-start.sh --port 8082 --db-pool-size 20
```

개발 포트는 `DEV_APP_PORT`로 설정합니다. 배포용 `APP_PORT=8081`은 개발 포트에 영향을 주지 않습니다.

DB GUI 는 [docker-compose-pgadmin.yml](docker-compose-pgadmin.yml) 을 앱/DB compose 와 따로 띄운다
(특정 앱에 묶이지 않은 범용 파일). 호스트에서 `http://localhost:5050` 으로 연 뒤, pgAdmin UI 에서
`localhost` 와 그 DB 의 호스트 포트로 서버를 등록한다.

## CI / CD

`main` 으로 열린 PR 과 `main` 머지는 GitHub Actions
([.github/workflows/ci.yml](.github/workflows/ci.yml)) 가 처리합니다.

| 이벤트 | 하는 일 |
| --- | --- |
| `main` 으로 PR | `test` (현재 코드 × 현재 테스트, Testcontainers Postgres, JaCoCo 90%) 와 `ab-regression` (현재 코드 × base `src/test`, JaCoCo 없음) 을 병렬로 돌린 뒤 `pr-gate` 가 둘을 AND 합니다. `*LiveTest` 는 관문에서 빠져 있습니다 |
| `main` 에 머지 | `test` 를 다시 돌린 뒤, DockerHub 최고 태그의 **patch +1** 로 `linux/arm64` 이미지를 `:vX.Y.Z` 와 `:latest` 로 푸시합니다 |
| Actions에서 수동 실행 | `main` 브랜치를 고른 뒤, 올릴 자리(`patch` / `minor` / `major`)를 고르거나 시작 태그(`v1.1.0`, `v2.0.0`)를 직접 적습니다. 테스트 통과 후 그 태그로 푸시하고, 이후 머지는 다시 그 태그에서 patch 를 올립니다 |

브랜치 보호의 required check 는 `pr-gate` 입니다. `test` 와 `ab-regression` 은 로그용입니다.

`ab-regression` 은 PR 이 코드와 테스트를 같이 바꿔 침묵하는 회귀를 찾습니다. base 의 `src/test` 를 현재 코드 위에 덮어 `./gradlew test` 를 돌립니다. 실패는 Step Summary 에 `REGRESSION` (예전 단언이 깨짐) 또는 `INCOMPATIBLE` (예전 테스트가 컴파일되지 않음) 으로 분류됩니다. `src/test` 와 `build.gradle` 이 base 와 같으면 기존 `test` 와 같은 일이라 생략합니다. 라벨 `intentional-contract-break` 가 있으면 overlay 를 생략합니다 (`test` 는 그대로 돕니다). 리뷰할 때 삭제·완화된 테스트가 의도인지, 라벨이 있으면 그 의도가 맞는지 보면 됩니다.

로컬에서 같은 `test` 관문을 보려면 Docker 가 켜져 있어야 합니다. 통합 테스트가 Testcontainers 를 씁니다.

```bash
./gradlew test
```

overlay 를 로컬에서 재현하려면:

```bash
git fetch origin main
git restore --source origin/main --worktree -- src/test
./gradlew test -x jacocoTestReport -x jacocoTestCoverageVerification
```

Windows 는 `.\gradlew.bat` 를 씁니다. 끝나면 `git restore --worktree -- src/test` 로 되돌립니다.

`test` 가 끝나면 커버리지 HTML 이 `build/reports/jacoco/test/html/index.html` 에 생깁니다.
CI 는 같은 파일을 `test-report` 아티팩트에 올립니다. overlay 리포트는 `ab-regression-report` 입니다. `check` 는 instruction/branch/line/method/class
90% 를 요구합니다 (`jacocoTestCoverageVerification`). `fastTest` / `liveTest` 는 측정하지 않습니다.

`fastTest` 는 Docker 없는 단축이고, `liveTest` 는 밖에 떠 있는 AI 스텁을 상대합니다. 둘 다 CI 관문이 아닙니다.

코드만으로 머지가 막히지는 않습니다. GitHub → Settings → Labels 에 `intentional-contract-break` 를 만들고, Settings → Branches 의 `main` protection 에 required status check `pr-gate` 와 required review 를 켜야 관문이 됩니다.

이미지 푸시에는 레포 Secrets 가 필요합니다. PR 테스트는 시크릿 없이 돕니다.

| Secret | 값 |
| --- | --- |
| `DOCKERHUB_USERNAME` | DockerHub 사용자명 (`eagleindesert`) |
| `DOCKERHUB_TOKEN` | DockerHub Access Token (계정 비밀번호 쓰지 않음) |

머지 전에 GitHub → Settings → Secrets and variables → Actions 에 이 두 키를 넣지 않으면
`docker` job 만 실패합니다. 로컬에서 특정 태그를 올릴 때는
[build-push.sh](build-push.sh)의 `--tag`를 씁니다.

## 프로필 (dev / prod)

환경은 프로필 두 개로만 나뉩니다. **디버그 기능은 프로필이 아니라 별도 스위치**라서,
운영에서 잠깐 디버그를 켠다고 CORS·로그·커넥션 풀까지 딸려 오지 않습니다.

| 프로필 | 설정 파일 | 언제 |
| --- | --- | --- |
| `dev` | [application-dev.yml](src/main/resources/application-dev.yml) | 기본값. `bootRun` / IDE 실행은 아무 설정 없이 여기로 오고, **배포 서버도 지금은 이 프로필로 돕니다** |
| `prod` | [application-prod.yml](src/main/resources/application-prod.yml) | **아직 켜는 곳이 없습니다** (아래 참고) |
| `test` | [application-test.yml](src/test/resources/application-test.yml) | `IntegrationTestBase` 의 `@ActiveProfiles("test")` |

> **`prod` 는 미리 만들어만 둔 프로필입니다.** 운영 환경이 아직 없고,
> [docker-compose-deployment.yaml](docker-compose-deployment.yaml) 이 띄우는 서버는 "배포된 개발
> 서버" 라서 `SPRING_PROFILES_ACTIVE: dev` 로 돕니다. 그래서 `dev` 는 "로컬" 이 아니라
> "아직 운영이 아닌 전부" 로 읽어야 합니다.
>
> 운영이 생기면 그 파일의 `SPRING_PROFILES_ACTIVE` 를 `prod` 로 바꾸는 것이 시작점이고,
> 그때 같이 챙길 것은 해당 줄의 주석에 목록으로 적어 뒀습니다.
> 그전까지 `prod` 를 실제로 밟아 보는 유일한 경로는 로컬 스모크입니다:
>
> ```powershell
> $env:SPRING_PROFILES_ACTIVE='prod'; ./gradlew bootRun
> ```

공통 설정은 [application.yml](src/main/resources/application.yml) 에 남아 있고,
프로필 파일은 **환경마다 달라야 하는 것만** 덮어씁니다.
다만 프로필은 *기본값*만 정합니다 — 아래 항목은 전부 `.env` 로 뒤집을 수 있습니다.
배포 서버가 `dev` 로 도는 동안 프로필을 갈아끼우지 않고 로그만 끌 수 있어야 하기 때문입니다.

| 항목 | dev 기본 | prod 기본 | 덮어쓰는 `.env` 키 |
| --- | --- | --- | --- |
| SQL 로그 (`show-sql`) | 끔 | 끔 | `JPA_SHOW_SQL` |
| SQL 로거 (`org.hibernate.SQL`) | `INFO` | `INFO` | `LOG_LEVEL_SQL` |
| 앱 로그 레벨 (`com.example.mateon`) | `DEBUG` | `INFO` | `LOG_LEVEL_APP` |
| CORS 허용 오리진 | `*` (전체 허용) | 기본값 없음 (누락 시 부팅 실패) | `CORS_ALLOWED_ORIGINS` |
| 디버그 컨트롤러 (`/debug/**`) | 등록 | 미등록 | `DEBUG_OAUTH_ENABLED` |
| 커넥션 풀 크기 | 10 | 20 | `DB_POOL_SIZE` |
| Docker Compose 자동 기동 | 켬 | 끔 | — (프로필 전용) |

> **SQL 로그는 두 프로필 모두 기본이 꺼짐입니다.** 배포 서버가 `dev` 로 도는 탓에 예전의
> dev 기본값(켬)이 그대로 서버 컨테이너 로그에 쌓였기 때문입니다. 로컬에서 쿼리를 보려면
> `.env` 로 켜세요 — 같은 쿼리를 두 곳이 찍으므로(`show-sql` 은 `System.out` 으로,
> `org.hibernate.SQL` 은 로거로) **`JPA_SHOW_SQL` 하나로는 반쪽만 열립니다.** 둘을 세트로 줍니다:
>
> ```dotenv
> JPA_SHOW_SQL=true
> LOG_LEVEL_SQL=DEBUG
> ```
>
> 앱 로그(`LOG_LEVEL_APP`)는 dev 기본이 여전히 `DEBUG` 입니다. 서버까지 조용히 시키려면
> 여기에 `LOG_LEVEL_APP=INFO` 를 더합니다.

## 환경 변수

설정은 아래 경로에 있습니다. 어떤 키가 필요한지는 **`.example` 파일이 문서를 겸합니다** —
값 없이 키 목록과 설명만 담고 있고, 이 둘만 커밋됩니다.

| 경로 | 용도 | 커밋 |
| --- | --- | --- |
| [.env.example](.env.example) | 아래 `.env` 의 템플릿 | ✅ |
| [.env.secret.example](.env.secret.example) | 아래 `.env.secret` 의 템플릿 | ✅ |
| `./.env` | 로컬·배포의 일반 실행 설정 | ❌ |
| `./.env.secret` | 로컬·배포의 시크릿 | ❌ |
| `./scripts/docker/.env` | DockerHub 자격증명 ([build-push.sh](build-push.sh) 전용) | ❌ |

처음 받았다면 템플릿을 복사해서 값을 채우세요.

```powershell
Copy-Item .env.example .env
Copy-Item .env.secret.example .env.secret
```

웹 카카오 로그인을 사용하면 `.env`의 `KAKAO_REDIRECT_URIS`를 실제 프론트 콜백 URL로
설정하세요. 템플릿의 `http://localhost:5173/auth/kakao/callback`과 다른 콜백을 사용하면
`dev` 프로필에서도 설정이 필요합니다. 아래는 예시이며, 카카오 콘솔에 등록한 Redirect URI와
프론트에서 보내는 `redirectUri`가 설정값과 정확히 같아야 합니다.

```dotenv
KAKAO_REDIRECT_URIS=https://web.example.com/auth/kakao/callback
```

여러 콜백을 허용하려면 콤마로 구분합니다. 빈 값으로 지정하면 부팅에 실패합니다.
변경 후 로컬 앱은 재시작하고, 배포 Compose의 앱 컨테이너는 환경 변수가 다시 주입되도록
재생성하세요.

```bash
./start.sh --mode server
```

로컬과 배포 서버 모두 `.env` / `.env.secret`을 사용하며, 값은 환경별로 관리합니다.
`start.sh`는 두 파일을 Compose 치환에 사용하고, 일반 앱에는 두 파일의 환경변수를 주입합니다.
테스트 앱에는 Compose에 명시된 테스트용 자격증명만 전달합니다.

## 배포와 컨테이너 재실행

배포 VM 은 ARM CPU, Ubuntu 24.04 LTS, Docker Compose 환경을 기준으로 합니다.
호스트 Nginx 는 앱의 공개 포트(기본 `8081`)로 프록시합니다.
일반·로드테스트 모두 배포 디렉터리에는 다음 네 파일만 필요합니다.

```text
docker-compose-deployment.yaml
.env
.env.secret
start.sh
```

이미지는 소스가 있는 머신에서 빌드하고 DockerHub에 게시합니다. 일반 모드는 백엔드를,
테스트 모드는 같은 백엔드와 AI 스텁을 같은 태그·플랫폼으로 게시합니다.
Nginx는 VM 호스트에서 관리하며, 프로젝트가 프록시 이미지나 설정 파일을 배포하지 않습니다.

```bash
./build-push.sh --mode server --tag v1.2.3
./build-push.sh --mode loadtest --tag v1.2.3
```

```bash
./start.sh          # 설정된 이미지를 모두 pull한 뒤 빌드 없이 실행
./start.sh v1.2.3   # 태그를 v1.2.3으로 지정하고 pull 성공 후 실행
./start.sh --tag v1.2.3 --db-pool-size 20 --port 8081
./start.sh --help
```

`start.sh`는 모든 모드에서 DockerHub에 게시된 이미지만 pull해 실행합니다. AI 스텁을 포함해
로컬 빌드는 하지 않으며, pull에 실패하면 실행을 중단합니다. 버전 인자는 이번 실행에만
적용됩니다. 이후에도 같은 버전을 기본으로 쓰려면 `.env`의 `TAG`를 지정하세요.
모든 모드에서 앱의 `/health`가 성공할 때까지 최대 180초 기다립니다.
CLI로 일반 모드를 실행하려면 같은 프로필과 두 환경 파일을 지정합니다.

```bash
TAG=v1.2.3 COMPOSE_PROFILES=server docker compose --env-file .env --env-file .env.secret \
  -f docker-compose-deployment.yaml --profile server pull --policy always
TAG=v1.2.3 COMPOSE_PROFILES=server docker compose --env-file .env --env-file .env.secret \
  -f docker-compose-deployment.yaml --profile server up -d --no-build --pull never --wait
```

부하테스트는 같은 배포 Compose의 `loadtest` 프로필과 별도 프로젝트 `mateon-loadtest`를
사용합니다. 테스트 DB·네트워크·자격증명은 일반 환경과 분리됩니다. `loadtest` 모드는 AI 업무
호출을 거절하고, `ai-stub` 모드는 실제 AI 대신 스텁 응답을 사용합니다.
앱·스텁은 모두 `TAG`를 사용하므로 같은 버전을 게시해야 하며, 별도 `AI_STUB_TAG`는 사용하지 않습니다.

이전 구성으로 `mateon-loadtest`를 실행 중이었다면 최초 전환 전에 아래 명령으로 기존 테스트
컨테이너를 종료합니다. 테스트 DB 볼륨은 유지하며 일반 배포 프로젝트에는 영향을 주지 않습니다.
서비스명이 변경되었으므로 이 절차 없이 실행하면 기존 테스트 DB·포트와 충돌할 수 있습니다.

```bash
docker compose --env-file .env --env-file .env.secret -p mateon-loadtest \
  -f docker-compose-deployment.yaml --profile '*' down --remove-orphans
```

```bash
./start.sh --mode loadtest --tag v1.2.3 --db-pool-size 20 --port 18081
./scripts/test/jmeter/run.sh --suite core-rest --users 30 --ramp-up 60 --duration 600

./start.sh --mode ai-stub
./scripts/test/jmeter/run.sh --suite ai-stub --users 10 --ramp-up 60 --duration 300
```

서버 환경 배포는 네 파일로 완료됩니다. 실제 부하 발생에는 소스 체크아웃과 JMeter·uv가 필요합니다.
기본 ARM 이미지, 테스트 데이터 준비, 격리 방식, 결과와 종료 방법은
[JMeter 실행 안내](scripts/test/jmeter/README.md)를 참고하세요. 환경변수를 바꾸면 `start.sh`로
컨테이너를 재생성해야 하며, 단순 `docker compose restart`에는 새 설정이 반영되지 않습니다.

소스가 있는 머신에서 빌드 후 실행하려면 `build-start.sh`를 사용합니다. `start.sh`와 동일한
`--mode`, `--tag`, `--db-pool-size`, `--port` 옵션을 지원합니다. 기본 모드인 `server`의 환경은
스크립트에 따라 다릅니다.

| 실행 명령 | Compose | 설정 파일 | 기본 HTTP 포트 | DB 볼륨 키 |
| --- | --- | --- | --- | --- |
| `./scripts/build-start.sh` | `docker-compose.yml` (`app` 프로파일) | `.env` + `.env.secret` | 8080 (`DEV_APP_PORT`) | `mateon-postgres-dev-data` |
| `./start.sh` | `docker-compose-deployment.yaml` (`server`) | `.env` + `.env.secret` | 8081 (`APP_PORT`) | `mateon-postgres-data` |
| 두 스크립트의 `--mode loadtest` / `ai-stub` | `docker-compose-deployment.yaml` (`loadtest`) | `.env` + `.env.secret` (앱에는 테스트값만 주입) | 18081 (`LOADTEST_PORT`) | `loadtest-data` |

테스트 앱은 기본 `127.0.0.1:18081`에 직접 포트를 엽니다. `--port`로 호스트 Nginx가 사용하는
포트에 맞출 수 있으며, 그 포트를 기존 컨테이너가 사용하고 있다면 먼저 해당 컨테이너를 중지해야 합니다.
모드 선택은 다른 모드의 컨테이너를 자동으로 종료하지 않습니다. 기존 배포 DB의 볼륨 이름을
유지하려면 일반 배포 디렉터리도 그대로 사용하세요.

`build-start.sh`는 `bootRun`과 같은 개발 DB를 사용합니다. 배포 DB 볼륨은 별도로 보존합니다.
기존 배포 볼륨에서 `V2 ... provider_id ... does not exist`가 발생했다면, 현재 V1에 정의된 컬럼과
기존 DB의 스키마가 일치하지 않는 상태입니다. 개발 실행은 개발 DB로 연결하고, 해당 배포 DB의
스키마 이관은 별도로 확인해야 합니다.

```bash
./scripts/build-start.sh --tag local
./scripts/build-start.sh --tag local --platform linux/amd64
./scripts/build-start.sh --mode loadtest --db-pool-size 20 --port 18081
./scripts/build-start.sh --mode ai-stub --tag local
```

플랫폼 기본값은 Docker 데몬의 아키텍처이며, 태그 기본값은 `local`입니다. 테스트 모드는
앱과 AI 스텁을 모두 같은 태그로 빌드합니다. 빌드 결과를 같은 Docker 데몬에 로드하고
그 이미지로 컨테이너를 재생성하며, DockerHub에 push하지 않습니다.
DB 이미지는 로컬에 없으면 pull합니다. 배포 VM용 ARM 이미지 빌드·푸시는 아래 명령으로 수행합니다.
Gradle 컴파일은 빌드 머신의 네이티브 아키텍처에서 실행됩니다.

```bash
./build-push.sh --username eagleindesert --tag v1.2.3 --platform linux/arm64
./build-push.sh --mode loadtest --username eagleindesert --tag v1.2.3 --platform linux/arm64
```

buildx 빌더는 `restart-policy=no` 로 생성하며, 기존 빌더 컨테이너에도 같은 정책을 적용합니다.
`--no-push`는 게시 대신 로컬 Docker 데몬에 이미지를 로드합니다. 기존
`scripts/docker/deploy-dockerhub.sh`는 `build-push.sh`로 전달하는 호환 진입점입니다.

`bootRun`과 Docker 빌드는 작업량과 캐시 범위가 다릅니다. `bootRun`은 로컬 컴파일 결과를 활용해
클래스패스로 실행하고, Docker 빌드는 JAR 생성·이미지 생성·Docker 데몬으로 로드까지 수행합니다.
빌드는 Docker 내부의 JDK 21과 Gradle wrapper로 수행하므로 호스트에 Java·Gradle 설치가 필요 없습니다.

Dockerfile은 BuildKit 캐시 마운트로 Gradle 의존성(`/root/.gradle`), 작업 이력(`/app/.gradle`),
컴파일 결과(`/app/build`)를 보존합니다. Java 소스가 바뀌면 Gradle을 다시 실행하되,
이전 결과를 바탕으로 증분 컴파일이 가능한 범위만 다시 처리하고 `--build-cache`도 사용합니다.
`clean`은 실행하지 않으며, 이전 버전 JAR이 섞이지 않도록 `build/libs`의 JAR만 제거합니다.
완성된 JAR은 캐시 밖으로 복사해 런타임 이미지에 넣습니다.

앱 이미지는 `src/main`만 복사하므로 테스트 소스 변경은 앱 빌드 레이어를 무효화하지 않습니다.
입력이 같으면 Docker 레이어 캐시를 재사용합니다. 캐시는 빌드 머신의 아키텍처별로 분리하고,
같은 캐시를 동시에 수정하지 않도록 잠급니다. 기본 `mateon-builder`를 중지해도 캐시는 남지만,
빌더나 캐시를 삭제하면 다시 준비해야 합니다. 첫 빌드·큰 변경·전체 재컴파일의 비용은 발생합니다.

GitHub Actions의 `type=gha`는 캐시 마운트 내용을 자동 보존하지 않습니다. 새 러너에서도 wrapper·
플러그인·의존성 메타데이터를 재사용할 수 있도록 별도의 준비 레이어를 유지합니다.
컴파일 결과의 증분 재사용 효과는 같은 빌더를 계속 사용하는 로컬 반복 빌드에서 확인할 수 있습니다.

빌드 로그의 단계별 소요 시간을 확인하려면 아래 명령을 사용합니다. 빌드 후 개발 컨테이너도 실행됩니다.

```bash
BUILDKIT_PROGRESS=plain ./scripts/build-start.sh
```

모든 HTTP 요청은 `method`, `uri`, `status`, `ip`, `userId`, `elapsedMs` 를 한 줄로 기록합니다.
미인증 요청의 `userId` 는 `-` 이고, SSE 등 비동기 요청은 응답 완료 시 기록합니다.
IP 는 `X-Forwarded-For` 의 첫 주소, `X-Real-IP`, 직접 연결 주소 순으로 선택합니다.
호스트 Nginx 는 외부에서 들어온 전달 헤더를 신뢰하지 않도록 클라이언트 주소로 덮어써야 합니다.

```nginx
proxy_set_header X-Forwarded-For $remote_addr;
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-Proto $scheme;
```
