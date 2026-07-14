# API 테스트 스크립트 (curl.exe 기반 PowerShell)

[docs/API_SPEC.md](../../docs/API_SPEC.md) 의 목차 구성에 맞춰 각 도메인별 API 를
`curl.exe` 로 호출하는 PowerShell 스크립트 모음입니다.

## 구성

| 파일 | 대상 | 인증 |
|------|------|------|
| `00_common.ps1` | 공통 헬퍼 + **설정(CONFIG) 블록** (curl 호출, 토큰 저장/재사용, `.env` 로드) | - |
| `01_health.ps1` | Health (헬스체크) | 불필요 |
| `02_auth.ps1` | Auth `/api/auth` (로그인 후 토큰 저장) | 불필요 |
| `03_user.ps1` | User `/api/users` | **필요** |
| `04_event.ps1` | Event `/api/events` | 일부 필요 |
| `05_team.ps1` | Team `/api/teams` | **필요** |
| `06_notification.ps1` | Notification `/api/notifications` | **필요** |
| `07_school_auth.ps1` | 학교(재학생) 인증 & 게이팅 `/api/auth/school` | **필요** |
| `08_social_kakao.ps1` | 카카오 소셜 로그인/회원가입 `/api/auth/social/kakao` | 불필요 |
| `10_chat.ps1` | Chat `/api/chat` + **WebSocket(STOMP)** 양방향 송수신 | **필요** |
| `99_run_all.ps1` | 위 스크립트 전체 순차 실행 | - |

> `10_chat.ps1` 은 curl(REST) 뿐 아니라 `System.Net.WebSockets` 로 실제 STOMP 메시지를
> 주고받아 양방향 채팅을 검증한다. 2번째 유저(B)를 자동 생성/로그인하므로 이메일 인증코드
> 조회용 **로컬 PostgreSQL 도커 컨테이너**가 필요하다. (B 이메일/비번은 `-UserBEmail` 등으로 변경 가능)

### 설정(CONFIG)
모든 설정은 `00_common.ps1` 최상단의 `$MateonConfig` 블록에서 한 번에 관리합니다.
우선순위는 바로 위 `$DotEnvOverridesShell` 토글로 정합니다:

- `$true` (**기본**): `.env` 파일 > 셸 환경변수 > 기본값(Default) — **`.env` 가 이김**
- `$false`: 셸 환경변수 > `.env` 파일 > 기본값(Default) — 셸이 이김

(`$true` 여도 `.env` 에 없는 키는 셸 값이, 그것도 없으면 Default 가 적용됩니다.)

| 설정 | 셸 환경변수 | 기본값 | 용도 |
|------|-------------|--------|------|
| EnvFile | `MATEON_ENV_FILE` | `<폴더>/.env` | `.env` 파일 경로 |
| BaseUrl | `MATEON_BASE_URL` | `http://localhost:8080` | API 서버 주소 |
| PgContainer | `MATEON_PG_CONTAINER` | `mateon-postgres` | PostgreSQL 도커 컨테이너 |
| PgUser | `MATEON_PG_USER` | `admin` | psql 접속 계정 |
| PgDatabase | `MATEON_PG_DB` | `mateon_db` | psql 대상 DB 이름 |
| JwtSecret | `MATEON_JWT_SECRET` | (빈 값) | 예약 — 현재 미사용 |
| KakaoAccessToken | `MATEON_KAKAO_ACCESS_TOKEN` | (빈 값) | 있으면 `08_social_kakao.ps1` 이 실제 카카오 로그인까지 검증 |

> 카카오 앱 설정(REST API 키·redirect_uri·client secret)은 **디버그 전용**이라 위 표에 없다.
> 실제 카카오 토큰이 필요하면 [`../debug/oauth/README.md`](../debug/oauth/README.md) 의 `get-kakao-token.ps1`
> 로 인가코드 교환~`.env` 주입을 자동화할 수 있다(해당 키는 그 폴더 `.env` 에 둔다).

## 사전 준비

1. 백엔드 서버 실행 (기본 `http://localhost:8080`)
   ```powershell
   ./gradlew bootRun
   ```
2. 대상 서버 주소 지정 — 우선순위: **셸 환경변수 > `.env` 파일 > 코드 기본값(`localhost:8080`)**
   - **`.env` 파일 (권장, 반복 사용)**: 이 폴더에 `.env` 를 만들고 아래처럼 작성한다.
     `00_common.ps1` 로드 시 같은 폴더의 `.env` 를 자동으로 읽으며(`Import-DotEnv`),
     `.env` 는 `.gitignore` 의 `*.env` 패턴으로 커밋에서 제외된다.
     ```ini
     # scripts/test/for-rest-api/.env  (커밋 금지)
     # 테스트 대상 서버 주소 (뒤에 /api/... 가 붙는다)
     MATEON_BASE_URL=http://localhost:8080/fmewlkmflewmlk
     ```

