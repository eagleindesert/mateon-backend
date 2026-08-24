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
PostgreSQL / pgAdmin 을 자동으로 기동합니다. (Docker 가 실행 중이어야 합니다.)

```bash
./gradlew bootRun        # macOS / Linux
.\gradlew.bat bootRun    # Windows
```

- 애플리케이션: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- pgAdmin: `http://localhost:5050` (admin@admin.com / admin)

## 프로필 (dev / prod)

환경은 프로필 두 개로만 나뉩니다. **디버그 기능은 프로필이 아니라 별도 스위치**라서,
운영에서 잠깐 디버그를 켠다고 CORS·로그·커넥션 풀까지 딸려 오지 않습니다.

| 프로필 | 설정 파일 | 언제 |
| --- | --- | --- |
| `dev` | [application-dev.yml](src/main/resources/application-dev.yml) | 기본값. `bootRun` / IDE 실행은 아무 설정 없이 여기로 옵니다 |
| `prod` | [application-prod.yml](src/main/resources/application-prod.yml) | [docker-compose-deployment.yml](docker-compose-deployment.yml) 이 `SPRING_PROFILES_ACTIVE=prod` 로 켭니다 |
| `test` | [application-test.yml](src/test/resources/application-test.yml) | `IntegrationTestBase` 의 `@ActiveProfiles("test")` |

공통 설정은 [application.yml](src/main/resources/application.yml) 에 남아 있고,
프로필 파일은 **환경마다 달라야 하는 것만** 덮어씁니다.

| 항목 | dev | prod |
| --- | --- | --- |
| SQL 로그 (`show-sql`) | 켬 | 끔 |
| 로그 레벨 (`com.example.mateon`) | `DEBUG` | `INFO` |
| CORS 허용 오리진 | `*` (전체 허용) | `CORS_ALLOWED_ORIGINS` **필수** |
| 디버그 컨트롤러 (`/debug/**`) | 등록 | 미등록 |
| Docker Compose 자동 기동 | 켬 | 끔 |
| 커넥션 풀 크기 | 10 | 20 |

## 환경 변수 (.env)

`.env` 는 **두 경로**에 있고 용도가 다릅니다. 둘 다 `.gitignore`·`.dockerignore` 로 제외되어
커밋되지도, 이미지에 들어가지도 않습니다.

| 경로 | 용도 | 읽는 주체 |
| --- | --- | --- |
| `./.env` | 애플리케이션 시크릿·설정 | 로컬은 Spring, 배포는 compose `env_file` |
| `./scripts/docker/.env` | DockerHub 자격증명 | [deploy-dockerhub.ps1](scripts/docker/deploy-dockerhub.ps1) |

