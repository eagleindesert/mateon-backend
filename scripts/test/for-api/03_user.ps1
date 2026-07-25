# 03_user.ps1 - User (사용자) API 테스트  /api/users  [인증 필요]
# 사용법: powershell -ExecutionPolicy Bypass -File .\03_user.ps1
# 사전 조건: 02_auth.ps1 로 로그인하여 .auth-token.txt 가 생성되어 있어야 한다.
. "$PSScriptRoot\00_common.ps1"

try {

Write-Host "`n########## 3. User (사용자) - /api/users [인증 필요] ##########" -ForegroundColor Magenta

if (-not (Get-AccessToken)) {
    Write-Host "(!) accessToken 이 없습니다. 먼저 .\auth\02_auth.ps1 을 실행하세요." -ForegroundColor Red
    return
}

# 3.1 내 프로필 조회
$profile = Invoke-Api -Method GET -Path "/api/users/me" -Auth -PassThru -Title "3.1 내 프로필 조회"

# 3.1b [B단계 검증] 로컬 가입 유저는 학교 이메일로 선행 인증했으므로 schoolVerified=true 여야 한다.
Write-Host "`n[3.1b 학교 인증 상태 검증]" -ForegroundColor Cyan
Assert-Test -Title "3.1b 로컬 유저 schoolVerified=true" `
    -Condition ([bool]$profile.data.schoolVerified) `
    -Detail "schoolVerified=$($profile.data.schoolVerified), schoolEmail=$($profile.data.schoolEmail)" | Out-Null

# 3.2 내 프로필 수정
Invoke-Api -Method PUT -Path "/api/users/me" -Auth -Title "3.2 내 프로필 수정" -Body @{
    name               = "수정된이름"
    campus             = "JUKJEON"
    college            = "SW융합대학"
    major              = "소프트웨어학과"
    grade              = "4학년"
    interestJobPrimary = "백엔드 개발자"
    tagline            = "안녕하세요, 반갑습니다."
}

# 3.3 마이페이지 조회
Invoke-Api -Method GET -Path "/api/users/mypage" -Auth -Title "3.3 마이페이지 조회"

# 3.4 비밀번호 변경 (실제 변경되므로 기본은 스킵)
Write-Host "`n[3.4 비밀번호 변경] 예시만 표기 - 실제 변경을 원하면 아래 주석을 해제하세요." -ForegroundColor Yellow
# Invoke-Api -Method POST -Path "/api/users/password/change" -Auth -Title "3.4 비밀번호 변경" -Body @{
#     currentPassword    = "Password1234"
#     newPassword        = "NewPassword1234"
#     newPasswordConfirm = "NewPassword1234"
# }

# ==== 3.5 타인 프로필 조회 (GET /api/users/{userId}) ====
#
#   프론트가 추천 목록/팀 상세/역제안/DM 어디서 얻은 userId 든 이 경로 하나로 프로필을 연다.
#   여기서 지키는 계약의 핵심은 "남의 이메일이 절대 실리지 않는다" 이다 — 로그인만 하면 누구나
#   부를 수 있는 API 라서, 이메일이 실리면 userId 를 훑는 것만으로 연락처가 수집된다.
Write-Host "`n[3.5 타인 프로필 조회]" -ForegroundColor Cyan

$myId = Get-JwtSubject -Token (Get-AccessToken)

# 상대는 슬롯 B 를 쓴다. 09_three_users 를 안 돌렸으면 조용히 자기 자신으로 대체한다
# (그래도 이메일 부재 계약과 404 는 검증된다).
$targetId = Get-SlotUserId "B"
if (-not $targetId -or $targetId -eq $myId) {
    Write-Host "  (i) 슬롯 B 토큰이 없어 자기 프로필로 대체합니다. 타인 조회까지 보려면 .\auth\09_three_users.ps1 을 먼저 실행하세요." -ForegroundColor DarkYellow
    $targetId = $myId
}

$other = Invoke-Api -Method GET -Path "/api/users/$targetId" -Auth -PassThru -Title "3.5 타인 프로필 조회"

if ($other -and $other.data) {
    $keys = $other.data.PSObject.Properties.Name

    Assert-Test -Title "3.5a 조회한 userId 가 그대로 돌아온다" `
        -Condition ("$($other.data.userId)" -eq "$targetId") `
        -Detail "요청=$targetId, 응답=$($other.data.userId)" | Out-Null

    Assert-Test -Title "3.5b 공개 프로필에 이메일이 없다" `
        -Condition (-not ($keys -contains 'email' -or $keys -contains 'schoolEmail')) `
        -Detail "keys = $($keys -join ', ')" | Out-Null

    # 슬롯 미작성 유저도 null 이 아니라 빈 배열이어야 프론트가 그대로 map 할 수 있다.
    Assert-Test -Title "3.5c 희망역할/스킬이 배열로 내려온다 (미작성이어도 null 아님)" `
        -Condition (($keys -contains 'desiredRoles') -and ($null -ne $other.data.desiredRoles) `
                    -and ($keys -contains 'skills') -and ($null -ne $other.data.skills)) `
        -Detail "desiredRoles=$($other.data.desiredRoles -join '/'), skills=$($other.data.skills -join '/')" | Out-Null

    $expectMe = ("$targetId" -eq "$myId")
    Assert-Test -Title "3.5d isMe 가 본인 여부와 일치한다" `
        -Condition ([bool]$other.data.isMe -eq $expectMe) `
        -Detail "isMe=$($other.data.isMe), 기대=$expectMe" | Out-Null
}

# 자기 자신을 조회하면 isMe=true (프론트가 '프로필 수정' 버튼을 띄우는 근거)
$mine = Invoke-Api -Method GET -Path "/api/users/$myId" -Auth -PassThru -Title "3.5e 자기 프로필을 id 로 조회"
Assert-Test -Title "3.5e 자기 id 조회는 isMe=true" `
    -Condition ([bool]$mine.data.isMe) `
    -Detail "isMe=$($mine.data.isMe)" | Out-Null

# 없는 유저는 404 다 (400 이 아니다 — 요청이 틀린 게 아니라 그 사람이 없는 것이다)
Invoke-Api -Method GET -Path "/api/users/999999999" -Auth `
    -Title "3.5f 존재하지 않는 userId - 차단 기대 (404 USER_NOT_FOUND)" | Out-Null

} finally {
    Write-TestSummary | Out-Null
}
