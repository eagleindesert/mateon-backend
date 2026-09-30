# 16_recommendation_reason.ps1 (for-api-server) - 추천 상세 이유 (lazy)
#   POST /api/matching/recommendations/reason/user-to-team
#   POST /api/matching/recommendations/reason/team-to-user
#
# ============================================================================
#  [!] 과금 주의 - 이 스크립트는 실제 LLM / 임베딩을 호출합니다.
#      예상 호출 8회:
#        - 팀 생성 1회   -> 비동기 임베딩 갱신 (LLM 추출 + 임베딩)
#        - 의도 추출 4회 -> A/B 각 2턴 (LLM + 임베딩)
#        - 추천 점수화 2회 -> 유저→팀 1회, 팀→유저 1회
#        - 이유 생성 2회 -> 방향별 1회씩 (LLM)
#      ※ 캐시 hit(16.4)·404(16.6)·403(16.8)은 AI 를 호출하지 않습니다 — 그게 검증 대상입니다.
#
#      과금 없이 돌리려면 백엔드가 로컬 스텁을 보게 하세요:
#        pwsh -File ..\debug\ai-stub\stub-ai-server.ps1     # 포트 8000
#        백엔드 AI_BASE_URL=http://localhost:8000 으로 재기동
# ============================================================================
#
# 사용법: pwsh -File .\16_recommendation_reason.ps1            # 기본: 만든 팀을 남긴다
#        pwsh -File .\16_recommendation_reason.ps1 -Cleanup    # B 가 만든 팀까지 삭제
# 사전 조건:
#   1) 로그인이 선행되어 .auth-token.txt 가 있어야 한다 (99_run_all 또는 auth\02_auth.ps1).
#   2) 유저 B 계정이 있어야 한다 (02_auth 가 함께 생성).
#      A = 추천받는 유저(유저→팀), B = 팀장(팀→유저 역방향의 요청자).
#   3) A 와 B 모두 학교 인증이 되어 있어야 한다 (팀 생성의 전제 조건).
#   4) 백엔드의 ai.base-url 이 살아있는 AI 서버(또는 스텁)를 가리켜야 한다.
#
# [검증 범위]
#   - 추천 목록에 뜬 팀의 이유를 생성할 수 있는지 (reason 비어있지 않음)
#   - 같은 쌍을 다시 물으면 처음 문장이 그대로 오는지 (캐시 hit — AI 재호출 없음)
#   - 역방향(팀→유저)도 같은 규약으로 동작하는지
#   - 추천에 뜬 적 없는 팀은 404 인지 (400 이 아니다 — 실제로 없는 것이다)
#   - 팀장이 아니면 역방향 이유를 못 보는지 (403)
#   - teamId 누락이 400 으로 걸리는지
#
# [!] 캐시 hit 검증의 원리
#     스텁은 이유 문장에 호출 일련번호 [stub#N] 을 박아 준다. 같은 쌍을 두 번 물었는데 N 이
#     같으면 두 번째는 AI 를 부르지 않은 것이다. 실서버로 돌리면 번호가 없으므로 이 검증은
#     "두 응답이 완전히 동일한 문자열인가"로 떨어진다 — 실서버 LLM 은 매번 다른 문장을 만들기
#     때문에 그것만으로도 캐시 여부가 갈린다.
param(
    [switch]$Cleanup,
    [string]$UserBEmail,       # 미지정 시 00_common 의 UserBEmail
    [string]$UserBPassword     # 미지정 시 00_common 의 UserBPassword
)
. "$PSScriptRoot\00_common.ps1"

if (-not $UserBEmail)    { $UserBEmail    = $script:UserBEmail }
if (-not $UserBPassword) { $UserBPassword = $script:UserBPassword }

Write-Host "`n########## 16. Recommendation Reason (추천 상세 이유) [인증 필요] ##########" -ForegroundColor Magenta

