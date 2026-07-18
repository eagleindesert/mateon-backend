# 13_recommendation.ps1 (for-api-server) - 유저→팀 추천  GET /api/matching/recommendations/user-to-team
# 사용법: pwsh -File .\13_recommendation.ps1            # 기본: 만든 팀을 남긴다
#        pwsh -File .\13_recommendation.ps1 -Cleanup    # B 가 만든 팀까지 삭제
# 사전 조건:
#   1) 로그인이 선행되어 .auth-token.txt 가 있어야 한다 (99_run_all 또는 auth\02_auth.ps1).
#   2) 유저 B 계정이 있어야 한다 (02_auth 가 함께 생성). 후보 팀은 B 가 만든다 —
#      내가 팀장인 팀은 추천에서 제외되므로 A 가 만든 팀은 후보가 될 수 없다.
#   3) 백엔드의 ai.base-url 이 살아있는 AI 서버(또는 스텁)를 가리켜야 한다.
#      이 API 는 의도 추출과 달리 AI 호출이 동기다 — AI 가 죽어있으면 503/502 가 난다.
#
# [검증 범위]
#   - 의도 추출이 끝난 사용자만 추천을 받을 수 있는지 (미완료 → 400 MATCHING_INTENT_REQUIRED)
#   - 점수 내림차순으로 정렬되는지 (스텁은 일부러 오름차순으로 돌려준다)
#   - 역할이 맞는 팀이 위로 오는지 + label 이 그대로 노출되는지
#   - 내가 팀장인 팀이 후보에서 빠지는지
#   - limit 이 적용되는지
#   - 후보가 없는 조건(없는 eventId)에서 빈 배열이 오는지 (404 아님)
#   - 추천 결과가 로그 테이블에 쌓이는지 (아래 SQL 로 확인)
param(
    [switch]$Cleanup,
    [string]$UserBEmail,       # 미지정 시 00_common 의 UserBEmail
    [string]$UserBPassword     # 미지정 시 00_common 의 UserBPassword
)
. "$PSScriptRoot\00_common.ps1"

if (-not $UserBEmail)    { $UserBEmail    = $script:UserBEmail }
if (-not $UserBPassword) { $UserBPassword = $script:UserBPassword }

Write-Host "`n########## 13. Recommendation (유저→팀 추천) - /api/matching/recommendations [인증 필요] ##########" -ForegroundColor Magenta

$tokenA = Get-AccessToken
if (-not $tokenA) {
    Write-Host "(!) accessToken 이 없습니다. 먼저 .\auth\02_auth.ps1 (또는 99_run_all) 을 실행하세요." -ForegroundColor Red
    return
}
$userIdA = Get-JwtSubject -Token $tokenA

# 저장된 토큰을 갈아끼우는 헬퍼 (10_chat 과 같은 방식)
function Use-Token { param([string]$Token) Save-AccessToken $Token }

