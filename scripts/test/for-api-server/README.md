# API 테스트 스크립트 — 원격 서버 대상 (`for-api-server`)

[`../for-api-local`](../for-api-local/README.md) 과 같은 테스트를 **원격 서버**를 대상으로 실행하기
위한 버전입니다. curl.exe(REST) + `System.Net.WebSockets`(STOMP) 로 각 도메인 API 를 호출합니다.

## for-api-local 과의 차이

| 항목 | for-api-local | **for-api-server (이 폴더)** |
|------|---------------|------------------------------|
| 인증코드 확보 | 로컬 PostgreSQL 컨테이너에서 `docker exec` 로 자동 조회 | **사람이 직접 입력**(메일 또는 원격 DB 조회) |
| 로컬 도커 DB | 필요 | **불필요** |
| 유저 B(채팅 상대) 생성 | 10_chat 이 생성 | **02_auth 가 A 와 함께 생성**, 10_chat 은 로그인만 |
| 07 학교인증 | DB 로 미인증 강제 → 게이팅 차단 → 코드 심기 → verify | **정상 절차**(request → 수동 코드 → verify) 라운드트립 |
| 11 의도추출 AI | 스텁 AI(`../debug/ai-stub`) + DB 로 벡터/슬롯 직접 검증 | **실서버 FastAPI** 대상. DB 검증 불가 → `slotId` 로 간접 확인, 시나리오는 `missingFields` 기반 반복 |

핵심: 인증코드를 `docker exec` 로 읽던 부분만 **수동 입력**으로 바꾸면, API 서버가 어디 있든
(원격 VM 포함) 그대로 작동합니다. DB 를 직접 조작하던 07 의 미인증 강제/게이팅 차단 검증은
원격에서 재현할 수 없어 제외했고, 그 게이팅 검증은 08_social_kakao(미인증 카카오 유저)가 커버합니다.

## 인증코드 확인 방법

각 스크립트는 `email/request` 후 콘솔에서 6자리 코드를 물어봅니다. 코드는 둘 중 하나로 확인합니다.

1. **서버가 보낸 메일**에서 확인 → 접근 가능한 실제 이메일 주소를 테스트 계정으로 써야 합니다.
2. **원격 DB 조회** (pgAdmin 또는 psql). 서버가 `email_verifications` 에 저장한 최신 코드를 읽습니다:
   ```sql
   SELECT code FROM email_verifications WHERE email='test22@example.ac.kr' ORDER BY id DESC LIMIT 1;
   ```
   - 조회 이메일과 스크립트의 `-Email`(기본 `test22@example.ac.kr`)을 반드시 일치시키세요.
   - `email` 에 unique 제약이 없어 가장 최근(`id` 최대) 코드를 읽습니다.

> 코드 입력 프롬프트에서 그냥 **Enter**(빈 값)를 치면 해당 verify/signup 단계를 건너뜁니다.
> 이미 가입된 계정이라면 이후 login 은 코드 없이 진행됩니다.

## 구성

| 파일 | 대상 | 인증 |
|------|------|------|
| `00_common.ps1` | 공통 헬퍼 + **설정(CONFIG) 블록** (curl 호출, 토큰 저장/재사용, `.env` 로드, 수동 코드 입력) | - |
| `01_health.ps1` | Health (헬스체크) | 불필요 |
| `auth/00_before_auth.ps1` | 회원가입 전 원격 DB 정리 SQL 생성 (DB 직접 실행용) | - |
| `auth/02_auth.ps1` | Auth `/api/auth` — **유저 A·B 생성**(각각 수동 코드) 후 A 토큰 저장 | 불필요 |
| `03_user.ps1` | User `/api/users` | **필요** |
| `04_event.ps1` | Event `/api/events` | 일부 필요 |
| `05_team.ps1` | Team `/api/teams` | **필요** |
| `06_notification.ps1` | Notification `/api/notifications` | **필요** |
| `auth/07_school_auth.ps1` | 학교 이메일 인증 `/api/auth/school/email` (request→수동 코드→verify) | **필요** |
| `auth/08_social_kakao.ps1` | 카카오 소셜 로그인/회원가입 `/api/auth/social/kakao` | 불필요 |
| `10_chat.ps1` | Chat `/api/chat` + **WebSocket(STOMP)** 양방향 송수신 (B 는 로그인만) | **필요** |
| `11_matching_intent.ps1` | Matching Intent `/api/matching/intents` — 별도 **AI 서버(FastAPI)** 연동 | **필요** |
| `99_run_all.ps1` | 위 스크립트 전체 순차 실행 | - |

## 설정(CONFIG)

모든 설정은 `00_common.ps1` 최상단의 `$MateonConfig` 블록에서 관리하며, 같은 폴더의 `.env` 로
덮어쓸 수 있습니다(`.env` > 셸 환경변수 > 기본값 순).

