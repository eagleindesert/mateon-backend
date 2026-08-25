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
| `dev` | [application-dev.yml](src/main/resources/application-dev.yml) | 기본값. `bootRun` / IDE 실행은 아무 설정 없이 여기로 오고, **배포 서버도 지금은 이 프로필로 돕니다** |
| `prod` | [application-prod.yml](src/main/resources/application-prod.yml) | **아직 켜는 곳이 없습니다** (아래 참고) |
| `test` | [application-test.yml](src/test/resources/application-test.yml) | `IntegrationTestBase` 의 `@ActiveProfiles("test")` |

> **`prod` 는 미리 만들어만 둔 프로필입니다.** 운영 환경이 아직 없고,
> [docker-compose-deployment.yml](docker-compose-deployment.yml) 이 띄우는 서버는 "배포된 개발
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
| `./.env` | 포트·오리진·이미지 태그 등 일반 설정 | ❌ |
| `./.env.secret` | 시크릿과 계정 식별자 | ❌ |
| `./scripts/docker/.env` | DockerHub 자격증명 ([deploy-dockerhub.ps1](scripts/docker/deploy-dockerhub.ps1) 전용) | ❌ |

처음 받았다면 템플릿을 복사해서 값을 채우세요.

```powershell
Copy-Item .env.example .env
Copy-Item .env.secret.example .env.secret
```

`./.env` 와 `./.env.secret` 은 **내 PC 와 배포 서버가 각자 다른 내용**을 갖습니다
(같은 이름의 다른 파일입니다). 배포 서버에 올릴 때는 `.env.secret` 을 먼저 올리세요 —
없으면 `docker compose up` 이 통째로 실패합니다.