# ============================================================================
#  0) 유저 B 준비 — 후보 팀의 팀장이 될 사람
# ============================================================================
$loginB = Invoke-Api -Method POST -Path "/api/auth/login" -PassThru -Title "13.0 유저 B 로그인 (후보 팀 팀장)" -Body @{
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
Write-Host "  (i) userIdA=$userIdA (추천 받는 쪽), userIdB=$userIdB (팀 만드는 쪽)" -ForegroundColor Green

# ============================================================================
#  1) B 가 후보 팀 2개 생성 — 역할이 맞는 팀 / 안 맞는 팀
#     스텁 의도 추출은 desired_roles=["BE"] 를 돌려주므로 BE 팀이 위로 와야 한다.
# ============================================================================
Use-Token $tokenB

# 활동 연결 팀을 만들기 위해 기존 활동 id 를 하나 가져온다 (없으면 자율 프로젝트로 폴백).
$events = Invoke-Api -Method GET -Path "/api/events" -PassThru -Title "13.0b 활동 목록 조회 (연결용 eventId 확보)"
$linkedEventId = @($events.data)[0].id
if ($linkedEventId) {
    Write-Host "  (i) BE팀을 eventId=$linkedEventId 에 연결합니다." -ForegroundColor Green
} else {
    Write-Host "  (i) 활동이 없어 BE팀도 자율 프로젝트로 만듭니다 (활동 제목 검증은 건너뜀)." -ForegroundColor Yellow
}

$teamBe = Invoke-Api -Method POST -Path "/api/teams" -Auth -PassThru -Title "13.1 (B) BE 모집 팀 생성 - 역할 일치 기대" -Body @{
    eventId              = $linkedEventId
    title                = "추천테스트 BE팀 $((Get-Random -Maximum 9999))"
    promotionText        = "커머스 서비스를 만드는 팀입니다. 주 2회 오프라인으로 모이고 초보자도 환영합니다."
    role                 = @("BE")
    requiredSkills       = @("Spring Boot", "PostgreSQL")
    characteristic       = "초보 환영"
    capacity             = 4
    recruitmentStartDate = (Get-Date).ToString("yyyy-MM-dd")
    recruitmentEndDate   = (Get-Date).AddDays(30).ToString("yyyy-MM-dd")
}
$teamBeId = $teamBe.data.id

$teamFe = Invoke-Api -Method POST -Path "/api/teams" -Auth -PassThru -Title "13.2 (B) FE 모집 팀 생성 - 역할 불일치 기대" -Body @{
    eventId              = $null
    title                = "추천테스트 FE팀 $((Get-Random -Maximum 9999))"
    promotionText        = "디자인 시스템을 다듬는 팀입니다. 온라인 위주로 모입니다."
    role                 = @("FE")
    requiredSkills       = @("React")
    characteristic       = "차분한 팀"
    capacity             = 3
    recruitmentStartDate = (Get-Date).ToString("yyyy-MM-dd")
    recruitmentEndDate   = (Get-Date).AddDays(30).ToString("yyyy-MM-dd")
}
$teamFeId = $teamFe.data.id

if (-not $teamBeId -or -not $teamFeId) {
    Assert-Test -Title "13.2a 후보 팀 2개 생성 성공" -Condition $false -Detail "teamBeId=$teamBeId, teamFeId=$teamFeId" | Out-Null
    Use-Token $tokenA
    return
}
Write-Host "  (i) teamBeId=$teamBeId, teamFeId=$teamFeId" -ForegroundColor Green

# 팀 임베딩은 커밋 후 비동기로 저장된다. 임베딩이 없는 팀은 추천 후보가 될 수 없으므로 기다린다.
Write-Host "  (i) 비동기 팀 임베딩 저장 대기 (4초)..." -ForegroundColor DarkGray
Start-Sleep -Seconds 4

# ============================================================================
#  2) 의도 추출이 안 된 사용자는 추천을 받을 수 없다 (B 는 의도 추출을 한 적이 없다)
# ============================================================================
Invoke-Api -Method GET -Path "/api/matching/recommendations/user-to-team" -Auth `
    -Title "13.3 (B) 의도 추출 미완료 상태로 추천 요청 - 차단 기대 (400 MATCHING_INTENT_REQUIRED)" | Out-Null

# ============================================================================
#  3) A 로 돌아와 의도 추출을 완료시킨다 (슬롯 + user_embeddings 생성)
#     스텁 기준 messages 2개부터 완료된다.
# ============================================================================
Use-Token $tokenA

Invoke-Api -Method POST -Path "/api/matching/intents/session/restart" -Auth -Title "13.4 (A) 의도 세션 초기화" | Out-Null

Invoke-Api -Method POST -Path "/api/matching/intents/messages" -Auth -Title "13.5 (A) 의도 추출 1턴" -Body @{
    message = "백엔드 공부하려고 포트폴리오용 프로젝트 팀을 찾고 있어요."
} | Out-Null

$intent2 = Invoke-Api -Method POST -Path "/api/matching/intents/messages" -Auth -PassThru -Title "13.6 (A) 의도 추출 2턴 (완료 기대)" -Body @{
    message = "아직 입문 수준이고, 주 2회 정도 오프라인으로 만나고 싶어요."
}
Assert-Test -Title "13.6a 의도 추출 완료 (슬롯 + 임베딩 생성)" `
    -Condition ([bool]$intent2.data.completed) `
    -Detail ("completed={0}, slotId={1}" -f $intent2.data.completed, $intent2.data.slotId) | Out-Null

if (-not $intent2.data.completed) {
    Write-Host "(!) 의도 추출이 완료되지 않아 추천을 검증할 수 없습니다. AI 서버(스텁) 상태를 확인하세요." -ForegroundColor Red
    return
}

# ============================================================================
#  4) 추천 요청 — 본 검증
# ============================================================================
$rec = Invoke-Api -Method GET -Path "/api/matching/recommendations/user-to-team?limit=5" -Auth -PassThru `
    -Title "13.7 (A) 팀 추천 요청 (limit=5)"

$items = @($rec.data)
Assert-Test -Title "13.7a 추천 결과 수신" -Condition ([bool]($rec.success -and $items.Count -gt 0)) `
    -Detail ("{0}건" -f $items.Count) | Out-Null

if ($items.Count -eq 0) {
    Write-Host "(!) 후보가 0건입니다. 팀 임베딩이 아직 저장되지 않았을 수 있습니다 (team_embeddings 확인)." -ForegroundColor Red
    return
}

# 점수 내림차순 — 스텁은 일부러 오름차순으로 돌려주므로 정렬은 백엔드가 한 것이다.
$scores = @($items | ForEach-Object { [double]$_.score })
$sortedDesc = @($scores | Sort-Object -Descending)
Assert-Test -Title "13.7b 점수 내림차순 정렬" `
    -Condition (($scores -join ",") -eq ($sortedDesc -join ",")) `
    -Detail ("scores=[{0}]" -f ($scores -join ", ")) | Out-Null

# label 이 그대로 노출되는지
$allHaveLabel = @($items | Where-Object { -not $_.label }).Count -eq 0
Assert-Test -Title "13.7c 모든 추천에 label 노출" -Condition $allHaveLabel `
    -Detail ("첫 label='{0}'" -f $items[0].label) | Out-Null

# 역할이 맞는 BE 팀이 FE 팀보다 위
$rankBe = [array]::IndexOf(@($items | ForEach-Object { $_.teamId }), $teamBeId)
$rankFe = [array]::IndexOf(@($items | ForEach-Object { $_.teamId }), $teamFeId)
Assert-Test -Title "13.7d 역할 일치 팀(BE)이 불일치 팀(FE)보다 상위" `
    -Condition ($rankBe -ge 0 -and $rankFe -ge 0 -and $rankBe -lt $rankFe) `
    -Detail ("BE 순위={0}, FE 순위={1}" -f $rankBe, $rankFe) | Out-Null

# 내가 팀장인 팀은 후보에서 빠진다
$myOwnInResults = @($items | Where-Object { "$($_.leaderId)" -eq "$userIdA" })
Assert-Test -Title "13.7e 내가 팀장인 팀은 추천에서 제외" -Condition ($myOwnInResults.Count -eq 0) `
    -Detail ("내 팀 {0}건 포함" -f $myOwnInResults.Count) | Out-Null

# ============================================================================
#  4b) 표시 정보 — 활동 제목 / 현재 인원 (배치 조회로 채워지는 필드)
# ============================================================================
$beItem = @($items | Where-Object { $_.teamId -eq $teamBeId })[0]
$feItem = @($items | Where-Object { $_.teamId -eq $teamFeId })[0]

# 활동에 연결된 팀은 제목이 채워져야 한다 (FE 가 eventId 로는 활동을 조회할 수 없으므로).
if ($linkedEventId) {
    Assert-Test -Title "13.7f 활동 연결 팀에 connectedActivityTitle 채워짐" `
        -Condition ([bool]$beItem.connectedActivityTitle) `
        -Detail ("eventId={0}, title='{1}'" -f $beItem.eventId, $beItem.connectedActivityTitle) | Out-Null
} else {
    Write-Host "  (i) 연결할 활동이 없어 13.7f 를 건너뜁니다." -ForegroundColor Yellow
}

# 자율 프로젝트(eventId=null)는 활동 정보가 null 이어야 하고, 그것 때문에 응답이 깨지면 안 된다.
Assert-Test -Title "13.7g 자율 프로젝트 팀은 활동 정보가 null" `
    -Condition ($null -eq $feItem.eventId -and $null -eq $feItem.connectedActivityTitle) `
    -Detail ("eventId={0}, title={1}" -f $feItem.eventId, $feItem.connectedActivityTitle) | Out-Null

# 인원 수는 기존 팀 상세 조회와 반드시 같아야 한다 (두 엔드포인트가 다른 숫자를 내면 안 된다).
$teamDetail = Invoke-Api -Method GET -Path "/api/teams/$teamBeId" -Auth -PassThru `
    -Title "13.7h 팀 상세 조회 (currentMemberCount 교차 검증용)"
Assert-Test -Title "13.7i currentMemberCount 가 GET /api/teams/{id} 와 일치" `
    -Condition ([int]$beItem.currentMemberCount -eq [int]$teamDetail.data.currentMemberCount) `
    -Detail ("추천={0}, 상세={1}" -f $beItem.currentMemberCount, $teamDetail.data.currentMemberCount) | Out-Null

# ============================================================================
#  5) limit / eventId 필터
# ============================================================================
$rec1 = Invoke-Api -Method GET -Path "/api/matching/recommendations/user-to-team?limit=1" -Auth -PassThru `
    -Title "13.8 (A) limit=1 적용 확인"
Assert-Test -Title "13.8a limit=1 이면 1건만" -Condition (@($rec1.data).Count -eq 1) `
    -Detail ("{0}건" -f @($rec1.data).Count) | Out-Null

$recNone = Invoke-Api -Method GET -Path "/api/matching/recommendations/user-to-team?eventId=99999999" -Auth -PassThru `
    -Title "13.9 (A) 존재하지 않는 eventId - 빈 배열 기대 (404 아님)"
Assert-Test -Title "13.9a 후보 없음 → 200 + 빈 배열" `
    -Condition ([bool]($recNone.success -and @($recNone.data).Count -eq 0)) `
    -Detail ("{0}건" -f @($recNone.data).Count) | Out-Null

# ============================================================================
#  6) 뒷정리
# ============================================================================
if ($Cleanup) {
    Use-Token $tokenB
    if ($teamBeId) { Invoke-Api -Method DELETE -Path "/api/teams/$teamBeId" -Auth -Title "13.10 뒷정리 (BE팀 삭제)" | Out-Null }
    if ($teamFeId) { Invoke-Api -Method DELETE -Path "/api/teams/$teamFeId" -Auth -Title "13.10 뒷정리 (FE팀 삭제)" | Out-Null }
    Use-Token $tokenA
} else {
    Write-Host ""
    Write-Host "  (i) B 가 만든 팀을 남깁니다 (teamBeId=$teamBeId, teamFeId=$teamFeId)." -ForegroundColor Yellow
    Write-Host "      삭제까지 하려면 -Cleanup 을 붙여 실행하세요." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "  (i) 추천 로그는 API 로 노출되지 않습니다. DB 에서 확인하세요:" -ForegroundColor DarkGray
Write-Host "      SELECT l.id, l.user_id, l.event_id, l.candidate_count, l.created_at," -ForegroundColor DarkGray
Write-Host "             i.rank_no, i.team_id, i.score, i.label" -ForegroundColor DarkGray
Write-Host "        FROM user_to_team_recommendation_logs l" -ForegroundColor DarkGray
Write-Host "        JOIN user_to_team_recommendation_items i ON i.log_id = l.id" -ForegroundColor DarkGray
Write-Host "       WHERE l.user_id = $userIdA ORDER BY l.id DESC, i.rank_no;" -ForegroundColor DarkGray

Write-Host "`n########## 13. Recommendation 테스트 완료 ##########" -ForegroundColor Magenta