| 설정 | 셸 환경변수 | 기본값 | 용도 |
|------|-------------|--------|------|
| BaseUrl | `MATEON_BASE_URL` | `http://localhost:8080` | **원격 서버 주소 — 반드시 지정** |
| TestEmail | `MATEON_TEST_EMAIL` | `test22@example.ac.kr` | 유저 A 이메일 |
| TestPassword | `MATEON_TEST_PASSWORD` | `Password1234` | 유저 A 비밀번호 |
| TestName | `MATEON_TEST_NAME` | `테스트유저` | 유저 A 이름 |
| UserBEmail | `MATEON_USERB_EMAIL` | `chatmate@example.ac.kr` | 유저 B(채팅 상대) 이메일 |
| UserBPassword | `MATEON_USERB_PASSWORD` | `Password1234` | 유저 B 비밀번호 |
| UserBName | `MATEON_USERB_NAME` | `채팅메이트` | 유저 B 이름 |
| SchoolEmail | `MATEON_SCHOOL_EMAIL` | (빈 값) | 학교(재학생) 인증 대상 이메일 (`auth/00_before_auth.ps1` 에서 정리 대상으로 사용) |
| KakaoAccessToken | `MATEON_KAKAO_ACCESS_TOKEN` | (빈 값) | 있으면 `auth/08_social_kakao.ps1` 이 실제 카카오 로그인까지 검증 |

`.env` 예시 (이 폴더에 두면 자동 로드, `.gitignore` 로 커밋 제외됨):
```ini
# scripts/test/for-api-server/.env  (커밋 금지)
MATEON_BASE_URL=https://your-remote-server.example.com
MATEON_TEST_EMAIL=test22@example.ac.kr
MATEON_USERB_EMAIL=chatmate@example.ac.kr
```

## 사전 준비

- **PowerShell 7 (pwsh)** 권장 — 특히 `10_chat.ps1` 의 WebSocket(STOMP) 은 pwsh 7 이 필요합니다.
  (Windows PowerShell 5.1 의 `ClientWebSocket` 은 Connection 헤더를 거부해 STOMP 연결이 실패합니다.)
- `.env` 에 `MATEON_BASE_URL` 을 원격 서버 주소로 지정.
- 인증코드를 읽을 수단: 접근 가능한 실제 메일함, 또는 원격 DB 접속(pgAdmin/psql).

## 실행 방법

`auth` 폴더 내의 스크립트들(`00_before_auth`, `02_auth`, `07_school_auth`, `08_social_kakao`)은 계정 생성, 소셜 로그인 등 테스트 환경 구성이 필요할 때 **필요 시에만 개별적으로 실행**하십시오.
`99_run_all.ps1` 은 이미 생성된 계정으로 로그인만 수행하여 토큰을 획득한 후 API 테스트를 진행하며, `auth` 폴더의 스크립트들은 자동으로 실행하지 않습니다.

각 스크립트는 독립 실행 가능하지만, **인증이 필요한 스크립트는 먼저 로그인이 선행**되어야 합니다. 로그인 성공 시 accessToken 이 `.auth-token.txt` 에 저장되어 이후 스크립트가 재사용합니다.

```powershell
# [필요 시] 회원가입 전 테스트 계정 DB 정리 SQL 생성 및 복사
pwsh -File .\auth\00_before_auth.ps1 -Clip

# [필요 시] 유저 A·B 신규 생성 (각각 코드 수동 입력) — 전체 테스트 전 계정 세팅
pwsh -File .\auth\02_auth.ps1 -Email me@example.ac.kr -Password Password1234

# 개별 실행
pwsh -File .\03_user.ps1
pwsh -File .\auth\07_school_auth.ps1   # [필요 시] 학교 이메일 코드도 수동 입력
pwsh -File .\10_chat.ps1               # 유저 B 는 로그인만 (코드 불필요)

# 전체 순차 실행 (계정 생성을 생략하고 로그인만으로 테스트 진행)
pwsh -File .\99_run_all.ps1 -Email me@example.ac.kr -Password Password1234
```

## 주의

- `.auth-token.txt`, `.refresh-token.txt` 에 토큰이 평문 저장됩니다. (`.gitignore` 로 커밋 제외)
- 비밀번호 변경/로그아웃/팀 삭제/지원 취소 등 **부작용이 큰 호출은 기본적으로 주석 처리**되어
  있습니다. 필요 시 해당 스크립트에서 주석을 해제하세요.
- 같은 이메일로 두 번째부터는 signup 이 `EMAIL_ALREADY_EXISTS` 로 실패하지만, 이어지는 login 이
  성공해 토큰은 정상 저장됩니다.
