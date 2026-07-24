# 99_run_all.ps1 (for-api-server) - 전체 API 테스트를 순서대로 실행 (원격 서버 대상)
#
# ============================================================================
#  [!] 과금 주의 - 전체 실행은 실제 LLM / 임베딩을 여러 번 호출합니다.
#      스크립트별 예상 호출:
#        05_team            2회  (팀 생성/수정 -> 비동기 임베딩 갱신)
#        11_matching_intent 최대 8회 (의도 추출 대화, 완료까지 되묻는 만큼)
#        12_team_embedding  3회  (팀 생성 2 + 수정 1)
#        13_recommendation  7회  (팀 생성 2 + 의도 추출 2 + 추천 점수화 3)
#        14_reverse_offer   6회  (팀 생성 2 + 의도 추출 2 + 역제안 점수화 2)
#        15_review          1회  (팀 생성 1 -> 비동기 임베딩 갱신)
#        16_recommendation_reason 8회 (팀 1 + 의도 4 + 점수화 2 + 이유 2, 캐시 hit 는 제외)
#        17_proposal_assembly    10회 (팀 1 + 의도 4 + 점수화 2 + 조립 3)
#      -> 1회 전체 실행에 대략 44회 안팎. 반복 실행하면 그만큼 누적됩니다.
#
#      과금 없이 돌리려면 백엔드가 로컬 스텁을 보게 하세요:
#        pwsh -File .\debug\ai-stub\stub-ai-server.ps1      # 포트 8000
#        백엔드 AI_BASE_URL=http://localhost:8000 으로 재기동
#      (스텁은 고정 응답이라 AI 품질 검증은 못 하지만 연동/스키마 검증에는 충분합니다.)
# ============================================================================
#
# 사용법:
#   powershell -ExecutionPolicy Bypass -File .\99_run_all.ps1
#   powershell -ExecutionPolicy Bypass -File .\99_run_all.ps1 -Email me@example.ac.kr -Password Password1234
#
# 이 러너는 인증 계열 스크립트(02_auth / 07_school_auth / 08_social_kakao)를 실행하지 않는다.
#   대신 채팅 등 인증이 필요한 테스트를 위해, 코드 입력 없이 유저 A 로그인만 해서 토큰을 확보한다.
#   (auth/ 스크립트는 필요할 때 개별 실행: pwsh -File .\auth\02_auth.ps1 ...)
#   - 전제: 유저 A/B 계정이 이미 존재해야 한다(신규 가입/코드 입력은 하지 않음).
#   - 15_review(협업 온도)는 유저 C 까지 필요하다. 없으면 그 항목만 조용히 스킵된다.
param(
    [string]$Email = "",          # 유저 A: 미지정 시 00_common 의 TestEmail
    [string]$Password = "",       # 유저 A: 미지정 시 00_common 의 TestPassword
    [string]$UserBEmail = "",     # 유저 B: 미지정 시 00_common 의 UserBEmail
    [string]$UserBPassword = "",  # 유저 B: 미지정 시 00_common 의 UserBPassword
    [string]$UserCEmail = "",     # 유저 C: 미지정 시 00_common 의 UserCEmail (15_review 전용)
    [string]$UserCPassword = ""   # 유저 C: 미지정 시 00_common 의 UserCPassword
)

. "$PSScriptRoot\00_common.ps1"

# 이전 실행 결과가 남아있지 않도록 집계 초기화
Reset-TestResults

# 과금 경고는 파일 상단 주석만으로는 놓치기 쉬워 실행 시에도 보여준다.
Write-Host ""
Write-Host "  [!] 이 실행은 실제 LLM/임베딩을 대략 44회 호출합니다 (과금 발생)." -ForegroundColor Yellow
Write-Host "      과금을 피하려면 백엔드 AI_BASE_URL 을 로컬 스텁으로 돌려두세요 - 파일 상단 주석 참고." -ForegroundColor DarkGray
Write-Host ""

