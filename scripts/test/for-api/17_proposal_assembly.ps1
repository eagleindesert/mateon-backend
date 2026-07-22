# 17_proposal_assembly.ps1 (for-api-server) - 최종 제안 조립 (AI 가 지원/제안 문구 초안을 써 준다)
#   POST /api/matching/proposals/user-to-team
#   POST /api/matching/proposals/team-to-user
#
# ============================================================================
#  [!] 과금 주의 - 이 스크립트는 실제 LLM / 임베딩을 호출합니다.
#      예상 호출 10회:
#        - 팀 생성 1회   -> 비동기 임베딩 갱신 (LLM 추출 + 임베딩)
#        - 의도 추출 4회 -> A/B 각 2턴 (LLM + 임베딩)
#        - 추천 점수화 2회 -> 유저→팀 1회, 팀→유저 1회
#        - 제안 조립 3회 -> 정방향 2회(재요청 검증 포함) + 역방향 1회 (LLM)
#      ※ 404(17.5)·400(17.5b)·403(17.7)은 AI 를 호출하지 않습니다 — 그게 검증 대상입니다.
#
#      과금 없이 돌리려면 백엔드가 로컬 스텁을 보게 하세요:
#        pwsh -File ..\debug\ai-stub\stub-ai-server.ps1     # 포트 8000
#        백엔드 AI_BASE_URL=http://localhost:8000 으로 재기동
# ============================================================================
#
# 사용법: pwsh -File .\17_proposal_assembly.ps1            # 기본: 만든 팀을 남긴다
#        pwsh -File .\17_proposal_assembly.ps1 -Cleanup    # B 가 만든 팀까지 삭제
# 사전 조건: 16 번과 동일 (로그인 선행, 유저 B 계정, 양쪽 학교 인증, AI 서버 또는 스텁).
#
# [이 기능의 계약 — 프론트가 기대하는 것]
#   조립은 초안만 만든다. 아무것도 저장하지 않으므로 이 호출만으로는 지원서가 생기지 않는다.
#   사용자가 문구를 고쳐 기존 발송 API(POST /api/teams/{id}/apply)로 보내야 지원서가 되고,
#   그때 나오는 applicationId 가 AI 명세의 proposal_id 다. 17.8 이 그 연결을 검증한다.
#
# [검증 범위]
#   - 추천에 뜬 팀에 대해 summary/message 초안이 만들어지는지
#   - synergyScore 가 추천 목록의 score 와 같은지 (조립 단계에서 재계산하지 않는다)
#   - 재요청하면 새 문구가 나오는지 (초안은 캐시하지 않는다 — 16 번과 기대가 반대다)
#   - 역방향(팀→유저)도 같은 모양으로 동작하고 direction 이 뒤집히는지
#   - 추천에 뜬 적 없는 팀은 404, teamId 누락은 400
#   - 팀장이 아니면 역방향 조립을 못 하는지 (403)
#   - 초안 → 실제 발송까지 이어지는지 (proposal_id 채번 지점)
#
# [!] "캐시하지 않음" 검증의 원리
#     스텁은 초안에 호출 일련번호 [stub#N] 을 박아 준다. 같은 쌍을 두 번 물었을 때 N 이 올라가야
#     정상이다. 실서버로 돌리면 번호가 없지만 LLM 이 매번 다른 문장을 만들므로 "두 응답이 다른
#     문자열인가"로 같은 것을 본다.
param(
    [switch]$Cleanup,
    [string]$UserBEmail,       # 미지정 시 00_common 의 UserBEmail
    [string]$UserBPassword     # 미지정 시 00_common 의 UserBPassword
)
. "$PSScriptRoot\00_common.ps1"

if (-not $UserBEmail)    { $UserBEmail    = $script:UserBEmail }
if (-not $UserBPassword) { $UserBPassword = $script:UserBPassword }

