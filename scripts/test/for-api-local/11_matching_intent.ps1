# 11_matching_intent.ps1 - 매칭 의도 추출 API 테스트  /api/matching/intents  [인증 필요]
# 사용법: powershell -ExecutionPolicy Bypass -File .\11_matching_intent.ps1
# 사전 조건:
#   1) 02_auth.ps1 로 로그인하여 .auth-token.txt 가 생성되어 있어야 한다.
#   2) AI 서버가 떠 있어야 한다. 실제 FastAPI 또는 스텁:
#        pwsh -File ..\debug\ai-stub\stub-ai-server.ps1 -Port 8001
#      백엔드의 AI_BASE_URL 과 포트를 맞출 것.
#
# 흐름: restart(초기화) -> 답변1(재질문) -> 세션 복원 -> 답변2(완료) -> 슬롯/임베딩 DB 검증
#
# 주의: AI 서버가 없으면 11.1 부터 503(AI_SERVER_UNAVAILABLE)이 난다. 그것 자체도
#       의미 있는 검증이라 -SkipIfNoAi 없이 그대로 돌린다.
. "$PSScriptRoot\00_common.ps1"

Write-Host "`n########## 11. Matching Intent (매칭 의도 추출) - /api/matching/intents [인증 필요] ##########" -ForegroundColor Magenta

if (-not (Get-AccessToken)) {
    Write-Host "(!) accessToken 이 없습니다. 먼저 .\02_auth.ps1 을 실행하세요." -ForegroundColor Red
    return
}

$userId = Get-JwtSubject -Token (Get-AccessToken)
Write-Host "  (i) userId = $userId" -ForegroundColor DarkGray
Write-Host "  (i) AI 서버 = $script:AiBaseUrl (백엔드의 ai.base-url 과 일치해야 함)" -ForegroundColor DarkGray

# ── 11.0 초기화 ────────────────────────────────────────────────────────────
# 이전 실행에서 남은 IN_PROGRESS 세션을 버려 깨끗한 상태에서 시작한다.
Invoke-Api -Method POST -Path "/api/matching/intents/session/restart" -Auth `
    -Title "11.0 대화 초기화 (restart)"

# ── 11.1 첫 답변 → 재질문 기대 ─────────────────────────────────────────────
$r1 = Invoke-Api -Method POST -Path "/api/matching/intents/messages" -Auth -PassThru `
    -Title "11.1 첫 답변 전송 (재질문 기대)" -Body @{
        message = "백엔드 경험은 없지만 프론트엔드를 1년 해봤고, 이번엔 풀스택 프로젝트에서 성장하고 싶습니다. 포트폴리오용 프로젝트를 찾고 있습니다."
    }

$sessionId1 = $r1.data.sessionId
Assert-Test -Title "11.1a sessionId 발급됨" -Condition ([bool]$sessionId1) -Detail "sessionId=$sessionId1"
Assert-Test -Title "11.1b completed=false" -Condition ($r1.data.completed -eq $false)
Assert-Test -Title "11.1c missingFields 가 비어있지 않음" `
    -Condition (@($r1.data.missingFields).Count -gt 0) `
    -Detail ("missingFields=[{0}]" -f (@($r1.data.missingFields) -join ", "))
Assert-Test -Title "11.1d assistantMessage 존재 (프론트가 그대로 출력할 문구)" `
    -Condition ([bool]$r1.data.assistantMessage)
Assert-Test -Title "11.1e slotId=null (미완료라 슬롯 없음)" -Condition ($null -eq $r1.data.slotId)

# ── 11.2 세션 복원 ─────────────────────────────────────────────────────────
# AI 재호출 없이 DB 에 저장해 둔 마지막 결과로 복원되어야 한다.
$s = Invoke-Api -Method GET -Path "/api/matching/intents/session" -Auth -PassThru `
    -Title "11.2 세션 복원 (GET /session)"

$msgCount = @($s.data.messages).Count
Assert-Test -Title "11.2a 대화 2건 (USER + ASSISTANT)" -Condition ($msgCount -eq 2) `
    -Detail "messages=$msgCount"
Assert-Test -Title "11.2b 첫 턴이 USER" -Condition (@($s.data.messages)[0].role -eq "USER")
Assert-Test -Title "11.2c 둘째 턴이 ASSISTANT (assistant_message 가 대화 이력에 저장됨)" `
    -Condition (@($s.data.messages)[1].role -eq "ASSISTANT")
Assert-Test -Title "11.2d status=IN_PROGRESS" -Condition ($s.data.status -eq "IN_PROGRESS")

# ── 11.3 둘째 답변 → 완료 기대 ─────────────────────────────────────────────
$r2 = Invoke-Api -Method POST -Path "/api/matching/intents/messages" -Auth -PassThru `
    -Title "11.3 둘째 답변 전송 (완료 기대)" -Body @{
        message = "백엔드로 참여하고 싶고 아직 초보야. 커머스 쪽에 관심 있어."
    }

Assert-Test -Title "11.3a 같은 세션에 이어짐" -Condition ($r2.data.sessionId -eq $sessionId1) `
    -Detail "sessionId=$($r2.data.sessionId)"