try {

$tokenA = Get-AccessToken
if (-not $tokenA) {
    Write-Host "(!) accessToken 이 없습니다. 먼저 .\auth\02_auth.ps1 (또는 99_run_all) 을 실행하세요." -ForegroundColor Red
    return
}
$userIdA = Get-JwtSubject -Token $tokenA

function Use-Token { param([string]$Token) Save-AccessToken $Token }

# ============================================================================
#  0) 유저 B 준비 — 후보 팀의 팀장 (내가 팀장인 팀은 내 추천에서 빠지므로 B 가 만든다)
# ============================================================================
$loginB = Invoke-Api -Method POST -Path "/api/auth/login" -PassThru -Title "16.0 유저 B 로그인 (후보 팀 팀장)" -Body @{
    email = $UserBEmail; password = $UserBPassword
}
$tokenB = $loginB.data.accessToken
if (-not $tokenB) {
    Write-Host "(!) 유저 B 로그인 실패 - 먼저 .\auth\02_auth.ps1 로 유저 B 계정을 생성하세요." -ForegroundColor Red
    Use-Token $tokenA
    return
}
$userIdB = Get-JwtSubject -Token $tokenB
if ($userIdA -eq $userIdB) {
    Write-Host "(!) A 와 B 가 동일 계정입니다. -UserBEmail 을 A 와 다른 이메일로 지정하세요." -ForegroundColor Red
    Use-Token $tokenA
    return
}
Write-Host "  (i) userIdA=$userIdA (추천 받는 쪽), userIdB=$userIdB (팀장)" -ForegroundColor Green

# ============================================================================
#  1) B 가 후보 팀 생성 — 스텁 의도 추출이 desired_roles=["BE"] 를 주므로 BE 팀을 만든다
# ============================================================================
Use-Token $tokenB

$team = Invoke-Api -Method POST -Path "/api/teams" -Auth -PassThru -Title "16.1 (B) BE 모집 팀 생성" -Body @{
    eventId              = $null
    title                = "이유테스트 BE팀 $((Get-Random -Maximum 9999))"
    promotionText        = "커머스 서비스를 만드는 팀입니다. 주 2회 오프라인으로 모이고 초보자도 환영합니다."
    role                 = @("BE")
    requiredSkills       = @("Spring Boot", "PostgreSQL")
    characteristic       = "초보 환영"
    capacity             = 4
    recruitmentStartDate = (Get-Date).ToString("yyyy-MM-dd")
    recruitmentEndDate   = (Get-Date).AddDays(30).ToString("yyyy-MM-dd")
}
$teamId = $team.data.id
if (-not $teamId) {
    Assert-Test -Title "16.1a 후보 팀 생성 성공" -Condition $false -Detail "teamId 없음" | Out-Null
    Use-Token $tokenA
    return
}
Write-Host "  (i) teamId=$teamId" -ForegroundColor Green

# 팀 임베딩은 커밋 후 비동기로 저장된다. 이유의 target_summary 가 여기서 나오므로 기다린다.
Write-Host "  (i) 비동기 팀 임베딩 저장 대기 (4초)..." -ForegroundColor DarkGray
Start-Sleep -Seconds 4

# B 도 의도 추출을 해 둔다 — 역방향(팀→유저) 후보가 되려면 슬롯이 있어야 한다.
Invoke-Api -Method POST -Path "/api/matching/intents/session/restart" -Auth -Title "16.1b (B) 의도 세션 초기화" | Out-Null
Invoke-Api -Method POST -Path "/api/matching/intents/messages" -Auth -Title "16.1c (B) 의도 추출 1턴" -Body @{
    message = "백엔드 쪽으로 사이드 프로젝트를 하고 싶어요."
} | Out-Null
Invoke-Api -Method POST -Path "/api/matching/intents/messages" -Auth -Title "16.1d (B) 의도 추출 2턴 (완료 기대)" -Body @{
    message = "입문 수준이고 주 2회 오프라인이면 좋겠어요."
} | Out-Null

# ============================================================================
#  2) A 의 의도 추출 완료 → 유저→팀 추천 (이유의 근거가 될 추천 이력을 만든다)
# ============================================================================
Use-Token $tokenA

Invoke-Api -Method POST -Path "/api/matching/intents/session/restart" -Auth -Title "16.2 (A) 의도 세션 초기화" | Out-Null
Invoke-Api -Method POST -Path "/api/matching/intents/messages" -Auth -Title "16.2a (A) 의도 추출 1턴" -Body @{
    message = "백엔드 공부하려고 포트폴리오용 프로젝트 팀을 찾고 있어요."
} | Out-Null
$intent = Invoke-Api -Method POST -Path "/api/matching/intents/messages" -Auth -PassThru -Title "16.2b (A) 의도 추출 2턴 (완료 기대)" -Body @{
    message = "아직 입문 수준이고, 주 2회 정도 오프라인으로 만나고 싶어요."
}
if (-not $intent.data.completed) {
    Write-Host "(!) 의도 추출이 완료되지 않아 이유 생성을 검증할 수 없습니다. AI 서버(스텁) 상태를 확인하세요." -ForegroundColor Red
    return
}

# limit 을 넉넉히 준다 — 테스트 데이터가 쌓일수록 방금 만든 팀이 상위 5건 밖으로 밀린다.
$rec = Invoke-Api -Method GET -Path "/api/matching/recommendations/user-to-team?limit=200" -Auth -PassThru `
    -Title "16.2c (A) 팀 추천 요청 (이유의 근거가 될 추천 이력 생성)"
$recItems = @($rec.data)

# 이유는 "추천에 뜬 팀"에 대해서만 만들 수 있다. 방금 만든 팀이 응답에 없으면(AI 컷오프에
# 걸렸으면) 아무 추천 팀이나 하나 잡아서 검증한다 — 검증 대상은 이유 생성이지 순위가 아니다.
$targetTeamId = @($recItems | Where-Object { $_.teamId -eq $teamId } | Select-Object -First 1).teamId
if (-not $targetTeamId) {
    $targetTeamId = @($recItems)[0].teamId
    Write-Host "  (i) 방금 만든 팀이 추천 응답에 없어 상위 팀(teamId=$targetTeamId)으로 검증합니다." -ForegroundColor Yellow
}

if (-not $targetTeamId) {
    Write-Host "(!) 추천 결과가 0건이라 이유를 검증할 수 없습니다 (team_embeddings 확인)." -ForegroundColor Red
    Use-Token $tokenA
    return
}

# ============================================================================
#  3) 유저→팀 상세 이유 — 본 검증
# ============================================================================
$reason1 = Invoke-Api -Method POST -Path "/api/matching/recommendations/reason/user-to-team" -Auth -PassThru `
    -Title "16.3 (A) 추천받은 팀의 상세 이유 생성" -Body @{ teamId = $targetTeamId }

$reasonText1 = $reason1.data.reason
Assert-Test -Title "16.3a reason 이 비어있지 않음" `
    -Condition ([bool](-not [string]::IsNullOrWhiteSpace($reasonText1))) `
    -Detail ("reason='{0}'" -f $reasonText1) | Out-Null

# 요약이 비어 나갔는지 밖에서 확인하는 유일한 방법. 스텁은 받은 요약을 문장에 그대로 섞어
# 주므로 "후보()"처럼 괄호가 비어 있으면 백엔드가 조립에 실패한 것이다.
# (실서버로 돌리면 이 검증은 의미가 없으니 경고만 남긴다.)
if ($reasonText1 -match "\[stub#\d+\]") {
    Assert-Test -Title "16.3b 스텁에 요약이 채워져 전달됨 (빈 괄호 없음)" `
        -Condition ([bool]($reasonText1 -notmatch "후보\(\)" -and $reasonText1 -notmatch "대상\(\)")) `
        -Detail "candidate_summary/target_summary 조립 확인" | Out-Null
} else {
    Write-Host "  (i) 실서버 응답으로 보입니다 - 요약 전달 여부는 AI 서버 로그로 확인하세요." -ForegroundColor DarkGray
}

# ============================================================================
#  4) 캐시 — 같은 쌍을 다시 물으면 AI 를 부르지 않고 처음 문장을 그대로 준다
# ============================================================================
$reason2 = Invoke-Api -Method POST -Path "/api/matching/recommendations/reason/user-to-team" -Auth -PassThru `
    -Title "16.4 (A) 같은 팀의 이유 재요청 (캐시 hit 기대)" -Body @{ teamId = $targetTeamId }

Assert-Test -Title "16.4a 재요청 시 같은 문장 (AI 재호출 없음)" `
    -Condition ([bool]($reason2.data.reason -eq $reasonText1)) `
    -Detail ("스텁이면 [stub#N] 의 N 이 같아야 한다. 1차='{0}' / 2차='{1}'" -f $reasonText1, $reason2.data.reason) | Out-Null

# ============================================================================
#  5) 추천에 뜬 적 없는 팀 → 404 (400 이 아니다 — "준비가 안 됐다"가 아니라 실제로 없다)
# ============================================================================
Invoke-Api -Method POST -Path "/api/matching/recommendations/reason/user-to-team" -Auth `
    -Title "16.5 (A) 추천 이력 없는 teamId 로 이유 요청 - 차단 기대 (404 RECOMMENDATION_NOT_FOUND)" `
    -Body @{ teamId = 999999999 } | Out-Null

# teamId 누락은 검증 단계에서 400 으로 걸린다 (AI 까지 가지 않는다).
Invoke-Api -Method POST -Path "/api/matching/recommendations/reason/user-to-team" -Auth `
    -Title "16.5b (A) teamId 누락 - 차단 기대 (400)" -Body @{ } | Out-Null

# ============================================================================
#  6) 역방향 (팀→유저) — B 가 팀장으로서 추천받은 유저의 이유를 본다
# ============================================================================
Use-Token $tokenB

$recUsers = Invoke-Api -Method GET -Path "/api/matching/recommendations/team-to-user?teamId=$teamId&limit=200" -Auth -PassThru `
    -Title "16.6 (B) 역제안 추천 요청 (역방향 이유의 근거 생성)"
$targetUserId = @($recUsers.data)[0].userId

if ($targetUserId) {
    $reasonU1 = Invoke-Api -Method POST -Path "/api/matching/recommendations/reason/team-to-user" -Auth -PassThru `
        -Title "16.6a (B) 추천받은 유저의 상세 이유 생성" -Body @{ teamId = $teamId; userId = $targetUserId }

    Assert-Test -Title "16.6b 역방향 reason 이 비어있지 않음" `
        -Condition ([bool](-not [string]::IsNullOrWhiteSpace($reasonU1.data.reason))) `
        -Detail ("reason='{0}'" -f $reasonU1.data.reason) | Out-Null

    $reasonU2 = Invoke-Api -Method POST -Path "/api/matching/recommendations/reason/team-to-user" -Auth -PassThru `
        -Title "16.6c (B) 같은 유저의 이유 재요청 (캐시 hit 기대)" -Body @{ teamId = $teamId; userId = $targetUserId }

    Assert-Test -Title "16.6d 역방향도 재요청 시 같은 문장" `
        -Condition ([bool]($reasonU2.data.reason -eq $reasonU1.data.reason)) `
        -Detail "방향과 무관하게 같은 캐시 규약" | Out-Null

    # ========================================================================
    #  7) 팀장이 아니면 역방향 이유를 볼 수 없다 (A 는 이 팀의 팀장이 아니다)
    # ========================================================================
    Use-Token $tokenA
    Invoke-Api -Method POST -Path "/api/matching/recommendations/reason/team-to-user" -Auth `
        -Title "16.7 (A) 남의 팀 역방향 이유 요청 - 차단 기대 (403)" `
        -Body @{ teamId = $teamId; userId = $targetUserId } | Out-Null
} else {
    Write-Host "  (i) 역제안 후보가 0건이라 역방향 검증을 건너뜁니다 (의도 추출한 유저가 부족)." -ForegroundColor Yellow
}

# ============================================================================
#  정리
# ============================================================================
Use-Token $tokenA

if ($Cleanup) {
    Use-Token $tokenB
    Invoke-Api -Method DELETE -Path "/api/teams/$teamId" -Auth -Title "16.9 (B) 테스트 팀 삭제" -NoTrack | Out-Null
    Use-Token $tokenA
    Write-Host "  (i) 정리 완료. 추천/이유 로그는 남습니다 (시점 기록이라 팀이 지워져도 보존)." -ForegroundColor DarkGray
} else {
    Write-Host "  (i) 만든 팀(teamId=$teamId)을 남겨 둡니다. 지우려면 -Cleanup 을 주세요." -ForegroundColor DarkGray
}

Write-Host ""
Write-Host "  [DB 확인] 이유가 캐시됐는지:" -ForegroundColor DarkGray
Write-Host "    SELECT id, team_id, rank_no, score, label, reason" -ForegroundColor DarkGray
Write-Host "      FROM user_to_team_recommendation_items ORDER BY id DESC LIMIT 5;" -ForegroundColor DarkGray
Write-Host "  [폴백 확인] embedding_text 없이도 요약이 조립되는지:" -ForegroundColor DarkGray
Write-Host "    UPDATE team_embeddings SET embedding_text = NULL WHERE team_id = $teamId;" -ForegroundColor DarkGray
Write-Host "    UPDATE user_to_team_recommendation_items SET reason = NULL WHERE team_id = $teamId;" -ForegroundColor DarkGray
Write-Host "    -- 캐시를 함께 지워야 AI 를 다시 부른다. 이후 재실행하면 스텁 콘솔의" -ForegroundColor DarkGray
Write-Host "    -- target_summary 가 '모집 역할: BE / 요구 스킬: ...' 형태(2층)로 나와야 한다." -ForegroundColor DarkGray

Write-Host "`n########## 16. Recommendation Reason 테스트 완료 ##########" -ForegroundColor Magenta

} finally {
    Write-TestSummary | Out-Null
}
