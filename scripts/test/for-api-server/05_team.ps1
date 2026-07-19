# 05_team.ps1 - Team (팀 모집/지원) API 테스트  /api/teams  [인증 필요]
#
# ============================================================================
#  [!] 과금 주의 - 팀 API 는 AI 를 간접 호출합니다.
#      팀을 만들거나 수정하면 커밋 후 비동기로 팀 임베딩 갱신(LLM 추출 + 임베딩)이 돕니다.
#      이 스크립트는 AI 를 직접 부르지 않지만 결과적으로 과금됩니다.
#      예상 호출: 2회 (5.3 팀 생성 1 + 5.4 수정 1)
#
#      과금 없이 돌리려면 백엔드가 로컬 스텁을 보게 하세요:
#        pwsh -File .\debug\ai-stub\stub-ai-server.ps1      # 포트 8000
#        백엔드 AI_BASE_URL=http://localhost:8000 으로 재기동
# ============================================================================
#
# 사용법: powershell -ExecutionPolicy Bypass -File .\05_team.ps1
# 사전 조건: 02_auth.ps1 로 로그인하여 .auth-token.txt 가 생성되어 있어야 한다.
#
# 흐름: 팀 생성 -> 목록/상세 조회 -> 수정 -> (지원 관련) -> 삭제
# 지원(apply)은 보통 "다른 사용자"가 수행하므로, 본인 팀에 지원 시 서버 정책에 따라
# 거절될 수 있다. 응답 상태로 동작을 확인한다.
. "$PSScriptRoot\00_common.ps1"

Write-Host "`n########## 5. Team (팀 모집/지원) - /api/teams [인증 필요] ##########" -ForegroundColor Magenta

if (-not (Get-AccessToken)) {
    Write-Host "(!) accessToken 이 없습니다. 먼저 .\auth\02_auth.ps1 을 실행하세요." -ForegroundColor Red
    return
}

# 5.3 팀 모집글 작성
$created = Invoke-Api -Method POST -Path "/api/teams" -Auth -PassThru -Title "5.3 팀 모집글 작성" -Body @{
    eventId              = $null
    title                = "자동테스트 팀 모집 $((Get-Random -Maximum 9999))"
    promotionText        = "함께 성장할 팀원을 찾습니다."
    role                 = @("백엔드", "프론트엔드")
    characteristic       = "열정적인 팀"
    capacity             = 4
    recruitmentStartDate = (Get-Date).ToString("yyyy-MM-dd")
    recruitmentEndDate   = (Get-Date).AddDays(30).ToString("yyyy-MM-dd")
}
$teamId = $created.data.id
if ($teamId) { Write-Host "  (i) 생성된 teamId = $teamId" -ForegroundColor Green }

# 5.1 팀 모집글 목록 조회
Invoke-Api -Method GET -Path "/api/teams" -Auth -Title "5.1 팀 모집글 목록 조회 (전체)"
Invoke-Api -Method GET -Path "/api/teams?myPosts=true" -Auth -Title "5.1 팀 모집글 목록 조회 (내 글만)"

# 5.2 팀 모집글 상세 조회
if ($teamId) {
    Invoke-Api -Method GET -Path "/api/teams/$teamId" -Auth -Title "5.2 팀 모집글 상세 조회"
}

# 5.4 팀 모집글 수정
if ($teamId) {
    Invoke-Api -Method PUT -Path "/api/teams/$teamId" -Auth -Title "5.4 팀 모집글 수정" -Body @{
        eventId              = $null
        title                = "수정된 팀 모집글"
        promotionText        = "수정된 홍보 문구"
        role                 = @("백엔드")
        characteristic       = "수정된 특징"
        capacity             = 3
        recruitmentStartDate = (Get-Date).ToString("yyyy-MM-dd")
        recruitmentEndDate   = (Get-Date).AddDays(15).ToString("yyyy-MM-dd")
    }
}

