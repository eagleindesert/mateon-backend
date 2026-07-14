# parallel-chat (for-api-server) — 두 창으로 실시간 채팅 눈으로 확인하기

원격 서버(`for-api-server`) 를 대상으로 실행하는 버전입니다. [`../../for-api-local/parallel-chat`](../../for-api-local/parallel-chat/README.md)
와 화면 동작은 동일하며, 계정 준비 시 이메일 인증코드를 **사람이 직접 입력**한다는 점만 다릅니다
(원격 DB 는 `docker exec` 로 조회할 수 없기 때문 — 상위 폴더 [README](../README.md#인증코드-확인-방법) 참고).

`10_chat.ps1` 은 한 프로세스 안에서 소켓 2개로 **자동 검증**(PASS/FAIL)을 하지만,
여기 스크립트는 **두 개의 PowerShell 창을 띄워** 사람이 직접 A·B 유저로 메시지를
주고받으며 실시간 브로드캐스트를 눈으로 확인하는 용도입니다.

## 구성

| 파일 | 역할 |
|------|------|
| `launch.ps1` | 유저 A·B 준비 + DM 방 생성 후 **채팅 창 2개 자동 실행** (보통 이것만 실행) |
| `chat-client.ps1` | 한 유저용 인터랙티브 클라이언트 (창 1개 = 유저 1명). 직접 실행도 가능 |
| `_stomp-lib.ps1` | STOMP-over-WebSocket 헬퍼 (연결/구독/발행). `10_chat.ps1` 로직을 재사용 분리 |

## 사전 조건

0. **PowerShell 7(pwsh) 설치 필수.** Windows PowerShell 5.1(`powershell.exe`)의 .NET Framework
   `ClientWebSocket` 은 서버가 보내는 `Connection: upgrade, keep-alive` 헤더를 거부해
   WebSocket 연결이 실패합니다(`'Connection' 헤더 값 ... 잘못되었습니다`). PowerShell 7 은 정상 동작합니다.
   ```powershell
   winget install --id Microsoft.PowerShell --source winget   # 설치 후 터미널 새로 열기
   pwsh --version
   ```
   `launch.ps1` 은 채팅 창을 띄울 때 `pwsh.exe` 를 **자동으로 우선 사용**합니다(없으면 경고 후 5.1 fallback).
1. 원격 서버 주소를 상위 폴더 `.env` 의 `MATEON_BASE_URL` 로 지정합니다(미지정 시 `00_common.ps1`
   기본값인 `http://localhost:8080` 으로 폴백하므로 반드시 지정하세요).
2. 유저 자동 생성 시 이메일 인증코드는 **사람이 직접 입력**합니다 — 원격 DB 는 `docker exec` 로 조회할
   수 없으므로, 서버가 보낸 메일에서 확인하거나 원격 DB(pgAdmin/psql)로 `email_verifications.code` 를
   조회해 넣으세요(이미 두 계정이 가입돼 있으면 login 만으로 진행되어 코드 입력이 필요 없습니다).
3. 설정(BaseUrl·계정 등)은 상위 폴더 `00_common.ps1` / `.env` 를 그대로 따릅니다.
   - 유저 A = `TestEmail` (`.env` 의 `MATEON_TEST_EMAIL`, 기본값 없음 — 반드시 지정)
   - 유저 B = `UserBEmail` (`.env` 의 `MATEON_USERB_EMAIL`, 기본값 없음 — 반드시 지정)

## 실행 (권장)

```powershell
# for-api-server\parallel-chat 폴더에서
powershell -ExecutionPolicy Bypass -File .\launch.ps1
```

- 런처 창이 A·B 계정을 준비하고 DM 방을 만든 뒤 **새 창 2개**(A=파랑, B=초록)를 띄웁니다.
- 아무 창에서나 메시지를 입력하고 Enter → **상대 창에 노란색으로 실시간 표시**됩니다.
- 내가 보낸 메시지는 서버가 되돌려주며 내 창에 회색 `나 ✓` 로 찍혀 **서버 반영(저장+브로드캐스트)** 을 확인시켜 줍니다.
- 종료: 각 창에서 `/quit` 또는 `/exit`.

### 계정 지정

```powershell
powershell -ExecutionPolicy Bypass -File .\launch.ps1 `
    -UserAEmail alice@example.ac.kr -UserBEmail bob@example.ac.kr
```

## 직접 한 창만 띄우기 (수동)

이미 방 번호를 알고 있다면 클라이언트를 단독 실행할 수 있습니다.

```powershell
powershell -ExecutionPolicy Bypass -File .\chat-client.ps1 `
    -Label A -Email test19@example.ac.kr -Password Password1234 -RoomId 3 -Color Cyan
```

## 동작 원리 (왜 창 하나로 송·수신이 같이 되나)

- **수신**: 백그라운드 런스페이스가 `ClientWebSocket.ReceiveAsync` 로 브로드캐스트를 상시 수신해
  `[Console]::WriteLine` 으로 콘솔에 바로 출력합니다.
- **전송**: 메인 스레드가 `Read-Host` 로 한 줄씩 입력받아 `/app/chat.send` 로 발행합니다.
  (한글 IME 입력을 위해 char 단위가 아닌 라인 입력을 사용합니다.)
- `ClientWebSocket` 은 동시에 1건 송신 + 1건 수신을 허용하므로 이 조합이 안전합니다.
- 로그인 토큰은 **메모리에만** 보관해 공용 `.auth-token.txt` 를 건드리지 않습니다
  (두 창이 서로의 토큰을 덮어쓰지 않도록).

## 색 규칙

| 표시 | 의미 |
|------|------|
| 노란색 `상대이름 ▶ ...` | 상대가 보낸 메시지 (실시간 수신) |
| 회색 `나 ✓ ...` | 내가 보낸 메시지가 서버에 반영되어 되돌아온 것 |
| 파랑(A) / 초록(B) | 창(=나) 테마 색 |
