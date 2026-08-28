# AI 서버 스텁

별도 FastAPI AI 서버를 흉내내는 로컬 스텁이다. 처리하는 엔드포인트:

| 엔드포인트 | 용도 |
|---|---|
| `POST /intents/extract` | 매칭 의도 추출 (`/api/matching/intents/**` 연동 검증) |
| `POST /internal/teams/embedding:refresh` | 팀 임베딩 계산 (팀 생성/수정 시 비동기 호출 검증) |
| `POST /recommendations/user-to-team` | 유저→팀 추천 점수 계산 |
| `POST /recommendations/team-to-user` | 팀→유저 역제안 추천 점수 계산 |
| `POST /recommendations/reason` | 선택된 한 쌍의 상세 이유 |
| `POST /selection-events` | 실제로 고른 후보 + 그때 노출된 목록 기록 |
| `POST /proposals/user-to-team` | 최종 제안 조립 (지원 문구 초안) |
| `POST /proposals/team-to-user` | 최종 역제안 조립 (제안 문구 초안) |
| `POST /contests/extract-image` | 포스터 이미지에서 공모전 정보 추출 (multipart) |
| `POST /portfolios/summarize` | 포트폴리오 PDF 요약 (multipart) |
| `GET /__stub` | 신원 확인. LiveTest 전용 (아래 참고) |

실제 FastAPI 를 띄울 수 없는 상황에서 백엔드 연동을 검증하기 위한
도구다. **실제 서버가 준비되면 이 스텁은 필요 없다** — `.env` 의 `AI_BASE_URL` 만 실제 주소로
바꾸면 된다.

## 진짜 목적

응답을 흉내내는 것보다, **백엔드가 보내는 요청을 눈으로 확인하는 것**이 이 스텁의 존재 이유다.
호출마다 요청을 콘솔에 덤프하고 자체 검증한다:

- (intents) `id` 가 1 부터 연속 증가하는가 (백엔드가 DB 의 `seq` 대신 재채번하는지)
- (intents) USER 발화만 들어있는가 (`assistant_message` 가 섞이지 않았는지), 호출할 때마다 누적되는가
- (teams) `intro_text`/`recruiting_roles`/`required_skills`/`contest_field` 가 제대로 실려 오는가
- (recommendations) `query_metadata` 가 실려 오는가, 후보마다 1536 차원 벡터와 정규화 메타데이터가
  붙어 오는가, 제외 대상(내 팀/지원한 팀)이 빠졌는가
- (selection-events) `component_scores` 가 추천 응답에서 준 것과 **키·값까지 똑같이** 돌아오는가.
  문자열로 오면 `[!!]` 로 표시한다 (`@JsonRawValue` 누락)
- (proposals) `sender_id`/`receiver_id` 가 방향에 맞는가 — 스텁이 직접 대조해 `[OK]`/`[!!]` 로 표시
- (multipart) 파트 이름이 `pdf_file`/`img_file` 인가, `filename` 파라미터가 실려 있는가
  (없으면 실서버 FastAPI 는 UploadFile 로 인식하지 못해 422 를 낸다)
- **`X-Internal-Secret` 헤더를 실어 보내는가** (실서버는 이게 없으면 401)

> 시크릿은 마스킹해서 출력한다(`ab****yz (len=12)`). 도착 여부와 길이만 알면 검증에 충분하고,
> 로컬 디버그 도구라도 콘솔 출력이 붙여넣기로 새어나갈 수 있기 때문이다.

## 사용법

**pwsh 7 이상**에서 실행한다.

```powershell
# 1) 스텁 서버 기동 (별도 터미널)
pwsh -File scripts/test/debug/ai-stub/stub-ai-server.ps1 -Port 8001

# 2) 백엔드를 이 주소로 띄운다 — .env 에 추가
#    AI_BASE_URL=http://localhost:8001
#    AI_INTERNAL_SECRET=dev-secret      ← 없으면 백엔드가 부팅되지 않는다
./gradlew bootRun

# 3) E2E 실행
pwsh -File scripts/test/for-api/11_matching_intent.ps1
```

포트를 바꿨다면 백엔드의 `AI_BASE_URL` 과 반드시 맞춰야 한다.

`AI_INTERNAL_SECRET` 은 **스텁을 쓸 때도 반드시 있어야 한다**. 값 자체는 아무거나 좋다(스텁은
`-ExpectedSecret` 을 주지 않으면 검증하지 않는다). 백엔드가 이 값을 필수로 요구하는 건
`JWT_SECRET` 과 같은 관례로, 시크릿 없이 실서버를 호출하다 401 을 맞는 상황을
부팅 시점에 막기 위해서다.

시크릿 검증까지 재현하려면 양쪽 값을 맞춘다:
```powershell
pwsh -File stub-ai-server.ps1 -ExpectedSecret "dev-secret"   # .env 의 AI_INTERNAL_SECRET 과 동일하게
```

## 동작

`POST /intents/extract` — 받은 `messages` 개수로 분기한다:

