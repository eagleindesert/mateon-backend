# websocket-javascript (debug) — Node.js로 두 창 실시간 채팅 테스트하기

`../../for-api-server/parallel-chat`(PowerShell 판)와 같은 방식으로, 유저 A·B 의 실시간 채팅을
**콘솔 창 2개**로 띄워서 눈으로 확인하는 순수 Node.js 버전입니다. **npm install 이 필요 없습니다** —
Node 18+ 의 전역 `fetch`, Node 21+ 의 전역 `WebSocket` 만 사용합니다 (`node --version` 으로 확인,
이 레포 기준 v24 확인됨).

## 구성

| 파일 | 역할 |
|------|------|
| `launch.js` | 유저 A·B 로그인 + DM 방 준비 후 **채팅 창 2개 자동 실행** (보통 이것만 실행) |
| `chat-client.js` | 한 유저용 인터랙티브 클라이언트 (창 1개 = 유저 1명). `launch.js` 가 새 콘솔 창으로 띄운다 |
| `.env` | 이 폴더 전용 설정 (커밋 금지). `scripts/test/debug/oauth/.env` 와 같은 패턴 — for-api-server 폴더 밖에 있어도 동작하도록 자체 `.env` 를 둔다 |

## 사전 조건

1. 이 폴더의 `.env` 에 `MATEON_BASE_URL`(원격 서버 주소), `MATEON_TEST_EMAIL`/`MATEON_TEST_PASSWORD`
   (유저 A), `MATEON_USERB_EMAIL`/`MATEON_USERB_PASSWORD`(유저 B) 가 설정되어 있어야 합니다.
   `../../for-api-server/.env` 를 거슬러 올라가 읽지 않으므로, 그쪽 값이 바뀌면 이 폴더의 `.env` 도
   직접 갱신하세요.
2. 두 계정이 이미 가입되어 있어야 합니다. 아직 없다면 `../../for-api-server/auth/02_auth.ps1` 로
   먼저 만들어 두세요 (`launch.js` 는 로그인만 시도하고, 계정을 새로 만들어주지는 않습니다).
3. 인증은 HTTP 헤더가 아니라 **STOMP CONNECT 프레임의 `Authorization: Bearer ...` 헤더**로 이루어집니다
   (브라우저/Node 의 `WebSocket` API 는 핸드셰이크에 커스텀 HTTP 헤더를 못 붙이기 때문 —
   서버 `StompAuthChannelInterceptor` 참고).
4. 현재 새 창 자동 실행은 **Windows(`cmd.exe`/`start`) 전용**입니다. macOS/Linux 에서는 `launch.js` 가
   방 준비까지만 하고, 각 창에서 수동으로 실행할 명령을 안내합니다.

## 실행

```bash
# websocket-javascript 폴더에서
node launch.js
```

- 유저 A·B 로 로그인 → DM 방 조회/생성(멱등) → **새 콘솔 창 2개**(A=파랑 계열, B=초록 계열)를 띄웁니다.
- 아무 창에서나 메시지를 입력하고 Enter → **상대 창에 노란색으로 실시간 표시**됩니다.
- 내가 보낸 메시지는 서버가 되돌려주는 걸 회색 `나 ✓` 로 표시해 **서버 반영(저장+브로드캐스트)**을
  확인시켜 줍니다.
- 종료: 각 창에서 `/quit` 또는 `/exit`.
- 계정 정보는 `.env` 에서만 읽고 커맨드라인에는 절대 노출하지 않습니다 — 새 창에는 임시 JSON
  파일 경로 하나만 전달되고, `chat-client.js` 가 그 파일을 읽는 즉시 삭제합니다.

### roomId 를 이미 알고 있을 때 (수동 실행)

`launch.js` 없이 `chat-client.js` 를 직접 띄울 수도 있습니다(비밀번호가 쉘 히스토리에 남으니
가능하면 `launch.js` 사용을 권장합니다):

```bash
node chat-client.js --label A --email test10@snu.ac.kr --password ****** --room 3 --color cyan
```

## 프로토콜 메모

- 엔드포인트: `/ws-stomp` (네이티브 WebSocket. `ws://` 또는 `wss://` 는 `MATEON_BASE_URL` 의 스킴에서 자동 변환)
- 발행: `/app/chat.send` — body `{ roomId, content }`
- 구독: `/topic/room.{roomId}`
- STOMP 프레임 형식은 `COMMAND\n header:value\n\n body\0` (NUL 종료) — `../../for-api-server/parallel-chat/_stomp-lib.ps1` 과 동일

## 참고: PowerShell 판과의 차이

- 구조와 동작(런처가 창 2개를 띄우고, 각 창이 독립적으로 송수신)은 PowerShell 판과 동일합니다.
- 별도 설치 없이 `node launch.js` 만으로 실행할 수 있어, PowerShell 환경이 없거나
  (`pwsh` 미설치 등) 빠르게 눈으로 확인만 하고 싶을 때 사용하세요.
- PowerShell 판(`launch.ps1`)은 계정이 없으면 회원가입까지 자동 진행하지만(이메일 인증코드는 사람이
  직접 입력), 이 JS 판은 계정이 이미 있다는 전제로 로그인만 시도합니다.