# 유저 준비(09_three_users)에 넘길 인자. 09 는 A/B/C 를 슬롯별로 받는다.
$authArgs = @{ LoginOnly = $true }
if ($Email)         { $authArgs.EmailA    = $Email }
if ($Password)      { $authArgs.PasswordA = $Password }
if ($UserBEmail)    { $authArgs.EmailB    = $UserBEmail }
if ($UserBPassword) { $authArgs.PasswordB = $UserBPassword }
if ($UserCEmail)    { $authArgs.EmailC    = $UserCEmail }
if ($UserCPassword) { $authArgs.PasswordC = $UserCPassword }

# 유저 B 인자 (로그인 전용). 10_chat 과 13_recommendation 이 함께 쓴다 —
# 둘 다 "나 아닌 상대"가 필요하다(채팅 상대 / 추천 후보 팀의 팀장).
$chatArgs = @{}
if ($UserBEmail)    { $chatArgs.UserBEmail    = $UserBEmail }
if ($UserBPassword) { $chatArgs.UserBPassword = $UserBPassword }

Write-Host "===== 1) Health =====" -ForegroundColor Magenta
& "$PSScriptRoot\01_health.ps1"

# ===== 2) 유저 준비 (09_three_users) =====
# 02_auth 대신 09 를 쓴다. 02 는 항상 email/request 부터 밟아 매번 수동 코드 입력을 요구하는데,
# 09 는 로그인을 먼저 시도해 기존 계정이면 코드 없이 통과한다.
#   -LoginOnly: 계정이 없어도 가입 절차로 넘어가지 않는다. 무인 실행 중 프롬프트가 뜨면
#               러너 전체가 멈추기 때문이다. (계정 생성은 09 를 단독 실행)
# 09 는 A/B/C 슬롯을 채우고 활성 세션을 A 로 맞춰 두므로, 이후 인증 필요 테스트가 그대로 A 로 돈다.
Write-Host "`n===== 2) 유저 준비 (A/B/C 로그인) =====" -ForegroundColor Magenta
& "$PSScriptRoot\auth\09_three_users.ps1" @authArgs

Write-Host "`n===== 3) User =====" -ForegroundColor Magenta
& "$PSScriptRoot\03_user.ps1"

Write-Host "`n===== 4) Event =====" -ForegroundColor Magenta
# 등록(init)과 조회를 나눠 돈다. 조회 스크립트는 init 이 남긴 .event-ids.json 을 읽어
# '방금 등록한 활동이 검색에 잡히는지' 를 증분 검증하므로 순서가 중요하다.
& "$PSScriptRoot\04_00_event_init.ps1"
& "$PSScriptRoot\04_01_event.ps1"

Write-Host "`n===== 5) Team =====" -ForegroundColor Magenta
& "$PSScriptRoot\05_team.ps1"

Write-Host "`n===== 6) Notification =====" -ForegroundColor Magenta
& "$PSScriptRoot\06_notification.ps1"

# ===== 7) School Email Auth (비활성화) — 인증 계열은 이 러너에서 실행하지 않는다. =====
# 필요 시 개별 실행: pwsh -File .\auth\07_school_auth.ps1
# $schoolArgs = @{}
# if ($Email) { $schoolArgs.Email = $Email }
# & "$PSScriptRoot\auth\07_school_auth.ps1" @schoolArgs

# ===== 8) Social Login (Kakao) (비활성화) — 인증 계열은 이 러너에서 실행하지 않는다. =====
# 필요 시 개별 실행: pwsh -File .\auth\08_social_kakao.ps1
# & "$PSScriptRoot\auth\08_social_kakao.ps1"

Write-Host "`n===== 10) Chat (REST + WebSocket/STOMP) =====" -ForegroundColor Magenta
& "$PSScriptRoot\10_chat.ps1" @chatArgs

Write-Host "`n===== 11) Matching Intent (AI 의도 추출) =====" -ForegroundColor Magenta
# 원격 백엔드의 ai.base-url 이 살아있는 FastAPI 를 가리켜야 한다.
# 닿지 않으면 503(AI_SERVER_UNAVAILABLE)으로 실패하며, 스크립트가 원인을 안내한다.
#
# 아래 13 번도 선행 조건으로 의도 추출을 수행하지만(13.4~13.6), 겹치는 것은 "대화를 완료까지
# 진행한다"는 절차뿐이고 검증 항목은 거의 겹치지 않는다. 13 번은 completed 여부만 보고,
# 세션 복원(GET /session)·IN_PROGRESS 상태·embeddingVector 미노출·완료 세션 재사용 방지·
# 빈 메시지/인증 차단은 여기서만 검증한다. 그래서 둘 다 태운다.
# (AI 대화가 두 번 도는 비용이 부담되면 이 줄을 주석 처리하고 13 번만 남기면 된다.)
& "$PSScriptRoot\11_matching_intent.ps1"