| messages 개수 | 응답 |
|---|---|
| 1개 | `missing_fields=["experience_level"]`, `embedding_*=null` → 재질문 |
| 2개 이상 | `missing_fields=[]`, `embedding_vector`=1536개 난수 → 완료 |

즉 E2E 에서 메시지를 두 번 보내면 재질문 → 완료 흐름을 그대로 밟는다.

`POST /internal/teams/embedding:refresh` — 항상 임베딩 + `metadata` 를 반환한다.
`missing_fields=["activity_intensity"]` 로 고정 — 스펙상 미추출 항목이 있어도 벡터는 함께 온다는
특성을 그대로 재현한다. `metadata.recruiting_roles`/`required_skills` 는 요청 값을 에코한다.

`POST /recommendations/user-to-team`, `POST /recommendations/team-to-user` — 역할이 겹치면
(`desired_roles` ∩ `recruiting_roles`) 0.9 대, 아니면 0.1 대로 점수를 가르고 `label` 을 만든다.
**일부러 점수 오름차순으로 돌려준다** — 백엔드가 내림차순으로 다시 세우는지 확인하기 위해서다.
역제안은 질의와 후보의 자리가 뒤집힌다(질의=팀, 후보=유저).

`POST /recommendations/reason` — 받은 세 요약을 찍고 `[stub#N]` 문장을 반환한다. `N` 은 호출
일련번호라, 같은 쌍을 두 번 물었는데 `N` 이 그대로면 백엔드의 이유 캐시가 동작한 것이다.

`POST /proposals/*` — 받은 식별자를 에코하고 `[stub#N]` 초안(`summary`+`message`)을 반환한다.
초안은 캐시하지 않는 게 정상이라, 여기서는 반대로 `N` 이 매번 올라가야 한다.

`POST /selection-events` — 노출 목록과 선택된 후보를 찍고 `{ accepted: true }` 를 반환한다.
저장 확인이 아니라 접수 확인이라 응답에 볼 게 없고, 값어치는 전부 콘솔 대조에 있다.

`POST /portfolios/summarize` — 파트명 `pdf_file` 과 `%PDF` 시그니처를 확인하고 `[stub#N]` 요약을
반환한다. `pdf_id` 는 고정 더미값이라 백엔드 로그에 "해시 불일치" 경고가 뜨는 게 정상이다
(백엔드는 AI 값이 아니라 자기가 계산한 SHA-256 을 캐시 키로 쓴다).

`POST /contests/extract-image` — 파트명 `img_file` 을 확인하고 공모전 추출 결과를 반환한다.
`image_url`/`detail_url`/`external_id` 는 null 로 둔다 — 백엔드가 자기 버킷 URL 로 채우는지
확인할 수 있게 하기 위해서다.

## LiveTest (`./gradlew liveTest`)

E2E 스크립트 없이, JUnit 이 직접 이 스텁에 붙어 **AI 응답이 DTO 로 채워지는지와 DB 까지
저장되는지**를 확인한다. 백엔드를 띄울 필요가 없다.

```powershell
# 1) 스텁 기동 (별도 터미널)
pwsh -File scripts/test/debug/ai-stub/stub-ai-server.ps1 -Port 8001 -ExpectedSecret "stub-secret"

# 2) 게이트 변수를 주고 실행 (Docker 도 떠 있어야 한다 — 저장 테스트가 Postgres 를 띄운다)
$env:AI_STUB_BASE_URL = "http://localhost:8001"
$env:AI_STUB_SECRET   = "stub-secret"
./gradlew liveTest
```

`AI_STUB_BASE_URL` 이 없으면 **전건 건너뛴다**(실패가 아니다). "통과"만 보고 안심하지 말고
실행 건수를 확인할 것.

`AI_BASE_URL` 을 재사용하지 않고 전용 변수를 두는 이유는, 개발하다 보면 그 값이 셸에 떠 있게
되어 테스트가 의도 없이 실서버를 때리기 때문이다. 전용 변수를 일부러 세팅하는 행위 자체가
동의여야 한다.

`GET /__stub` 은 그 게이트의 두 번째 겹이다. 변수 이름만으로는 실수로 넣은 실서버 주소를
막지 못하므로, LiveTest 는 먼저 이 경로를 물어 `{"stub":"mateon-ai-stub"}` 이 오는지 확인하고
아니면 **아무 호출도 하지 않고 건너뛴다.**

`*LiveTest` 는 `./gradlew test` 와 `fastTest` 양쪽에서 이름으로 빠져 있다. 밖에 뭔가가 떠
있어야만 의미가 있는 테스트라 어느 관문에도 걸지 않는다.

## 옵션

| 파라미터 | 기본값 | 설명 |
|---|---|---|
| `-Port` | `8000` | 리스닝 포트 |
| `-EmbeddingDimension` | `1536` | 임베딩 차원. `user_embeddings.embedding` 이 `vector(1536)` 이므로 기본값을 바꾸면 백엔드가 502 로 거른다 (차원 검증 테스트용) |
| `-ExpectedSecret` | (없음) | 주면 `X-Internal-Secret` 을 검증해 불일치/누락 시 **401**. 안 주면 받은 값을 마스킹해 출력만 한다 |

중지는 `Ctrl+C`.
