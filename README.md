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

전체 API 명세는 [docs/API_SPEC.md](docs/API_SPEC.md) 를 참고하세요.

## 로컬 실행

`bootRun` 실행 시 Spring Boot Docker Compose 지원이 [compose-dev.yml](compose-dev.yml) 의
PostgreSQL / pgAdmin 을 자동으로 기동합니다. (Docker 가 실행 중이어야 합니다.)

```bash
./gradlew bootRun        # macOS / Linux
.\gradlew.bat bootRun    # Windows
```

- 애플리케이션: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- pgAdmin: `http://localhost:5050` (admin@admin.com / admin)

## 환경 변수 (.env) 위치 가이드

환경 변수는 용도에 따라 **두 개의 파일**로 나뉘며, 둘 다 Git 에 커밋되지 않습니다(`.gitignore` 처리됨).

### 1. 애플리케이션 실행용 — 프로젝트 루트 `./.env`

로컬 개발 시 [application.properties](src/main/resources/application.properties) 의
`spring.config.import=optional:file:.env[.properties]` 설정에 의해 자동으로 로드됩니다.
(파일이 없으면 무시되며, Docker 배포 환경에서는 compose 의 `env_file`/`environment` 로 주입됩니다.)

```dotenv
# === Gmail SMTP === 이메일 인증 발송용
MAIL_USERNAME=your-gmail@gmail.com
MAIL_PASSWORD=your-gmail-app-password   # Gmail 앱 비밀번호

# === JWT ===
JWT_SECRET=최소-256bit-이상의-시크릿-키
JWT_EXPIRATION=86400000          # access token (ms), 기본 1일
JWT_REFRESH_EXPIRATION=604800000 # refresh token (ms), 기본 7일

# === DataSource (선택) ===
# 미설정 시 localhost:5432 / admin / admin 기본값 사용
# SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/mateon_db
# SPRING_DATASOURCE_USERNAME=admin
# SPRING_DATASOURCE_PASSWORD=admin
```

### 2. 배포용 — `./scripts/docker/.env.deploy`

DockerHub 이미지 빌드·푸시 스크립트([scripts/docker/deploy-dockerhub.ps1](scripts/docker/deploy-dockerhub.ps1))가 사용합니다.

```dotenv
DOCKERHUB_USERNAME=your-dockerhub-username
# DOCKERHUB_TOKEN=dckr_pat_xxxxx   # CI 등 토큰 로그인 시에만
```

> ⚠️ 두 `.env` 파일에는 시크릿이 포함됩니다. 절대 커밋하지 말고, 팀원에게는 안전한 채널로 공유하세요.
