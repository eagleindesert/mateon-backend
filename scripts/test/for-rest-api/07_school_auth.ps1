# 07_school_auth.ps1 - 학교(재학생) 인증 & 게이팅 라운드트립 테스트  [인증 필요]
# 사용법: powershell -ExecutionPolicy Bypass -File .\07_school_auth.ps1
#
# B단계(소셜 우선 → 학교 인증 후행) 리팩터링 검증:
#   1) 로그인한 유저를 '미인증(소셜만 로그인)' 상태로 강제(Set-SchoolVerified -Verified:$false)
#   2) 학생 전용 쓰기(팀 생성) → SCHOOL_NOT_VERIFIED 로 차단되는지 확인 (게이팅)
#   3) 학교 이메일 인증코드를 DB 에 심고 /api/auth/school/email/verify 로 인증 → 재학생 확정
#   4) 프로필에 schoolVerified=true 노출 확인
#   5) 다시 팀 생성 → 이번엔 허용되는지 확인 (게이팅 해제)
#
# 사전 조건:
#   - 02_auth.ps1 로 로그인하여 .auth-token.txt 존재
#   - 로컬 PostgreSQL 도커 컨테이너 접근 가능(Set-SchoolVerified/Grant-SchoolEmailCode 가 psql 사용).
#     원격 VM 대상(BaseUrl 이 원격)일 때는 DB 헬퍼가 로컬 컨테이너를 보므로 이 스크립트는 스킵된다.
param(
    [string]$Email   # 미지정 시 00_common 의 TestEmail (02_auth 기본 이메일과 일치)
)
. "$PSScriptRoot\00_common.ps1"

# param 미지정 시 00_common 의 테스트 계정 기본값으로 채운다.
if (-not $Email) { $Email = $script:TestEmail }

Write-Host "`n########## 7. School Auth & Gating - /api/auth/school [인증 필요] ##########" -ForegroundColor Magenta

if (-not (Get-AccessToken)) {
    Write-Host "(!) accessToken 이 없습니다. 먼저 .\02_auth.ps1 을 실행하세요." -ForegroundColor Red
    return
}

# 마지막 API 호출의 HTTP 상태(문자열)를 집계 트래커에서 읽어온다.
function Get-LastStatus {
    if ($global:MateonTestResults.Count -eq 0) { return $null }
    return $global:MateonTestResults[$global:MateonTestResults.Count - 1].Status
}

$teamBody = @{
    eventId              = $null
    title                = "학교인증 게이팅 테스트 팀 $((Get-Random -Maximum 9999))"
    promotionText        = "게이팅 검증용 팀"
    role                 = @("백엔드")
    characteristic       = "테스트"
    capacity             = 4
    recruitmentStartDate = (Get-Date).ToString("yyyy-MM-dd")
    recruitmentEndDate   = (Get-Date).AddDays(30).ToString("yyyy-MM-dd")
}

# --- 1) 미인증(소셜만 로그인) 상태 재현 ---
Write-Host "`n[7.1 미인증 상태 재현]" -ForegroundColor Cyan
if (-not (Set-SchoolVerified -Email $Email -Verified:$false)) {
    Write-Host "(!) DB 접근 불가로 학교인증 게이팅 테스트를 스킵합니다. (로컬 도커 DB 필요)" -ForegroundColor Yellow
    return
}

# --- 2) 게이팅: 미인증 유저의 팀 생성은 차단돼야 함 ---
Invoke-Api -Method POST -Path "/api/teams" -Auth -Title "7.2 (미인증) 팀 생성 시도 → 차단 기대" -Body $teamBody | Out-Null
$blockedStatus = Get-LastStatus
Assert-Test -Title "7.2 미인증 상태에서 팀 생성 차단(4xx)" `
    -Condition ($blockedStatus -and [int]$blockedStatus -ge 400) `
    -Detail "status=$blockedStatus (SCHOOL_NOT_VERIFIED 기대)" | Out-Null

# --- 3) 학교 이메일 인증 (코드 DB 심기 → verify) ---
Write-Host "`n[7.3 학교 이메일 인증]" -ForegroundColor Cyan
$schoolCode = "000000"
Grant-SchoolEmailCode -Email $Email -Code $schoolCode | Out-Null
Invoke-Api -Method POST -Path "/api/auth/school/email/verify" -Auth -Title "7.3 학교 이메일 인증 검증" -Body @{
    schoolEmail = $Email
    code        = $schoolCode
} | Out-Null
$verifyStatus = Get-LastStatus
Assert-Test -Title "7.3 학교 이메일 verify 성공(2xx)" `
    -Condition ($verifyStatus -and [int]$verifyStatus -lt 400) `
    -Detail "status=$verifyStatus" | Out-Null

# --- 4) 프로필에 schoolVerified=true 노출 확인 ---
$me = Invoke-Api -Method GET -Path "/api/users/me" -Auth -PassThru -Title "7.4 프로필 schoolVerified 확인"
Assert-Test -Title "7.4 프로필 schoolVerified=true" `
    -Condition ([bool]$me.data.schoolVerified) `
    -Detail "schoolVerified=$($me.data.schoolVerified), schoolEmail=$($me.data.schoolEmail)" | Out-Null

# --- 5) 게이팅 해제: 인증 후 팀 생성 허용 ---
$created = Invoke-Api -Method POST -Path "/api/teams" -Auth -PassThru -Title "7.5 (인증 후) 팀 생성 시도 → 허용 기대" -Body $teamBody
$allowedStatus = Get-LastStatus
Assert-Test -Title "7.5 인증 후 팀 생성 허용(2xx)" `
    -Condition ($allowedStatus -and [int]$allowedStatus -lt 400) `
    -Detail "status=$allowedStatus, teamId=$($created.data.id)" | Out-Null