## 실행 방법

각 스크립트는 독립 실행 가능하지만, **인증이 필요한 스크립트는 먼저 `02_auth.ps1`
로 로그인**해야 합니다. 로그인 성공 시 accessToken 이 `.auth-token.txt` 에 저장되어
이후 스크립트가 자동으로 재사용합니다.

```powershell
# 개별 실행
powershell -ExecutionPolicy Bypass -File .\01_health.ps1
powershell -ExecutionPolicy Bypass -File .\02_auth.ps1 -Email me@example.ac.kr -Password Password1234
powershell -ExecutionPolicy Bypass -File .\03_user.ps1

# 전체 실행
powershell -ExecutionPolicy Bypass -File .\99_run_all.ps1 -Email me@example.ac.kr -Password Password1234
```

### 신규 회원가입까지 자동 진행

`02_auth.ps1` 은 실제 엔드포인트(`request`→`verify`→`signup`)를 그대로 밟습니다.
이메일 인증코드는 서버가 메일로 발송하지만, 이 스크립트는 메일 확인 대신
`email_verifications.code` 를 `docker exec` 로 읽어(정식 절차 그대로) 자동으로 verify 합니다.
따라서 기본 실행만으로 인증/회원가입까지 진행됩니다. (로컬에 DB 컨테이너가 있어야 함)

```powershell
# 기본: request → DB 에서 코드 조회 → verify → signup 까지 자동 진행
powershell -ExecutionPolicy Bypass -File .\02_auth.ps1 -Email new@example.ac.kr

# 메일로 받은 코드를 직접 지정하고 싶으면 -Code 로 넘긴다 (DB 조회 생략)
powershell -ExecutionPolicy Bypass -File .\02_auth.ps1 -Email new@example.ac.kr -Code 123456
```

## 원격 VM 서버를 대상으로 실행할 때 추가 조치

기본 자동 인증(`Get-EmailVerificationCode`)은 **스크립트를 실행하는 로컬 머신에서**
`docker exec` 로 PostgreSQL 의 `email_verifications.code` 를 읽는다. 따라서 DB 가 원격 VM 에
있으면 로컬 조회는 그 DB 에 닿지 않는다 → **VM 에서 코드를 읽어 `-Code` 로 넘기거나**,
서버가 실제로 발송한 메일의 코드를 `-Code` 로 넘겨야 한다.

1. VM 에 SSH 접속 후, request 로 발급된 코드를 조회한다(테스트 이메일 `00_common.ps1` 의
   `TestEmail` 기본값 사용). 먼저 로컬에서 `.env` 의 `MATEON_BASE_URL` 을 VM 주소로 두고
   `02_auth.ps1` 을 한 번 실행해 request 를 보낸 뒤:
   ```bash
   docker exec mateon-postgres psql -U admin -d mateon_db -t -A -c "SELECT code FROM email_verifications WHERE email='test1@example.ac.kr' ORDER BY id DESC LIMIT 1;"
   ```
   - `email_verifications.email` 에 unique 제약이 없어 가장 최근(`id` 최대) 코드를 읽는다.
   - **조회 이메일과 스크립트의 `-Email`(기본 `test1@example.ac.kr`)을 반드시 일치**시킬 것.

2. 조회한 코드를 `-Code` 로 넘겨 verify + signup + login 을 진행한다:
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\02_auth.ps1 -Code 123456
   # 또는 전체
   powershell -ExecutionPolicy Bypass -File .\99_run_all.ps1 -Code 123456
   ```
   - 이메일이 고정이라 **두 번째 실행부터** signup 은 `EMAIL_ALREADY_EXISTS` 로 실패하지만, 이어지는
     login 이 성공해 토큰은 정상 저장된다. 깨끗한 가입부터 다시 하려면 VM 에서 유저를 지운다:
     ```bash
     docker exec mateon-postgres psql -U admin -d mateon_db -c "DELETE FROM users WHERE email='test1@example.ac.kr';"
     ```

## 주의

- `.auth-token.txt`, `.refresh-token.txt` 에 토큰이 평문 저장됩니다. (VCS 커밋 금지)
- 비밀번호 변경/로그아웃/팀 삭제/지원 취소 등 **부작용이 큰 호출은 기본적으로
  주석 처리**되어 있습니다. 필요 시 해당 스크립트에서 주석을 해제하세요.