Assert-Test -Title "11.3b completed=true" -Condition ($r2.data.completed -eq $true)
Assert-Test -Title "11.3c missingFields 비어있음" -Condition (@($r2.data.missingFields).Count -eq 0)
$slotId = $r2.data.slotId
Assert-Test -Title "11.3d slotId 채번됨" -Condition ([bool]$slotId) -Detail "slotId=$slotId"
Assert-Test -Title "11.3e embeddingVector 가 응답에 없음 (1536개 float 를 프론트로 보내지 않는다)" `
    -Condition ($null -eq $r2.data.embeddingVector)

# ── 11.4 완료 후 세션 상태 ─────────────────────────────────────────────────
# COMPLETED 세션은 IN_PROGRESS 조회에 걸리지 않으므로 data 가 null 이어야 한다.
$s2 = Invoke-Api -Method GET -Path "/api/matching/intents/session" -Auth -PassThru `
    -Title "11.4 완료 후 세션 조회 (진행 중 세션 없음 기대)"
Assert-Test -Title "11.4a 진행 중 세션 없음 → data=null (404 가 아니라 정상 200)" `
    -Condition ($null -eq $s2.data)

# ── 11.5 완료 세션 재사용 안 함 ────────────────────────────────────────────
$r3 = Invoke-Api -Method POST -Path "/api/matching/intents/messages" -Auth -PassThru `
    -Title "11.5 완료 후 새 메시지 (새 세션 시작 기대)" -Body @{
        message = "다시 처음부터 할래. 기획으로 참여하고 싶어."
    }
Assert-Test -Title "11.5a 새 sessionId 발급 (완료 세션에 이어붙이지 않음)" `
    -Condition ($r3.data.sessionId -ne $sessionId1) `
    -Detail "이전=$sessionId1, 새로=$($r3.data.sessionId)"

# ── 11.6 검증 (빈 메시지) ──────────────────────────────────────────────────
Invoke-Api -Method POST -Path "/api/matching/intents/messages" -Auth `
    -Title "11.6 빈 메시지 (@NotBlank → 차단 기대)" -Body @{ message = "" }

# ── 11.7 인증 없이 호출 ────────────────────────────────────────────────────
Invoke-Api -Method GET -Path "/api/matching/intents/session" `
    -Title "11.7 인증 없이 세션 조회 (차단 기대)"

# ── 11.8 DB 검증 ───────────────────────────────────────────────────────────
Write-Host "`n[11.8 DB 검증] user_embeddings / matching_intent_slots / 세션 제약" -ForegroundColor Cyan

if (-not $userId) {
    Write-Host "  (!) userId 를 JWT 에서 얻지 못해 DB 검증을 건너뜁니다." -ForegroundColor Yellow
} else {
    # 이번 작업이 user_embeddings 를 처음 채우는 지점이므로 반드시 확인한다.
    $dims = (docker exec $script:PgContainer psql -U $script:PgUser -d $script:PgDatabase -t -A `
        -c "SELECT vector_dims(embedding) FROM user_embeddings WHERE user_id=$userId;" | Out-String).Trim()
    Assert-Test -Title "11.8a user_embeddings 에 1536 차원 벡터 적재됨" `
        -Condition ($dims -eq "1536") -Detail "vector_dims=$dims"

    $slot = (docker exec $script:PgContainer psql -U $script:PgUser -d $script:PgDatabase -t -A `
        -c "SELECT desired_roles || '|' || COALESCE(skills,'') || '|' || COALESCE(experience_level,'') FROM matching_intent_slots WHERE user_id=$userId;" | Out-String).Trim()
    Assert-Test -Title "11.8b matching_intent_slots 에 CSV 로 저장됨" `
        -Condition ([bool]$slot) -Detail "desired_roles|skills|experience_level = $slot"

    # 사용자당 슬롯 1건 (uk_matching_intent_slots_user)
    $slotCount = (docker exec $script:PgContainer psql -U $script:PgUser -d $script:PgDatabase -t -A `
        -c "SELECT count(*) FROM matching_intent_slots WHERE user_id=$userId;" | Out-String).Trim()
    Assert-Test -Title "11.8c 슬롯은 사용자당 1건 (upsert)" -Condition ($slotCount -eq "1") `
        -Detail "count=$slotCount"

    # 사용자당 IN_PROGRESS 세션은 최대 1개 (uk_matching_intent_sessions_active)
    $activeCount = (docker exec $script:PgContainer psql -U $script:PgUser -d $script:PgDatabase -t -A `
        -c "SELECT count(*) FROM matching_intent_sessions WHERE user_id=$userId AND status='IN_PROGRESS';" | Out-String).Trim()
    Assert-Test -Title "11.8d IN_PROGRESS 세션 1개 이하 (부분 유니크 인덱스)" `
        -Condition ([int]$activeCount -le 1) -Detail "count=$activeCount"
}

Write-Host "`n########## 11. Matching Intent 테스트 완료 ##########" -ForegroundColor Magenta
