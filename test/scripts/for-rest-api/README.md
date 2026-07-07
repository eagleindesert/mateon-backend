# API 테스트 스크립트 (curl.exe 기반 PowerShell)

[docs/API_SPEC.md](../../docs/API_SPEC.md) 의 목차 구성에 맞춰 각 도메인별 API 를
`curl.exe` 로 호출하는 PowerShell 스크립트 모음입니다.

## 구성

| 파일 | 대상 | 인증 |
|------|------|------|
| `_common.ps1` | 공통 헬퍼 (curl 호출, 토큰 저장/재사용) | - |
| `01_health.ps1` | Health (헬스체크) | 불필요 |
| `02_auth.ps1` | Auth `/api/auth` (로그인 후 토큰 저장) | 불필요 |
| `03_user.ps1` | User `/api/users` | **필요** |
| `04_event.ps1` | Event `/api/events` | 일부 필요 |
| `05_team.ps1` | Team `/api/teams` | **필요** |
| `06_notification.ps1` | Notification `/api/notifications` | **필요** |
| `run_all.ps1` | 위 스크립트 전체 순차 실행 | - |

## 사전 준비

1. 백엔드 서버 실행 (기본 `http://localhost:8080`)
   ```powershell
   ./gradlew bootRun
   ```
2. 다른 주소를 쓰는 경우 환경변수로 지정
   ```powershell
   $env:MATEON_BASE_URL = "http://localhost:9090"
   ```

## 실행 방법

각 스크립트는 독립 실행 가능하지만, **인증이 필요한 스크립트는 먼저 `02_auth.ps1`
로 로그인**해야 합니다. 로그인 성공 시 accessToken 이 `.auth-token.txt` 에 저장되어
이후 스크립트가 자동으로 재사용합니다.

```powershell
# 개별 실행
powershell -ExecutionPolicy Bypass -File .\01_health.ps1
powershell -ExecutionPolicy Bypass -File .\02_auth.ps1 -Email me@dankook.ac.kr -Password Password1234
powershell -ExecutionPolicy Bypass -File .\03_user.ps1

# 전체 실행
powershell -ExecutionPolicy Bypass -File .\run_all.ps1 -Email me@dankook.ac.kr -Password Password1234
```

### 신규 회원가입까지 자동 진행

이메일 인증코드는 서버가 메일로 발송하므로 자동화할 수 없습니다.
`02_auth.ps1` 실행 → 메일로 받은 6자리 코드를 `-Code` 로 다시 넘기면
인증/회원가입까지 진행됩니다.

```powershell
# 1) 코드 요청
powershell -ExecutionPolicy Bypass -File .\02_auth.ps1 -Email new@dankook.ac.kr

# 2) 메일 수신 후 코드로 재실행 (회원가입 + 로그인)
powershell -ExecutionPolicy Bypass -File .\02_auth.ps1 -Email new@dankook.ac.kr -Code 123456
```

## 주의

- `.auth-token.txt`, `.refresh-token.txt` 에 토큰이 평문 저장됩니다. (VCS 커밋 금지)
- 비밀번호 변경/로그아웃/팀 삭제/지원 취소 등 **부작용이 큰 호출은 기본적으로
  주석 처리**되어 있습니다. 필요 시 해당 스크립트에서 주석을 해제하세요.
