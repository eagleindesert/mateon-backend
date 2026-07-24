# 13_recommendation.ps1 (for-api-server) - 유저→팀 추천  GET /api/matching/recommendations/user-to-team
#
# ============================================================================
#  [!] 과금 주의 - 이 스크립트는 실제 LLM / 임베딩을 호출합니다. 이 폴더에서 가장 많이 씁니다.
#      예상 호출 7회:
#        - 팀 생성 2회  -> 비동기 임베딩 갱신 (LLM 추출 + 임베딩)
#        - 의도 추출 2회 -> POST /intents/messages (LLM + 임베딩)
#        - 추천 점수화 3회 -> limit=5 / limit=200 / limit=1 각각 AI 호출
#      ※ 후보가 0건인 요청(13.9 없는 eventId)과 의도 미완료 차단(13.3)은 AI 를 호출하지 않습니다.
#      ※ 추천은 의도 추출과 달리 AI 호출이 동기입니다 — 느리면 AI 서버 응답을 기다리는 중입니다.
#
#      과금 없이 돌리려면 백엔드가 로컬 스텁을 보게 하세요:
#        pwsh -File ..\debug\ai-stub\stub-ai-server.ps1     # 포트 8000
#        백엔드 AI_BASE_URL=http://localhost:8000 으로 재기동
# ============================================================================
#
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