# 5.6 팀 지원하기 — 여기서는 지원자=방금 팀을 만든 본인이므로, 서버 정책상 "차단"되어야 정상이다.
#     (TeamService.applyToTeam: 본인이 개설한 팀에는 지원 불가 → 400, success=false)
if ($teamId) {
    Write-Host "`n[5.6 팀 지원하기] 본인이 만든 팀에 지원 → 차단(거절)될 것으로 기대합니다." -ForegroundColor Yellow
    $applied = Invoke-Api -Method POST -Path "/api/teams/$teamId/apply" -Auth -PassThru -Title "5.6 팀 지원하기 (본인 팀 → 차단 기대)" -Body @{
        introduction  = "간단 소개입니다."
        message       = "지원 동기입니다."
        contactNumber = "010-1234-5678"
        portfolioUrl  = "https://github.com/example"
    }
    # 차단되면 응답 envelope 의 success 가 false 로 내려온다.
    $blocked   = [bool]($applied -and ($applied.success -eq $false))
    $blockMsg  = if ($applied) { $applied.message } else { $null }
    Assert-Test -Title "5.6 본인 팀 지원 차단" -Condition $blocked -Detail "message=$blockMsg" | Out-Null
}

# 5.7 내가 쓴 지원서 목록
$myApps = Invoke-Api -Method GET -Path "/api/teams/applications/me" -Auth -PassThru -Title "5.7 내가 쓴 지원서 목록"
$applicationId = $null
if ($myApps.data -and $myApps.data.Count -gt 0) { $applicationId = $myApps.data[0].applicationId }

# 5.8 내 팀에 온 지원서 목록 (팀장용)
if ($teamId) {
    $teamApps = Invoke-Api -Method GET -Path "/api/teams/$teamId/applications" -Auth -PassThru -Title "5.8 내 팀에 온 지원서 목록 (팀장용)"
    if (-not $applicationId -and $teamApps.data -and $teamApps.data.Count -gt 0) {
        $applicationId = $teamApps.data[0].applicationId
    }
}

if ($applicationId) {
    Write-Host "  (i) 대상 applicationId = $applicationId" -ForegroundColor Green

    # 5.12 지원서 상세 조회
    Invoke-Api -Method GET -Path "/api/teams/applications/$applicationId" -Auth -Title "5.12 지원서 상세 조회"

    # 5.10 지원서 수정 (지원자용)
    Invoke-Api -Method PUT -Path "/api/teams/applications/$applicationId" -Auth -Title "5.10 지원서 수정 (지원자용)" -Body @{
        introduction  = "수정된 소개"
        message       = "수정된 지원 동기"
        contactNumber = "010-9999-8888"
        portfolioUrl  = "https://github.com/example2"
    }

    # 5.9 지원서 승인/거절 (팀장용)
    Invoke-Api -Method PATCH -Path "/api/teams/applications/$applicationId`?isApproved=true" -Auth -Title "5.9 지원서 승인 (팀장용)"

    # 5.11 지원 취소 (지원자용) - 기본은 스킵
    Write-Host "`n[5.11 지원 취소] 예시만 표기 - 실제 취소를 원하면 아래 주석을 해제하세요." -ForegroundColor Yellow
    # Invoke-Api -Method DELETE -Path "/api/teams/applications/$applicationId" -Auth -Title "5.11 지원 취소 (지원자용)"
} else {
    Write-Host "`n[5.9~5.12 지원서 관련] 스킵 - 조회된 지원서(applicationId)가 없습니다." -ForegroundColor Yellow
}

# 5.5 팀 모집글 삭제 (정리) - 기본은 스킵하여 데이터 확인 가능
if ($teamId) {
    Write-Host "`n[5.5 팀 모집글 삭제] 예시만 표기 - 생성한 테스트 팀을 지우려면 주석을 해제하세요." -ForegroundColor Yellow
    # Invoke-Api -Method DELETE -Path "/api/teams/$teamId" -Auth -Title "5.5 팀 모집글 삭제"
}
