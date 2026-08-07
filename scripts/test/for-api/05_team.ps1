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
# 사전 조건:
#   - 02_auth.ps1 로 유저 A 로그인 완료(.auth-token.txt 존재)
#   - 02_auth.ps1 이 유저 B 계정도 생성해 둠(이 스크립트는 B 를 '로그인만' 해서 지원자로 쓴다)
#
# 흐름: 팀 생성(A) -> 목록/상세 조회 -> 수정 -> 지원(B) -> 승인(A) -> 삭제
# 지원(apply)은 "다른 사용자"가 수행해야 하므로 유저 B 로 토큰을 전환해 실제 성공 경로까지 검증하고,
# 본인 팀 지원 / 중복 지원은 차단되는지 함께 확인한다.
param(
    [string]$UserBEmail,       # 지원자(B). 미지정 시 00_common 의 UserBEmail
    [string]$UserBPassword     # 지원자(B). 미지정 시 00_common 의 UserBPassword
)
. "$PSScriptRoot\00_common.ps1"

# param 미지정 시 00_common 의 2번째 유저(B) 기본값으로 채운다.
if (-not $UserBEmail)    { $UserBEmail    = $script:UserBEmail }
if (-not $UserBPassword) { $UserBPassword = $script:UserBPassword }

try {

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
    $detail = Invoke-Api -Method GET -Path "/api/teams/$teamId" -Auth -PassThru -Title "5.2 팀 모집글 상세 조회"

    # 5.2a~c 프론트가 명세를 보고 기대하는 필드가 실제로 내려오는지.
    #   한때 명세(docs/api-spec v7)에만 있고 서버에는 없는 필드가 있었다. 이름이 있는지를
    #   직접 확인해야 그 어긋남이 다시 조용히 생기지 않는다.
    $fields = @($detail.data.PSObject.Properties.Name)
    Assert-Test -Title "5.2a 상세 응답에 members 명단이 있다" `
        -Condition ($fields -contains 'members') `
        -Detail "members=$($detail.data.members.Count)명" | Out-Null
    Assert-Test -Title "5.2b 상세 응답에 myApplicationStatus 가 있다" `
        -Condition ($fields -contains 'myApplicationStatus') `
        -Detail "value=$($detail.data.myApplicationStatus)" | Out-Null
    Assert-Test -Title "5.2c 상세 응답에 isEnded 가 있다" `
        -Condition ($fields -contains 'isEnded') `
        -Detail "value=$($detail.data.isEnded)" | Out-Null

    # 5.2d 명단과 인원 수는 같은 출처에서 나온다. 어긋나면 둘 중 하나가 거짓이다.
    Assert-Test -Title "5.2d members 수 = currentMemberCount" `
        -Condition (@($detail.data.members).Count -eq [int]$detail.data.currentMemberCount) `
        -Detail ("명단 {0} / 인원 {1}" -f @($detail.data.members).Count, $detail.data.currentMemberCount) | Out-Null

    # 5.2e 갓 만든 팀의 팀원은 팀장 하나뿐이고, 그 한 명은 팀장으로 표시돼야 한다.
    Assert-Test -Title "5.2e 명단에 팀장이 isLeader=true 로 들어 있다" `
        -Condition ([bool](@($detail.data.members) | Where-Object { $_.isLeader })) `
        -Detail ("leader=" + (@($detail.data.members) | Where-Object { $_.isLeader } | ForEach-Object { $_.name }) -join ",") | Out-Null

    # 5.2f~g 최상위 boolean 의 키 이름. 자바 필드가 is- 로 시작해 Jackson 이 접두어를 떼고
    #   leader/recruiting 으로 나가는데, 프론트가 이미 이 이름으로 읽고 있어 이게 정본이다.
    #   isEnded 처럼 @JsonProperty 로 "고쳐" 놓고 싶어지지만 그러면 프론트가 조용히 깨진다.
    #   (실제로 한 번 바꿨다가 되돌렸다. 명단 쪽 members[].isLeader 와 이름이 다른 건 의도된 상태다.)
    #   TODO: 나중에 is 접두로 통일 예정 (docs/TODO.md). 프론트와 동시에 전환하고, 그때 이 두 assert 는
    #   지우지 말고 방향만 뒤집는다 — 통일 후엔 leader/recruiting 이 없어야 한다.
    Assert-Test -Title "5.2f 최상위 키가 leader 다 (isLeader 아님)" `
        -Condition (($fields -contains 'leader') -and -not ($fields -contains 'isLeader')) `
        -Detail ("leader={0} / isLeader키={1}" -f $detail.data.leader, ($fields -contains 'isLeader')) | Out-Null
    Assert-Test -Title "5.2g 최상위 키가 recruiting 이다 (isRecruiting 아님)" `
        -Condition (($fields -contains 'recruiting') -and -not ($fields -contains 'isRecruiting')) `
        -Detail ("recruiting={0} / isRecruiting키={1}" -f $detail.data.recruiting, ($fields -contains 'isRecruiting')) | Out-Null

    # 5.3 비로그인 상세 조회. 컨트롤러가 @SecurityRequirement(name = "") 로 약속한 동작인데
    #   SecurityConfig 에 permitAll 이 없어 실제로는 403 이었다. -Auth 를 빼서 토큰 없이 부른다.
    $anon = Invoke-Api -Method GET -Path "/api/teams/$teamId" -PassThru -Title "5.3 비로그인 상세 조회"
    Assert-Test -Title "5.3a 토큰 없이도 상세 조회가 된다" `
        -Condition ([bool]($anon -and $anon.success -eq $true)) `
        -Detail "message=$($anon.message)" | Out-Null
    # 5.3b 명단은 조회자와 무관한 사실이라 비로그인에서도 그대로 나와야 한다.
    Assert-Test -Title "5.3b 비로그인에서도 members 가 온다" `
        -Condition (@($anon.data.members).Count -eq [int]$anon.data.currentMemberCount) `
        -Detail ("명단 {0} / 인원 {1}" -f @($anon.data.members).Count, $anon.data.currentMemberCount) | Out-Null
    # 5.3c 반대로 조회자 기준 필드는 기준이 될 사람이 없으니 false/null 이어야 한다.
    #   최상위는 leader 다 (isLeader 가 아니다) — 5.2f 참고.
    Assert-Test -Title "5.3c 비로그인이면 조회자 기준 필드가 false/null" `
        -Condition ((-not $anon.data.leader) -and (-not $anon.data.hasApplied) -and ($null -eq $anon.data.myApplicationStatus)) `
        -Detail ("leader={0} hasApplied={1} myApplicationStatus={2}" -f $anon.data.leader, $anon.data.hasApplied, $anon.data.myApplicationStatus) | Out-Null
    # 5.3d 목록도 같은 규약이다.
    $anonList = Invoke-Api -Method GET -Path "/api/teams" -PassThru -Title "5.3d 비로그인 목록 조회"
    Assert-Test -Title "5.3d 토큰 없이도 목록 조회가 된다" `
        -Condition ([bool]($anonList -and $anonList.success -eq $true)) `
        -Detail ("{0}건" -f @($anonList.data).Count) | Out-Null

    # 5.3e 열린 것은 조회뿐이다. 작성은 계속 막혀야 한다 — permitAll 을 GET 으로 한정한 이유고,
    #   "/api/teams/*" 를 ** 로 넓히면 지원서·제안까지 새는지도 여기서 잡힌다.
    $anonWrite = Invoke-Api -Method POST -Path "/api/teams" -PassThru `
        -Title "5.3e 비로그인 작성 - 차단 기대" -Body @{ title = "비로그인 작성 시도"; capacity = 2 }
    Assert-Test -Title "5.3e 비로그인 작성은 여전히 차단" `
        -Condition ([bool]($anonWrite -and $anonWrite.success -ne $true)) `
        -Detail "message=$($anonWrite.message)" | Out-Null
    $anonApps = Invoke-Api -Method GET -Path "/api/teams/$teamId/applications" -PassThru `
        -Title "5.3f 비로그인 지원서 목록 - 차단 기대"
    Assert-Test -Title "5.3f 비로그인 지원서 목록은 여전히 차단" `
        -Condition ([bool]($anonApps -and $anonApps.success -ne $true)) `
        -Detail "message=$($anonApps.message)" | Out-Null
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

# ============================================================================
#  지원(apply) 시나리오: 팀장 = 유저 A(현재 토큰), 지원자 = 유저 B
#  02_auth.ps1 이 유저 B 계정을 이미 만들어 두므로 여기서는 '로그인만' 하면 된다.
#  (이메일 가입 유저는 signup 시점에 schoolVerified=true 라 지원 게이팅에 걸리지 않는다)
# ============================================================================
$tokenA = Get-AccessToken

Write-Host "`n[5.6 사전] 지원자(유저 B) 로그인: $UserBEmail" -ForegroundColor Cyan
$loginB = Invoke-Api -Method POST -Path "/api/auth/login" -PassThru -Title "5.6 지원자(B) 로그인" -Body @{
    email = $UserBEmail; password = $UserBPassword
}
$tokenB = $loginB.data.accessToken
Save-AccessToken $tokenA   # B 로그인 응답이 파일을 건드리지 않지만 확실히 A 로 유지
if (-not $tokenB) {
    Write-Host "(!) 유저 B 로그인 실패 - 먼저 .\auth\02_auth.ps1 로 유저 B 계정을 생성하세요." -ForegroundColor Red
    Write-Host "    (또는 -UserBEmail/-UserBPassword 로 이미 존재하는 계정을 지정하세요.)" -ForegroundColor Red
}

$applicationId = $null

# 5.6a 본인 팀 지원 차단 (A 가 자기 팀에 지원 → 400 이어야 정상)
if ($teamId) {
    Write-Host "`n[5.6a 본인 팀 지원] 팀장 본인이 지원 → 차단(거절)될 것으로 기대합니다." -ForegroundColor Yellow
    $self = Invoke-Api -Method POST -Path "/api/teams/$teamId/apply" -Auth -PassThru -Title "5.6a 본인 팀 지원 (차단 기대)" -Body @{
        introduction  = "간단 소개입니다."
        message       = "지원 동기입니다."
        contactNumber = "010-1234-5678"
        portfolioUrl  = "https://github.com/example"
    }
    $blocked  = [bool]($self -and ($self.success -eq $false))
    $blockMsg = if ($self) { $self.message } else { $null }
    Assert-Test -Title "5.6a 본인 팀 지원 차단" -Condition $blocked -Detail "message=$blockMsg" | Out-Null
}

# 5.6b 팀 지원하기 (B → A 의 팀) : 정상 성공 경로
if ($teamId -and $tokenB) {
    Save-AccessToken $tokenB
    $applied = Invoke-Api -Method POST -Path "/api/teams/$teamId/apply" -Auth -PassThru -Title "5.6b 팀 지원하기 (B → A 팀)" -Body @{
        introduction  = "안녕하세요, 백엔드 지원합니다."
        message       = "함께 성장하고 싶어 지원합니다."
        contactNumber = "010-1234-5678"
        portfolioUrl  = "https://github.com/example"
    }
    Assert-Test -Title "5.6b 타인 팀 지원 성공" -Condition ([bool]($applied -and $applied.success -eq $true)) `
        -Detail "message=$($applied.message)" | Out-Null

    # 중복 지원은 막혀야 한다 (이미 지원한 팀입니다 → 400)
    $dup = Invoke-Api -Method POST -Path "/api/teams/$teamId/apply" -Auth -PassThru -Title "5.6c 중복 지원 (차단 기대)" -Body @{
        introduction  = "중복 지원 시도"
        message       = "중복 지원 시도"
        contactNumber = "010-1234-5678"
        portfolioUrl  = "https://github.com/example"
    }
    Assert-Test -Title "5.6c 중복 지원 차단" -Condition ([bool]($dup -and $dup.success -eq $false)) `
        -Detail "message=$($dup.message)" | Out-Null

    # 5.7 내가 쓴 지원서 목록 (지원자 B)
    $myApps = Invoke-Api -Method GET -Path "/api/teams/applications/me" -Auth -PassThru -Title "5.7 내가 쓴 지원서 목록 (B)"
    if ($myApps.data) {
        $mine = @($myApps.data | Where-Object { $_.teamId -eq $teamId })[0]
        if ($mine) { $applicationId = $mine.applicationId }
    }
    Assert-Test -Title "5.7 B 지원서 목록에 방금 지원한 팀 포함" -Condition ([bool]$applicationId) `
        -Detail "applicationId=$applicationId" | Out-Null

    # 5.10 지원서 수정 (지원자 B 본인)
    if ($applicationId) {
        Invoke-Api -Method PUT -Path "/api/teams/applications/$applicationId" -Auth -Title "5.10 지원서 수정 (지원자 B)" -Body @{
            introduction  = "수정된 소개"
            message       = "수정된 지원 동기"
            contactNumber = "010-9999-8888"
            portfolioUrl  = "https://github.com/example2"
        }
    }

    Save-AccessToken $tokenA   # 이후 팀장(A) 관점 테스트
}

# 5.8 내 팀에 온 지원서 목록 (팀장 A)
if ($teamId) {
    $teamApps = Invoke-Api -Method GET -Path "/api/teams/$teamId/applications" -Auth -PassThru -Title "5.8 내 팀에 온 지원서 목록 (팀장 A)"
    Assert-Test -Title "5.8 팀장이 B 의 지원서를 확인" -Condition ([bool]($teamApps.data -and $teamApps.data.Count -ge 1)) `
        -Detail "count=$($teamApps.data.Count)" | Out-Null

    # 5.8b 지원자 상세 프로필로 넘어갈 id 가 응답에 남아 있는지.
    #   팀장은 이 id 로 GET /api/users/{userId} 를 불러 지원자의 공개 프로필을 연다.
    #
    #   [알려진 부채] applicant 에는 아직 UserResponse 가 통째로 실려 지원자의 email/schoolEmail
    #   까지 함께 나간다. 좁히면 프론트 계약이 깨지므로 합의 전까지 유지한다 —
    #   자세한 사정은 TeamApplicationResponseDTO.applicant 의 주석 참고.
    if ($teamApps.data -and $teamApps.data.Count -gt 0) {
        Assert-Test -Title "5.8b 지원자 프로필로 넘어갈 id 가 남아 있다" `
            -Condition ([bool]$teamApps.data[0].applicant.id) `
            -Detail "applicant.id = $($teamApps.data[0].applicant.id)" | Out-Null
    }

    if (-not $applicationId -and $teamApps.data -and $teamApps.data.Count -gt 0) {
        $applicationId = $teamApps.data[0].applicationId
    }
}

if ($applicationId) {
    Write-Host "  (i) 대상 applicationId = $applicationId" -ForegroundColor Green

    # 5.12 지원서 상세 조회 (팀장 A - 열람 권한 있음)
    Invoke-Api -Method GET -Path "/api/teams/applications/$applicationId" -Auth -Title "5.12 지원서 상세 조회 (팀장 A)"

    # 5.9 지원서 승인 (팀장 A)
    $beforeApprove = Invoke-Api -Method GET -Path "/api/teams/$teamId" -Auth -PassThru -Title "5.9 (사전) 승인 전 팀 상태"
    $countBefore = [int]$beforeApprove.data.currentMemberCount

    $approved = Invoke-Api -Method PATCH -Path "/api/teams/applications/$applicationId`?isApproved=true" -Auth -PassThru -Title "5.9 지원서 승인 (팀장 A)"
    Assert-Test -Title "5.9 팀장 승인 성공" -Condition ([bool]($approved -and $approved.success -eq $true)) `
        -Detail "message=$($approved.message)" | Out-Null

    # 5.9a 승인은 지원서 상태 변경이자 소속 생성이다. 명단에 실제로 들어와야 한다.
    $afterApprove = Invoke-Api -Method GET -Path "/api/teams/$teamId" -Auth -PassThru -Title "5.9a 승인 후 팀 상태"
    $countAfter = [int]$afterApprove.data.currentMemberCount
    Assert-Test -Title "5.9a 승인 즉시 인원 +1 (증분)" -Condition ($countAfter -eq $countBefore + 1) `
        -Detail ("{0}명 -> {1}명" -f $countBefore, $countAfter) | Out-Null
    Assert-Test -Title "5.9b 명단 수가 인원 수와 계속 일치" `
        -Condition (@($afterApprove.data.members).Count -eq $countAfter) `
        -Detail ("명단 {0} / 인원 {1}" -f @($afterApprove.data.members).Count, $countAfter) | Out-Null

    # 5.9c 승인/거절은 한 번뿐이다. 되돌리기를 허용하면 지원서만 REJECTED 가 되고
    #      팀원 소속은 활성인 채 남아 인원 수가 실제보다 커진다.
    $reprocess = Invoke-Api -Method PATCH -Path "/api/teams/applications/$applicationId`?isApproved=false" -Auth -PassThru `
        -Title "5.9c 처리된 지원서 재처리 - 차단 기대 (400 APPLICATION_ALREADY_PROCESSED)"
    Assert-Test -Title "5.9c 처리된 지원서 재처리 차단" `
        -Condition ([bool]($reprocess -and $reprocess.success -eq $false)) `
        -Detail "message=$($reprocess.message)" | Out-Null

    # 5.9d 차단됐으니 인원은 그대로여야 한다.
    $afterReprocess = Invoke-Api -Method GET -Path "/api/teams/$teamId" -Auth -PassThru -Title "5.9d 재처리 시도 후 팀 상태"
    Assert-Test -Title "5.9d 재처리 시도가 인원 수를 건드리지 않음" `
        -Condition ([int]$afterReprocess.data.currentMemberCount -eq $countAfter) `
        -Detail ("{0}명 유지" -f $afterReprocess.data.currentMemberCount) | Out-Null

    # 5.11 지원 취소 (지원자 B) - 기본은 스킵. 해제하려면 B 토큰으로 전환해야 한다.
    Write-Host "`n[5.11 지원 취소] 예시만 표기 - 실제 취소를 원하면 아래 주석을 해제하세요." -ForegroundColor Yellow
    # if ($tokenB) {
    #     Save-AccessToken $tokenB
    #     Invoke-Api -Method DELETE -Path "/api/teams/applications/$applicationId" -Auth -Title "5.11 지원 취소 (지원자 B)"
    #     Save-AccessToken $tokenA
    # }
} else {
    Write-Host "`n[5.9~5.12 지원서 관련] 스킵 - 조회된 지원서(applicationId)가 없습니다." -ForegroundColor Yellow
}

# 저장 토큰을 유저 A 로 확정 복구 (이후 스크립트가 A 세션을 이어 쓰도록)
if ($tokenA) { Save-AccessToken $tokenA }

# 5.5 팀 모집글 삭제 (정리) - 기본은 스킵하여 데이터 확인 가능
if ($teamId) {
    Write-Host "`n[5.5 팀 모집글 삭제] 예시만 표기 - 생성한 테스트 팀을 지우려면 주석을 해제하세요." -ForegroundColor Yellow
    # Invoke-Api -Method DELETE -Path "/api/teams/$teamId" -Auth -Title "5.5 팀 모집글 삭제"
}

} finally {
    Write-TestSummary | Out-Null
}