try {

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

# ── 표시정보 검증은 limit 을 넉넉히 준 응답으로 한다 ───────────────────────
# limit=5 창만 보면 DB 에 팀이 쌓일수록 우리가 만든 팀이 밖으로 밀려 "없음"이 된다.
# 그건 추천 로직이 아니라 테스트 데이터 누적 탓이므로 넉넉히 받아서 찾는다.
#
# 주의: limit 을 키워도 AI 가 돌려준 건수는 넘지 못한다. 백엔드는 AI 응답에 담긴 것만 싣고
# limit 으로 더 자를 뿐 늘리지 못한다(RecommendationService). 실서버는 자체 컷오프로 상위 일부만
# 주므로(실측 후보 20건 → 응답 10건) 이 응답도 후보 전량은 아니다.
$recAll = Invoke-Api -Method GET -Path "/api/matching/recommendations/user-to-team?limit=200" -Auth -PassThru `
    -Title "13.7-all (A) 넉넉한 limit 으로 재조회 (표시정보 검증용)"
$allItems = @($recAll.data)

# [보류 - 스텁 전용 검증] 13.7d 역할 일치 팀(BE)이 불일치 팀(FE)보다 상위
#
# 비활성화 이유: 이 검증은 "BE 와 FE 가 둘 다 결과에 있다"를 전제하는데, 실서버에서는 성립하지
# 않는다. 실서버는 후보를 전부 돌려주지 않고 자체 컷오프로 상위 일부만 준다 — 실측에서 후보 20건을
# 보냈는데 응답은 10건이었고, 역할이 안 맞는 FE 팀은 아예 빠져 있었다(추천 로그로 확인).
#   SELECT l.candidate_count, (SELECT count(*) FROM user_to_team_recommendation_items i
#          WHERE i.log_id = l.id) AS returned
#   FROM user_to_team_recommendation_logs l ORDER BY l.id DESC LIMIT 3;
#
# 백엔드의 limit 파라미터로는 못 늘린다. AI 응답에 담긴 것만 응답에 실리고(RecommendationService),
# limit 은 그걸 더 자를 뿐이다. 그래서 limit=200 으로 요청해도 FE 팀은 나타나지 않는다.
# 스텁은 받은 후보를 전부 돌려주므로 스텁으로 돌리면 이 검증은 통과한다.
#
# 되살리는 조건: AI 서버가 역할 불일치 팀도 포함해 돌려주도록 스펙이 바뀌거나,
# 이 검증을 "BE 가 결과에 있다" 정도로 약화시킬 때. 후자라면 rankFe 조건을 빼면 된다.
#
# $allIds = @($allItems | ForEach-Object { $_.teamId })
# $rankBe = [array]::IndexOf($allIds, $teamBeId)
# $rankFe = [array]::IndexOf($allIds, $teamFeId)
# Assert-Test -Title "13.7d 역할 일치 팀(BE)이 불일치 팀(FE)보다 상위" `
#     -Condition ($rankBe -ge 0 -and $rankFe -ge 0 -and $rankBe -lt $rankFe) `
#     -Detail ("BE 순위={0}, FE 순위={1} / 전체 {2}건" -f $rankBe, $rankFe, $allItems.Count) | Out-Null
Write-Host "  (i) 13.7d(역할 일치 순위)는 보류 - 실서버는 역할 불일치 팀을 응답에서 제외한다." -ForegroundColor DarkGray

# 내가 팀장인 팀은 후보에서 빠진다
$myOwnInResults = @($items | Where-Object { "$($_.leaderId)" -eq "$userIdA" })
Assert-Test -Title "13.7e 내가 팀장인 팀은 추천에서 제외" -Condition ($myOwnInResults.Count -eq 0) `
    -Detail ("내 팀 {0}건 포함" -f $myOwnInResults.Count) | Out-Null

# ============================================================================
#  4b) 표시 정보 — 활동 제목 / 현재 인원 (배치 조회로 채워지는 필드)
# ============================================================================
# limit=5 창이 아니라 전체 목록에서 찾는다 (13.7d 와 같은 이유).
$beItem = @($allItems | Where-Object { $_.teamId -eq $teamBeId })[0]
$feItem = @($allItems | Where-Object { $_.teamId -eq $teamFeId })[0]

# [보류 - 스텁 전용 검증] 13.7e2 검증 대상 두 팀이 추천 목록에 존재
#
# 비활성화 이유: 13.7d 와 같다. 실서버는 역할이 안 맞는 팀을 응답에서 빼므로 FE 팀은 정상적으로
# 없을 수 있다. 실측에서 FE 팀은 team_embeddings 가 SUCCESS + 벡터 보유 + 모집 중이었는데도
# 추천에 없었다 — 즉 백엔드 후보 선정은 정상이고 AI 응답에서 빠진 것이다.
# "둘 다 있어야 한다"는 전제 자체가 실서버에서 틀렸다.
#
# 되살리는 조건: 13.7d 와 동일.
#
# $itemsFound = ($null -ne $beItem -and $null -ne $feItem)
# Assert-Test -Title "13.7e2 검증 대상 두 팀이 추천 목록에 존재" -Condition $itemsFound `
#     -Detail ("BE={0}, FE={1}" -f [bool]$beItem, [bool]$feItem) | Out-Null

# 아래 검증들은 대상 항목이 있을 때만 의미가 있다. 특히 13.7g 는 $feItem 이 $null 이면
# $null.eventId 도 $null 이라 "잘못된 이유로 PASS" 해버리므로 반드시 존재를 먼저 확인해야 한다.
# 팀별로 따로 본다 — 실서버에서 FE 가 빠지는 건 정상이므로, 그것 때문에 BE 쪽 검증까지
# 버리면 안 된다.
if ($null -ne $beItem) {
    # 활동에 연결된 팀은 제목이 채워져야 한다 (FE 가 eventId 로는 활동을 조회할 수 없으므로).
    if ($linkedEventId) {
        Assert-Test -Title "13.7f 활동 연결 팀에 connectedActivityTitle 채워짐" `
            -Condition ([bool]$beItem.connectedActivityTitle) `
            -Detail ("eventId={0}, title='{1}'" -f $beItem.eventId, $beItem.connectedActivityTitle) | Out-Null
    } else {
        Write-Host "  (i) 연결할 활동이 없어 13.7f 를 건너뜁니다." -ForegroundColor Yellow
    }

    # 인원 수는 기존 팀 상세 조회와 반드시 같아야 한다 (두 엔드포인트가 다른 숫자를 내면 안 된다).
    $teamDetail = Invoke-Api -Method GET -Path "/api/teams/$teamBeId" -Auth -PassThru `
        -Title "13.7h 팀 상세 조회 (currentMemberCount 교차 검증용)"
    Assert-Test -Title "13.7i currentMemberCount 가 GET /api/teams/{id} 와 일치" `
        -Condition ([int]$beItem.currentMemberCount -eq [int]$teamDetail.data.currentMemberCount) `
        -Detail ("추천={0}, 상세={1}" -f $beItem.currentMemberCount, $teamDetail.data.currentMemberCount) | Out-Null
} else {
    # BE 는 역할이 맞아 정상이면 응답에 들어와야 한다. 없다면 임베딩 저장 실패나 후보 상한을 의심한다.
    Write-Host "  (i) BE 팀이 추천 목록에 없어 13.7f/13.7h/13.7i 를 건너뜁니다." -ForegroundColor Yellow
    Write-Host "      team_embeddings.refresh_status 로 임베딩 저장 상태를 확인하세요." -ForegroundColor DarkGray
}

if ($null -ne $feItem) {
    # 자율 프로젝트(eventId=null)는 활동 정보가 null 이어야 하고, 그것 때문에 응답이 깨지면 안 된다.
    Assert-Test -Title "13.7g 자율 프로젝트 팀은 활동 정보가 null" `
        -Condition ($null -eq $feItem.eventId -and $null -eq $feItem.connectedActivityTitle) `
        -Detail ("eventId={0}, title={1}" -f $feItem.eventId, $feItem.connectedActivityTitle) | Out-Null
} else {
    # 실서버는 역할 불일치 팀을 응답에서 빼므로 FE 부재는 정상이다 (스텁은 전부 돌려줘 검증된다).
    Write-Host "  (i) FE 팀이 추천 목록에 없어 13.7g 를 건너뜁니다 - 실서버에서는 정상입니다." -ForegroundColor DarkGray
}

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

} finally {
    Write-TestSummary | Out-Null
}
