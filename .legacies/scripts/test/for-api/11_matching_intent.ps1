# 11_matching_intent.ps1 (for-api-server) - 매칭 의도 추출 API 테스트  /api/matching/intents
#
# ============================================================================
#  [!] 과금 주의 - 이 스크립트는 실제 LLM / 임베딩을 호출합니다.
#      POST /intents/messages 한 번마다 AI 서버가 LLM 추출 + 임베딩 생성을 수행합니다.
#      예상 호출: 최대 8회 (첫 발화 1 + 완료까지 되묻는 루프 최대 6 + 완료 후 새 세션 1)
#      ※ 빈 메시지(11.6)는 백엔드에서 차단되므로 AI 를 호출하지 않습니다.
#
#      과금 없이 돌리려면 백엔드가 로컬 스텁을 보게 하세요:
#        pwsh -File ..\debug\ai-stub\stub-ai-server.ps1     # 포트 8000
#        백엔드 AI_BASE_URL=http://localhost:8000 으로 재기동
# ============================================================================
#
# 사용법: pwsh -File .\11_matching_intent.ps1
# 사전 조건:
#   1) 로그인이 선행되어 .auth-token.txt 가 있어야 한다 (99_run_all 또는 auth\02_auth.ps1).
#   2) 원격 백엔드의 ai.base-url 이 살아있는 FastAPI 를 가리켜야 한다.
#      AI 서버가 죽어있으면 503(AI_SERVER_UNAVAILABLE)이 나며, 그 자체도 유효한 검증이다.
#
# [주의사항]
#   (1) DB 검증 없음. 이 폴더에는 Invoke-PgSql/PgContainer 가 없다(원격 DB 에 docker 가 닿지 않음).
#       user_embeddings 의 벡터 차원이나 슬롯 CSV 는 여기서 확인할 수 없고, slotId 채번 여부로
#       "슬롯이 저장됐다"까지만 간접 확인한다. 벡터까지 보려면 원격 DB 에 직접 접속해야 한다:
#         SELECT vector_dims(embedding) FROM user_embeddings WHERE user_id=<id>;
#   (2) 시나리오가 고정이 아니다. 스텁 AI 를 쓰면 "1번째=재질문, 2번째=완료"가 보장되지만,
#       실서버는 실제 FastAPI 라 몇 번 되물을지 알 수 없다. 그래서 missingFields 를 보고 답을
#       골라 넣으며 완료될 때까지 반복한다.
. "$PSScriptRoot\00_common.ps1"

Write-Host "`n########## 11. Matching Intent (매칭 의도 추출) - /api/matching/intents [인증 필요] ##########" -ForegroundColor Magenta