Write-Host "`n########## 17. Proposal Assembly (최종 제안 조립) [인증 필요] ##########" -ForegroundColor Magenta

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
$loginB = Invoke-Api -Method POST -Path "/api/auth/login" -PassThru -Title "17.0 유저 B 로그인 (후보 팀 팀장)" -Body @{
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
Write-Host "  (i) userIdA=$userIdA (지원하는 쪽), userIdB=$userIdB (팀장)" -ForegroundColor Green

# ============================================================================
#  1) B 가 후보 팀 생성
# ============================================================================
# 16 번의 추천 이력을 재사용하지 않고 자체적으로 만든다 — 앞 스크립트가 -Cleanup 으로 팀을
# 지웠는지에 따라 (질의, 후보) 쌍이 있을 수도 없을 수도 있어 의존하면 불안정해진다.
#
# eventId 를 null 로 둔다(자율 프로젝트). 이러면 contest_id 가 null 인 채로 AI 에 나가는데,
# 실서버가 이 필드를 필수로 보면 여기서 422 -> 502 로 드러난다. 스텁은 잡지 못하는 항목이라
# 실서버 검증 때 이 지점을 눈여겨볼 것.
Use-Token $tokenB

$team = Invoke-Api -Method POST -Path "/api/teams" -Auth -PassThru -Title "17.1 (B) BE 모집 팀 생성 (자율 프로젝트 - contest_id null 경로)" -Body @{
    eventId              = $null
    title                = "제안조립 BE팀 $((Get-Random -Maximum 9999))"
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
    Assert-Test -Title "17.1a 후보 팀 생성 성공" -Condition $false -Detail "teamId 없음" | Out-Null
    Use-Token $tokenA
    return
}
Write-Host "  (i) teamId=$teamId" -ForegroundColor Green

# 팀 임베딩은 커밋 후 비동기로 저장된다. 초안의 요약이 여기서 나오므로 기다린다.
Write-Host "  (i) 비동기 팀 임베딩 저장 대기 (4초)..." -ForegroundColor DarkGray
Start-Sleep -Seconds 4

# B 도 의도 추출을 해 둔다 — 역방향(팀→유저) 후보가 되려면 슬롯이 있어야 한다.
Invoke-Api -Method POST -Path "/api/matching/intents/session/restart" -Auth -Title "17.1b (B) 의도 세션 초기화" | Out-Null
Invoke-Api -Method POST -Path "/api/matching/intents/messages" -Auth -Title "17.1c (B) 의도 추출 1턴" -Body @{
    message = "백엔드 쪽으로 사이드 프로젝트를 하고 싶어요."
} | Out-Null
Invoke-Api -Method POST -Path "/api/matching/intents/messages" -Auth -Title "17.1d (B) 의도 추출 2턴 (완료 기대)" -Body @{
    message = "입문 수준이고 주 2회 오프라인이면 좋겠어요."
} | Out-Null

# ============================================================================
#  2) A 의 의도 추출 완료 → 유저→팀 추천 (조립의 근거가 될 추천 이력을 만든다)
# ============================================================================
Use-Token $tokenA

Invoke-Api -Method POST -Path "/api/matching/intents/session/restart" -Auth -Title "17.2 (A) 의도 세션 초기화" | Out-Null
Invoke-Api -Method POST -Path "/api/matching/intents/messages" -Auth -Title "17.2a (A) 의도 추출 1턴" -Body @{
    message = "백엔드 공부하려고 포트폴리오용 프로젝트 팀을 찾고 있어요."
} | Out-Null
$intent = Invoke-Api -Method POST -Path "/api/matching/intents/messages" -Auth -PassThru -Title "17.2b (A) 의도 추출 2턴 (완료 기대)" -Body @{
    message = "아직 입문 수준이고, 주 2회 정도 오프라인으로 만나고 싶어요."
}
if (-not $intent.data.completed) {
    Write-Host "(!) 의도 추출이 완료되지 않아 제안 조립을 검증할 수 없습니다. AI 서버(스텁) 상태를 확인하세요." -ForegroundColor Red
    return
}

# limit 을 넉넉히 준다 — 테스트 데이터가 쌓일수록 방금 만든 팀이 상위 몇 건 밖으로 밀린다.
$rec = Invoke-Api -Method GET -Path "/api/matching/recommendations/user-to-team?limit=200" -Auth -PassThru `
    -Title "17.2c (A) 팀 추천 요청 (조립의 근거가 될 추천 이력 생성)"
$recItems = @($rec.data)

# 조립은 "추천에 뜬 팀"에 대해서만 할 수 있다. 방금 만든 팀이 응답에 없으면(AI 컷오프에
# 걸렸으면) 아무 추천 팀이나 하나 잡아서 검증한다 — 검증 대상은 조립이지 순위가 아니다.
$targetItem = @($recItems | Where-Object { $_.teamId -eq $teamId } | Select-Object -First 1)
if (-not $targetItem) {
    $targetItem = @($recItems)[0]
    if ($targetItem) {
        Write-Host "  (i) 방금 만든 팀이 추천 응답에 없어 상위 팀(teamId=$($targetItem.teamId))으로 검증합니다." -ForegroundColor Yellow
    }
}
if (-not $targetItem) {
    Write-Host "(!) 추천 결과가 0건이라 조립을 검증할 수 없습니다 (team_embeddings 확인)." -ForegroundColor Red
    Use-Token $tokenA
    return
}
$targetTeamId = $targetItem.teamId
$expectedScore = $targetItem.score

# ============================================================================
#  3) 유저→팀 제안 조립 — 본 검증
# ============================================================================
$draft1 = Invoke-Api -Method POST -Path "/api/matching/proposals/user-to-team" -Auth -PassThru `
    -Title "17.3 (A) 추천받은 팀에 보낼 지원 문구 조립" -Body @{ teamId = $targetTeamId }

$summary1 = $draft1.data.summary
$message1 = $draft1.data.message

Assert-Test -Title "17.3a summary 가 비어있지 않음" `
    -Condition ([bool](-not [string]::IsNullOrWhiteSpace($summary1))) `
    -Detail ("summary='{0}'" -f $summary1) | Out-Null

Assert-Test -Title "17.3b message 가 비어있지 않음" `
    -Condition ([bool](-not [string]::IsNullOrWhiteSpace($message1))) `
    -Detail ("message='{0}'" -f $message1) | Out-Null

Assert-Test -Title "17.3c direction 이 USER_TO_TEAM" `
    -Condition ([bool]($draft1.data.direction -eq "USER_TO_TEAM")) `
    -Detail ("direction='{0}' (AI 응답이 아니라 호출된 경로가 출처)" -f $draft1.data.direction) | Out-Null

# 조립 단계에서 점수를 재계산하지 않는다는 게 AI 명세다. 추천 목록에서 본 값과 같아야 한다.
Assert-Test -Title "17.3d synergyScore 가 추천 목록의 score 와 같음 (재계산 없음)" `
    -Condition ([bool]($draft1.data.synergyScore -eq $expectedScore)) `
    -Detail ("추천 score={0} / 조립 synergyScore={1}" -f $expectedScore, $draft1.data.synergyScore) | Out-Null

# 항상 null 인 예약 필드를 프론트에 흘리지 않기로 했다 (있으면 "언젠가 채워지는 값"으로 오해된다).
Assert-Test -Title "17.3e portfolioRoleFitScore 를 응답에 노출하지 않음" `
    -Condition ([bool](-not ($draft1.data.PSObject.Properties.Name -contains "portfolioRoleFitScore"))) `
    -Detail "명세상 항상 null 인 예약 필드" | Out-Null

# 조립만으로는 아무것도 저장되지 않는다 — 지원서가 생겼다면 계약 위반이다.
$myApps = Invoke-Api -Method GET -Path "/api/teams/applications/me" -Auth -PassThru `
    -Title "17.3f (A) 조립 후 내 지원서 목록 조회"
$appsForTarget = @($myApps.data | Where-Object { $_.teamId -eq $targetTeamId })
Assert-Test -Title "17.3g 조립만으로는 지원서가 생기지 않음 (초안일 뿐)" `
    -Condition ([bool]($appsForTarget.Count -eq 0)) `
    -Detail ("teamId={0} 지원서 {1}건 - 0건이어야 한다" -f $targetTeamId, $appsForTarget.Count) | Out-Null

# ============================================================================
#  4) 재요청 — 초안은 캐시하지 않으므로 새 문구가 나와야 한다 (16 번과 기대가 반대)
# ============================================================================
$draft2 = Invoke-Api -Method POST -Path "/api/matching/proposals/user-to-team" -Auth -PassThru `
    -Title "17.4 (A) 같은 팀에 재조립 요청 (새 문구 기대 - 캐시 없음)" -Body @{ teamId = $targetTeamId }

Assert-Test -Title "17.4a 재요청 시 새 문구 (매번 AI 를 부른다)" `
    -Condition ([bool]($draft2.data.message -ne $message1)) `
    -Detail ("스텁이면 [stub#N] 의 N 이 올라가야 한다. 1차='{0}' / 2차='{1}'" -f $message1, $draft2.data.message) | Out-Null

# ============================================================================
#  5) 추천에 뜬 적 없는 팀 → 404 / teamId 누락 → 400
# ============================================================================
Invoke-Api -Method POST -Path "/api/matching/proposals/user-to-team" -Auth `
    -Title "17.5 (A) 추천 이력 없는 teamId 로 조립 요청 - 차단 기대 (404 RECOMMENDATION_NOT_FOUND)" `
    -Body @{ teamId = 999999999 } | Out-Null

# teamId 누락은 검증 단계에서 400 으로 걸린다 (AI 까지 가지 않는다).
Invoke-Api -Method POST -Path "/api/matching/proposals/user-to-team" -Auth `
    -Title "17.5b (A) teamId 누락 - 차단 기대 (400)" -Body @{ } | Out-Null

# ============================================================================
#  6) 역방향 (팀→유저) — B 가 팀장으로서 추천받은 유저에게 보낼 문구를 조립한다
# ============================================================================
Use-Token $tokenB

$recUsers = Invoke-Api -Method GET -Path "/api/matching/recommendations/team-to-user?teamId=$teamId&limit=200" -Auth -PassThru `
    -Title "17.6 (B) 역제안 추천 요청 (역방향 조립의 근거 생성)"
$targetUser = @($recUsers.data)[0]
$targetUserId = $targetUser.userId

if ($targetUserId) {
    $draftU = Invoke-Api -Method POST -Path "/api/matching/proposals/team-to-user" -Auth -PassThru `
        -Title "17.6a (B) 추천받은 유저에게 보낼 제안 문구 조립" -Body @{ teamId = $teamId; userId = $targetUserId }

    Assert-Test -Title "17.6b 역방향 summary/message 가 비어있지 않음" `
        -Condition ([bool]((-not [string]::IsNullOrWhiteSpace($draftU.data.summary)) -and
                           (-not [string]::IsNullOrWhiteSpace($draftU.data.message)))) `
        -Detail ("summary='{0}' / message='{1}'" -f $draftU.data.summary, $draftU.data.message) | Out-Null

    Assert-Test -Title "17.6c direction 이 TEAM_TO_USER" `
        -Condition ([bool]($draftU.data.direction -eq "TEAM_TO_USER")) `
        -Detail ("direction='{0}'" -f $draftU.data.direction) | Out-Null

    Assert-Test -Title "17.6d 역방향 synergyScore 도 추천 목록의 score 와 같음" `
        -Condition ([bool]($draftU.data.synergyScore -eq $targetUser.score)) `
        -Detail ("추천 score={0} / 조립 synergyScore={1}" -f $targetUser.score, $draftU.data.synergyScore) | Out-Null

    # ========================================================================
    #  7) 팀장이 아니면 역방향 조립을 할 수 없다 (A 는 이 팀의 팀장이 아니다)
    # ========================================================================
    Use-Token $tokenA
    Invoke-Api -Method POST -Path "/api/matching/proposals/team-to-user" -Auth `
        -Title "17.7 (A) 남의 팀 역방향 조립 요청 - 차단 기대 (403)" `
        -Body @{ teamId = $teamId; userId = $targetUserId } | Out-Null
} else {
    Write-Host "  (i) 역제안 후보가 0건이라 역방향 검증을 건너뜁니다 (의도 추출한 유저가 부족)." -ForegroundColor Yellow
}

# ============================================================================
#  8) 초안 → 실제 발송 (여기서 proposal_id 가 채번된다)
# ============================================================================
# 조립이 만든 문구를 그대로 기존 발송 API 에 넣는다. 프론트가 실제로 하는 일과 같다 (사용자가
# 수정할 수도 있지만, 수정 없이도 그대로 들어가는지가 계약이다).
# 방금 만든 팀(B 의 팀)에 지원한다 — 상위 팀으로 밀렸을 경우 이미 지원했을 수 있어 그때는 건너뛴다.
Use-Token $tokenA

if ($targetTeamId -eq $teamId) {
    $apply = Invoke-Api -Method POST -Path "/api/teams/$teamId/apply" -Auth -PassThru `
        -Title "17.8 (A) 조립한 문구로 실제 지원 (proposal_id 채번)" -Body @{
        introduction  = $summary1
        message       = $message1
        contactNumber = "010-0000-0000"
    }

    Assert-Test -Title "17.8a 조립 문구로 지원이 성공함" `
        -Condition ([bool]($apply.success)) `
        -Detail "초안 -> 발송 경로가 이어진다" | Out-Null

    $myAppsAfter = Invoke-Api -Method GET -Path "/api/teams/applications/me" -Auth -PassThru `
        -Title "17.8b (A) 발송 후 내 지원서 목록 조회"
    $created = @($myAppsAfter.data | Where-Object { $_.teamId -eq $teamId } | Select-Object -First 1)
    Assert-Test -Title "17.8c 지원서 id 가 채번됨 (= 명세의 proposal_id)" `
        -Condition ([bool]($created.applicationId)) `
        -Detail ("applicationId={0}" -f $created.applicationId) | Out-Null

    # 초안이 그대로 저장됐는지 — 조립 결과와 발송 결과가 이어진다는 계약의 마지막 고리다.
    Assert-Test -Title "17.8d 저장된 지원서에 조립한 문구가 그대로 들어감" `
        -Condition ([bool]($created.message -eq $message1)) `
        -Detail ("저장된 message='{0}'" -f $created.message) | Out-Null
} else {
    Write-Host "  (i) 조립 대상이 이 스크립트가 만든 팀이 아니라 발송 검증을 건너뜁니다 (중복 지원 위험)." -ForegroundColor Yellow
}

# ============================================================================
#  정리
# ============================================================================
Use-Token $tokenA

if ($Cleanup) {
    Use-Token $tokenB
    Invoke-Api -Method DELETE -Path "/api/teams/$teamId" -Auth -Title "17.9 (B) 테스트 팀 삭제" -NoTrack | Out-Null
    Use-Token $tokenA
    Write-Host "  (i) 정리 완료. 추천 로그는 남습니다 (시점 기록이라 팀이 지워져도 보존)." -ForegroundColor DarkGray
} else {
    Write-Host "  (i) 만든 팀(teamId=$teamId)을 남겨 둡니다. 지우려면 -Cleanup 을 주세요." -ForegroundColor DarkGray
}

Write-Host ""
Write-Host "  [DB 확인] 조립은 아무것도 저장하지 않는다 - 늘어나는 건 지원서뿐:" -ForegroundColor DarkGray
Write-Host "    SELECT id, team_id, user_id, introduction FROM team_applications ORDER BY id DESC LIMIT 5;" -ForegroundColor DarkGray
Write-Host "  [스텁 콘솔 확인] 백엔드가 제대로 실어 보냈는지:" -ForegroundColor DarkGray
Write-Host "    - sender_id/receiver_id 가 [OK] 로 찍혔는가 (방향에 맞게 뒤집혔는가)" -ForegroundColor DarkGray
Write-Host "    - candidate_summary/target_summary 에 [!!] 가 없는가" -ForegroundColor DarkGray
Write-Host "    - contest_id 가 null(자율 프로젝트)로 나갔는가 - 실서버가 이걸 거부하는지 확인 필요" -ForegroundColor DarkGray

Write-Host "`n########## 17. Proposal Assembly 테스트 완료 ##########" -ForegroundColor Magenta