루트 `./.env` 는 **내 PC 와 배포 서버가 각자 다른 내용**을 갖습니다(같은 이름의 다른 파일입니다).
차이는 아래 [배포 서버의 `./.env`](#배포-서버의-env) 에 정리했습니다.

### 형식 규칙

- `KEY=VALUE` 한 줄에 하나. 따옴표를 붙이지 않습니다(값에 그대로 포함됩니다).
- `#` 로 시작하는 줄은 주석입니다.
- **키 이름에 점(`.`)을 쓰지 않습니다.** 대문자 + 언더스코어만 씁니다.
  배포 compose 가 `env_file` 로 이 파일을 통째로 환경변수화하는데, `debug.oauth.enabled` 같은
  점 표기는 그대로 환경변수 이름이 되어 **프로필 yml 을 조용히 이겨 버립니다**
  (Spring 우선순위에서 OS 환경변수 > 프로필 yml). 대문자 이름은 프로필이 `${...}` 로
  명시적으로 받는 자리라 그런 사고가 없습니다.
- 로컬에서는 [application.yml](src/main/resources/application.yml) 의
  `spring.config.import: "optional:file:.env[.properties]"` 로 로드됩니다. 파일이 없으면 무시됩니다.

### 루트 `./.env` — 전체 키

파일은 아래 네 구역으로 나뉘어 있습니다. **필수** 표시된 값이 없으면 부팅이 실패합니다.

#### 1. Key / Account Config

| 키 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- |
| `MAIL_USERNAME` | ✅ | — | 이메일 인증코드 발송용 Gmail 주소 |
| `MAIL_PASSWORD` | ✅ | — | Gmail **앱 비밀번호** (계정 비밀번호가 아닙니다) |
| `JWT_SECRET` | ✅ | — | 256bit 이상. 바뀌면 발급된 토큰이 전부 무효가 됩니다 |
| `JWT_EXPIRATION` | | `86400000` | access token 수명 (ms, 기본 1일) |
| `JWT_REFRESH_EXPIRATION` | | `604800000` | refresh token 수명 (ms, 기본 7일) |

#### 2. AI Config

| 키 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- |
| `AI_BASE_URL` | | `http://localhost:8000` | 의도 추출·임베딩을 담당하는 외부 FastAPI 서버 |
| `AI_INTERNAL_SECRET` | ✅ | — | 위 서버가 `X-Internal-Secret` 헤더로 요구합니다. 누락 시 `AiServerProperties` 가 부팅을 막습니다 |
| `OPENAI_API_KEY` | | (빈 값) | 도메인 분류용. **비워도 앱은 뜹니다** — 라우터가 실패로 보고 모든 발화를 매칭 의도 추출로 흘려보냅니다(게이트웨이 도입 전 동작) |
| `AI_ROUTER_MODEL` | | `gpt-5-mini` | 분류 모델. `temperature` 는 보내지 않습니다([application.yml](src/main/resources/application.yml) 주석 참고) |
| `AI_ROUTER_ENABLED` | | `true` | `false` 면 분류를 건너뛰고 곧장 매칭으로 통과 |

#### 3. Prod / Debug Config

프로필 기본값을 덮어쓰는 자리입니다. 기본값 그대로 쓸 거면 줄을 지워도 됩니다.

| 키 | 필수 | dev 기본 | prod 기본 | 설명 |
| --- | --- | --- | --- | --- |
| `CORS_ALLOWED_ORIGINS` | **prod 만** ✅ | `*` | — | 허용 오리진. 콤마 구분, 스킴·포트까지 정확히. REST 와 STOMP 핸드셰이크가 같은 값을 씁니다 |
| `DEBUG_OAUTH_ENABLED` | | `true` | `false` | 카카오 인가코드 수신 컨트롤러(`/debug/**`) 등록 여부. `false` 면 빈 미등록 → 404 |
| `SWAGGER_UI_ENABLED` | | `true` | `true` | Swagger UI 노출. `false` 로 닫아도 `/v3/api-docs` 는 계속 열려 있습니다 |

> `CORS_ALLOWED_ORIGINS` 는 prod 에서 기본값이 없습니다. 값이 없으면
> `CorsProperties.validateAllowedOrigins()` 가 부팅을 막습니다 — 전체 허용으로 조용히 뜨는 것보다
> 낫기 때문입니다(`allowCredentials(true)` + `*` 는 아무 사이트나 로그인된 사용자 자격으로
> 이 API 를 호출할 수 있다는 뜻입니다).
>
> ```dotenv
> CORS_ALLOWED_ORIGINS=https://mateon.example.com,https://www.mateon.example.com
> ```

#### 4. OCI Object Storage (S3 호환 API)

포스터 이미지·프로필 이미지 업로드에 사용합니다. OCI 콘솔 > 사용자 > Customer Secret Keys 에서 발급합니다.

| 키 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- |
| `OCI_NAMESPACE` | ✅ | — | 오브젝트 스토리지 네임스페이스 |
| `OCI_REGION` | ✅ | — | 예: `ap-chuncheon-1` |
| `OCI_BUCKET` | ✅ | — | 버킷 이름 |
| `OCI_S3_ACCESS_KEY` | ✅ | — | Customer Secret Key 의 Access Key |
| `OCI_S3_SECRET_KEY` | ✅ | — | 같은 키의 Secret |
| `OCI_BUCKET_MAX_BYTES` | | `2GB` | 버킷 총량 상한(OCI 는 버킷 단위 쿼터가 없어 앱이 직접 셉니다). `0B` 면 무제한. 실제 여유보다 낮게 잡습니다 |

누락 시 `ObjectStorageProperties.validate()` 가 부팅을 막습니다.

### 선택 — 필요할 때만 추가하는 키

파일에는 없지만 `application.yml` 이 읽는 값들입니다. 기본값으로 충분하면 넣지 않습니다.

| 키 | 기본값 | 설명 |
| --- | --- | --- |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/mateon_db` | 배포에서는 compose 가 직접 주입합니다 |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | `admin` / `admin` | |
| `DB_POOL_SIZE` | dev 10 / prod 20 | HikariCP 최대 커넥션 |
| `DB_LEAK_DETECTION_MS` | `20000` | 이 시간 넘게 반납 안 된 커넥션의 스택트레이스를 경고로 남깁니다 |
| `MULTIPART_MAX_FILE_SIZE` / `_REQUEST_SIZE` | `20MB` / `24MB` | 컨테이너 레벨 한도. 도메인별 한도는 각 서비스 상수가 따로 지킵니다 |
| `AI_CONNECT_TIMEOUT` / `AI_READ_TIMEOUT` | `5s` / `120s` | 포트폴리오 PDF 요약이 가장 오래 걸립니다 |
| `AI_SESSION_TTL` | `24h` | 방치된 의도 추출 대화의 만료 시간 |
| `AI_EMBEDDING_DIMENSION` | `1536` | `user_embeddings.embedding` 의 `vector(1536)` 와 반드시 일치해야 합니다 |
| `NOTIFICATION_SSE_TIMEOUT` | `1h` | 앞단 nginx `proxy_read_timeout` 보다 짧게 두는 편이 좋습니다 |
| `OCI_BUCKET_USAGE_SYNC_CRON` | `0 30 4 * * *` | 버킷 사용량 재실측 주기 |
| `COLLABORATION_REVIEW_WINDOW_DAYS` | `14` | 활동 종료 후 팀원 평가 가능 기간 |
| `COLLABORATION_AUTO_COMPLETE_CRON` | `0 0 3 * * *` | 마감일 지난 팀 자동 종료 배치 |

### 배포 서버의 `./.env`

같은 파일 이름이지만 내용이 다릅니다. [docker-compose-deployment.yml](docker-compose-deployment.yml) 이
`env_file` 로 읽어 앱 컨테이너에 주입하고, 여기에만 있는 키가 몇 개 더 있습니다.

| 키 | 기본값 | 설명 |
| --- | --- | --- |
| `DOCKERHUB_USERNAME` | `eagleindesert` | pull 할 이미지의 소유자 |
| `TAG` | `latest` | 이미지 태그 |
| `APP_PORT` | `8081` | 호스트에 노출할 포트 (컨테이너 내부는 8080 고정) |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | `admin` / `admin` | DB 계정. 운영에서는 반드시 바꿉니다 |
| `PGADMIN_DEFAULT_EMAIL` / `_PASSWORD` | `admin@admin.com` / `admin` | pgAdmin 로그인 |
| `PGADMIN_PORT` | `5050` | |

`SPRING_PROFILES_ACTIVE` 는 `.env` 가 아니라 compose 의 `environment:` 에 `prod` 로 박혀 있습니다.

> ⚠️ 로컬 `.env` 를 배포 서버로 그대로 복사하지 마세요. `CORS_ALLOWED_ORIGINS` 가 없어 부팅이
> 막히고, `DEBUG_OAUTH_ENABLED=true` 가 딸려 가면 디버그 컨트롤러가 운영에 열립니다.

### `./scripts/docker/.env` — 배포 스크립트용

DockerHub 이미지 빌드·푸시 스크립트가 자격증명을 읽는 파일입니다.
(매개변수 `-Username` 이나 환경변수 `DOCKERHUB_USERNAME` / `DOCKERHUB_TOKEN` 이 우선하고,
셋 다 없으면 기존 `docker login` 세션을 씁니다.)

```dotenv
DOCKERHUB_USERNAME=your-dockerhub-username
DOCKERHUB_TOKEN=dckr_pat_xxxxx   # Access Token 권장 (비밀번호 대신)
```

> ⚠️ 모든 `.env` 가 시크릿을 담습니다. 절대 커밋하지 말고, 팀원에게는 안전한 채널로 공유하세요.
