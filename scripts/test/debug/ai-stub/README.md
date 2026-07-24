# AI 서버 스텁

별도 FastAPI AI 서버를 흉내내는 로컬 스텁이다. 처리하는 엔드포인트:

- `POST /intents/extract` — 매칭 의도 추출 (`/api/matching/intents/**` 연동 검증)
- `POST /internal/teams/embedding:refresh` — 팀 임베딩 계산 (팀 생성/수정 시 비동기 호출 검증)

실제 FastAPI 를 띄울 수 없는 상황에서 백엔드 연동을 검증하기 위한
도구다. **실제 서버가 준비되면 이 스텁은 필요 없다** — `.env` 의 `AI_BASE_URL` 만 실제 주소로
바꾸면 된다.

## 진짜 목적

응답을 흉내내는 것보다, **백엔드가 보내는 요청을 눈으로 확인하는 것**이 이 스텁의 존재 이유다.
호출마다 요청을 콘솔에 덤프하고 자체 검증한다:

- (intents) `id` 가 1 부터 연속 증가하는가 (백엔드가 DB 의 `seq` 대신 재채번하는지)
- (intents) USER 발화만 들어있는가 (`assistant_message` 가 섞이지 않았는지), 호출할 때마다 누적되는가
- (teams) `intro_text`/`recruiting_roles`/`required_skills`/`contest_field` 가 제대로 실려 오는가
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

## 옵션

| 파라미터 | 기본값 | 설명 |
|---|---|---|
| `-Port` | `8000` | 리스닝 포트 |
| `-EmbeddingDimension` | `1536` | 임베딩 차원. `user_embeddings.embedding` 이 `vector(1536)` 이므로 기본값을 바꾸면 백엔드가 502 로 거른다 (차원 검증 테스트용) |
| `-ExpectedSecret` | (없음) | 주면 `X-Internal-Secret` 을 검증해 불일치/누락 시 **401**. 안 주면 받은 값을 마스킹해 출력만 한다 |

중지는 `Ctrl+C`.