try {

if (-not (Get-AccessToken)) {
    Write-Host "(!) accessToken 이 없습니다. 먼저 .\auth\02_auth.ps1 (또는 99_run_all) 을 실행하세요." -ForegroundColor Red
    return
}

$userId = Get-JwtSubject -Token (Get-AccessToken)
Write-Host "  (i) userId = $userId" -ForegroundColor DarkGray
Write-Host "  (i) AI 서버는 원격 백엔드의 ai.base-url 설정을 따른다 (테스트 쪽에서 지정하지 않음)" -ForegroundColor DarkGray

# missingFields 이름별 답변. 실제 AI 가 무엇을 되물을지 모르므로 미리 준비해 둔다.
$answers = @{
    "desired_roles"    = "백엔드로 참여하고 싶어."
    "skills"           = "React 와 TypeScript 를 쓸 줄 알아."
    "interests"        = "커머스 쪽에 관심이 있어."
    "activity_goal"    = "포트폴리오용 프로젝트를 만들고 싶어."
    "activity_style"   = "주 2회 오프라인으로 만나고 싶어."
    "experience_level" = "아직 초보야."
}
$fallbackAnswer = "아직 초보지만 백엔드로 참여해서 포트폴리오용 프로젝트를 하고 싶어."

# ── 11.0 초기화 ────────────────────────────────────────────────────────────
# 이전 실행에서 남은 IN_PROGRESS 세션을 버려 깨끗한 상태에서 시작한다.
Invoke-Api -Method POST -Path "/api/matching/intents/session/restart" -Auth `
    -Title "11.0 대화 초기화 (restart)"

# ── 11.1 첫 답변 ───────────────────────────────────────────────────────────
# 일부러 정보가 거의 없는 발화를 보낸다. 첫 턴에 슬롯이 다 채워지면 missingFields
# 재질문 흐름(11.2~11.3)을 못 타므로, AI 가 되물을 수밖에 없게 만든다.
# (그래도 실제 AI 라 completed 여부를 단정하지는 않는다.)
$r1 = Invoke-Api -Method POST -Path "/api/matching/intents/messages" -Auth -PassThru `
    -Title "11.1 첫 답변 전송 (일부러 모호하게)" -Body @{
        message = "같이 프로젝트 할 사람을 찾고 있어요."
    }

# AI 서버가 죽어있으면 여기서 끝난다. 원인을 분명히 알려주고 중단한다.
if (-not $r1.success) {
    Write-Host ""
    Write-Host "  (!) 첫 호출이 실패했습니다: $($r1.message)" -ForegroundColor Red
    Write-Host "      503 이면 원격 백엔드가 AI 서버(FastAPI)에 닿지 못하는 상태입니다." -ForegroundColor Yellow
    Write-Host "      서버의 AI_BASE_URL 설정과 FastAPI 기동 여부를 확인하세요." -ForegroundColor Yellow
    Assert-Test -Title "11.1 첫 답변 전송 성공" -Condition $false -Detail $r1.message
    return
}

$sessionId1 = $r1.data.sessionId
Assert-Test -Title "11.1a sessionId 발급됨" -Condition ([bool]$sessionId1) -Detail "sessionId=$sessionId1"
Assert-Test -Title "11.1b assistantMessage 존재 (프론트가 그대로 출력할 문구)" `
    -Condition ([bool]$r1.data.assistantMessage)
Assert-Test -Title "11.1c embeddingVector 가 응답에 없음 (1536개 float 를 프론트로 보내지 않는다)" `
    -Condition ($null -eq $r1.data.embeddingVector)

# ── 11.2 세션 복원 ─────────────────────────────────────────────────────────
# 아직 진행 중일 때만 의미가 있다 (완료됐다면 IN_PROGRESS 세션이 없다).
if (-not $r1.data.completed) {
    $s = Invoke-Api -Method GET -Path "/api/matching/intents/session" -Auth -PassThru `
        -Title "11.2 세션 복원 (GET /session)"

    $msgs = @($s.data.messages)
    Assert-Test -Title "11.2a 대화 2건 (USER + ASSISTANT)" -Condition ($msgs.Count -eq 2) `
        -Detail "messages=$($msgs.Count)"
    Assert-Test -Title "11.2b 첫 턴이 USER" -Condition ($msgs[0].role -eq "USER")
    Assert-Test -Title "11.2c 둘째 턴이 ASSISTANT (assistant_message 가 대화 이력에 저장됨)" `
        -Condition ($msgs[1].role -eq "ASSISTANT")
    Assert-Test -Title "11.2d status=IN_PROGRESS" -Condition ($s.data.status -eq "IN_PROGRESS")
} else {
    Write-Host "  (i) 첫 발화만으로 완료됨 - 세션 복원 검증은 건너뜁니다." -ForegroundColor DarkGray
}

# ── 11.3 완료될 때까지 답변 ────────────────────────────────────────────────
# AI 가 무엇을 되묻는지(missingFields)에 따라 답을 골라 넣는다.
$last = $r1
$attempt = 0
$maxAttempts = 6   # AI 가 한 번에 한 필드만 물으므로 필드 수(6)면 충분하다.

while (-not $last.data.completed -and $attempt -lt $maxAttempts) {
    $attempt++
    $missing = @($last.data.missingFields)
    $field   = if ($missing.Count -gt 0) { $missing[0] } else { $null }
    $answer  = if ($field -and $answers.ContainsKey($field)) { $answers[$field] } else { $fallbackAnswer }

    Write-Host ""
    Write-Host ("  (i) 라운드 {0}: AI 가 묻는 항목 = [{1}]" -f $attempt, ($missing -join ", ")) -ForegroundColor DarkGray

    $last = Invoke-Api -Method POST -Path "/api/matching/intents/messages" -Auth -PassThru `
        -Title ("11.3 답변 전송 (라운드 {0}, 항목={1})" -f $attempt, $field) -Body @{ message = $answer }

    if (-not $last.success) {
        Assert-Test -Title "11.3 답변 전송 성공" -Condition $false -Detail $last.message
        return
    }

    Assert-Test -Title ("11.3-{0} 같은 세션에 이어짐" -f $attempt) `
        -Condition ($last.data.sessionId -eq $sessionId1) -Detail "sessionId=$($last.data.sessionId)"
}

Assert-Test -Title "11.3a 추출 완료 (completed=true)" -Condition ($last.data.completed -eq $true) `
    -Detail ("라운드 {0}회 만에 완료" -f $attempt)
Assert-Test -Title "11.3b missingFields 비어있음" -Condition (@($last.data.missingFields).Count -eq 0)

$slotId = $last.data.slotId
# slotId 채번 = 슬롯이 DB 에 저장됐다는 뜻. (DB 직접 확인은 이 폴더에서 불가 — 상단 주석 참고)
Assert-Test -Title "11.3c slotId 채번됨 (슬롯 저장 확인)" -Condition ([bool]$slotId) -Detail "slotId=$slotId"

$ex = $last.data.extracted
Assert-Test -Title "11.3d extracted 가 camelCase 로 채워짐" `
    -Condition ([bool]$ex.desiredRoles -and [bool]$ex.experienceLevel) `
    -Detail ("desiredRoles=[{0}], experienceLevel={1}" -f (@($ex.desiredRoles) -join ","), $ex.experienceLevel)

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
if ($r3.success) {
    Assert-Test -Title "11.5a 새 sessionId 발급 (완료 세션에 이어붙이지 않음)" `
        -Condition ($r3.data.sessionId -ne $sessionId1) `
        -Detail "이전=$sessionId1, 새로=$($r3.data.sessionId)"
}

# 뒷정리: 11.5 가 만든 세션을 남기지 않는다.
Invoke-Api -Method POST -Path "/api/matching/intents/session/restart" -Auth `
    -Title "11.5b 뒷정리 (restart)"

# ── 11.6 검증 (빈 메시지) ──────────────────────────────────────────────────
Invoke-Api -Method POST -Path "/api/matching/intents/messages" -Auth `
    -Title "11.6 빈 메시지 (@NotBlank → 차단 기대)" -Body @{ message = "" }

# ── 11.7 인증 없이 호출 ────────────────────────────────────────────────────
Invoke-Api -Method GET -Path "/api/matching/intents/session" `
    -Title "11.7 인증 없이 세션 조회 (차단 기대)"

Write-Host ""
Write-Host "  (i) 임베딩 벡터(user_embeddings)는 이 폴더에서 확인할 수 없습니다. 원격 DB 에서:" -ForegroundColor DarkGray
Write-Host "      SELECT vector_dims(embedding) FROM user_embeddings WHERE user_id=$userId;  -- 1536 기대" -ForegroundColor DarkGray

Write-Host "`n########## 11. Matching Intent 테스트 완료 ##########" -ForegroundColor Magenta

} finally {
    Write-TestSummary | Out-Null
}