Write-Host "`n===== 12) Team Embedding (팀 임베딩 연동) =====" -ForegroundColor Magenta
# 임베딩 갱신은 커밋 후 비동기라 AI 서버가 죽어있어도 팀 CRUD 는 전부 성공해야 한다.
& "$PSScriptRoot\12_team_embedding.ps1"

Write-Host "`n===== 13) Recommendation (유저→팀 추천) =====" -ForegroundColor Magenta
# 의도 추출(선행 조건)까지 이 안에서 수행한다 — 위 11 번을 대신한다.
# 후보 팀은 유저 B 가 만든다(내가 팀장인 팀은 추천에서 제외되므로). B 계정 인자를 넘긴다.
# 12 번 뒤에 두는 이유: 팀 임베딩이 저장돼 있어야 추천 후보가 생긴다.
& "$PSScriptRoot\13_recommendation.ps1" @chatArgs

Write-Host "`n===== 14) Reverse Offer (역제안: 팀→유저) =====" -ForegroundColor Magenta
# 13 번과 반대 방향이다 — A 가 팀장이 되어 B 를 추천받고 제안을 보내면 B 가 수락한다.
# B 의 의도 추출도 이 안에서 수행한다(13 번은 A 만 시켰다). B 계정 인자를 넘긴다.
& "$PSScriptRoot\14_reverse_offer.ps1" @chatArgs
# 14 번은 활성 세션을 A 로 되돌려 놓지만, 토큰 파일 기반이라 슬롯과 어긋날 수 있어 맞춰 준다.
Use-User "A" -Quiet | Out-Null

Write-Host "`n===== 15) Review (협업 온도) =====" -ForegroundColor Magenta
# 유저 3명이 서로 평가해야 하는 유일한 테스트다. 온도는 받은 평가가 2건 이상이어야 공개되므로
# (1건이면 누가 줬는지 자명해 익명성이 깨진다) A/B/C 세 명이 필요하다.
# 슬롯은 위 2) 단계에서 이미 채워졌다. 유저 C 가 없으면 15_review 가 스스로 스킵한다.
& "$PSScriptRoot\15_review.ps1"
# 15_review 가 활성 세션을 A 로 되돌려 놓지만, 이후 스크립트가 추가될 수 있으니 여기서도 보장한다.
Use-User "A" -Quiet | Out-Null

Write-Host "`n===== 16) Recommendation Reason (추천 상세 이유) =====" -ForegroundColor Magenta
# 13/14 번의 추천을 재사용하지 않고 자체적으로 추천 이력을 만든다 — 이유는 (질의, 후보) 쌍의
# 추천 아이템을 근거로 삼는데, 앞 스크립트들이 -Cleanup 으로 팀을 지웠는지에 따라 그 쌍이
# 있을 수도 없을 수도 있어 의존하면 불안정해진다.
& "$PSScriptRoot\16_recommendation_reason.ps1" @chatArgs
Use-User "A" -Quiet | Out-Null

Write-Host "`n===== 17) Proposal Assembly (최종 제안 조립) =====" -ForegroundColor Magenta
# 16 번과 같은 이유로 추천 이력을 자체 생성한다. 조립은 아무것도 저장하지 않지만, 마지막에
# 초안을 기존 /apply 로 실제 발송해 보므로 유저 A 의 지원서가 1건 늘어난다.
& "$PSScriptRoot\17_proposal_assembly.ps1" @chatArgs
Use-User "A" -Quiet | Out-Null

Write-Host "`n===== 전체 테스트 완료 =====" -ForegroundColor Green

# 성공/실패 개수 및 실패 항목 요약 출력 (전체 스크립트 합산 - From 0)
$failedCount = Write-TestSummary -From 0

# 실패가 있으면 0 이 아닌 종료 코드로 종료 (CI 등에서 활용 가능)
exit $failedCount
