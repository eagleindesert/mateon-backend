# 07_school_auth.ps1 (for-api-server) - 학교(재학생) 이메일 인증 라운드트립  [인증 필요]
# 사용법: powershell -ExecutionPolicy Bypass -File .\auth\07_school_auth.ps1
#
# 원격 서버 판: 정상 절차(request → 수동 코드 입력 → verify)로 학교 이메일 인증을 검증한다.
#   /api/auth/school/email/request { schoolEmail } → 서버가 코드 생성/발송(+ email_verifications 저장)
#   /api/auth/school/email/verify  { schoolEmail, code } → 재학생 확정(schoolVerified=true)
#
# [로컬 판과의 차이 / 스코프]
#   로컬 판은 유저를 DB 로 강제 '미인증' 상태로 만든 뒤(게이팅 차단 확인) → 코드를 DB 에 심어 verify 했다.
#   미인증 강제/코드 심기는 DB 쓰기가 필요해 원격 서버에서는 재현할 수 없으므로 여기서는 제외한다.
#   (미인증 유저의 팀 생성 게이팅 검증은 08_social_kakao.ps1 이 신규 카카오 유저로 이미 커버한다.)
#   또한 이메일 회원가입 유저는 signup 시점에 이미 schoolVerified=true 이므로, 이 스크립트는
#   학교 이메일 인증 엔드포인트가 정상 동작하는지를 라운드트립으로 확인하는 데 목적이 있다.
param(
    [string]$Email   # 학교 이메일(=인증 대상). 미지정 시 .env 의 MATEON_SCHOOL_EMAIL → 없으면 TestEmail
)
. "$PSScriptRoot\..\00_common.ps1"

# param 미지정 시 .env(MATEON_SCHOOL_EMAIL) → 그것도 없으면 테스트 계정(TestEmail) 순으로 폴백한다.
if (-not $Email) { $Email = $script:SchoolEmail }
if (-not $Email) { $Email = $script:TestEmail }

Write-Host "`n########## 7. School Email Auth - /api/auth/school [인증 필요] ##########" -ForegroundColor Magenta

if (-not (Get-AccessToken)) {
    Write-Host "(!) accessToken 이 없습니다. 먼저 .\auth\02_auth.ps1 을 실행하세요." -ForegroundColor Red
    return
}

# 마지막 API 호출의 HTTP 상태(문자열)를 집계 트래커에서 읽어온다.
function Get-LastStatus {
    if ($global:MateonTestResults.Count -eq 0) { return $null }
    return $global:MateonTestResults[$global:MateonTestResults.Count - 1].Status
}

# --- 1) 학교 이메일 인증코드 요청 (서버가 코드 생성/발송) ---
Write-Host "`n[7.1 학교 이메일 인증코드 요청]" -ForegroundColor Cyan
Invoke-Api -Method POST -Path "/api/auth/school/email/request" -Auth -Title "7.1 학교 이메일 인증코드 요청" -Body @{
    schoolEmail = $Email
} | Out-Null
$requestStatus = Get-LastStatus
Assert-Test -Title "7.1 학교 이메일 코드 요청 성공(2xx)" `
    -Condition ($requestStatus -and [int]$requestStatus -lt 400) `
    -Detail "status=$requestStatus" | Out-Null

# --- 2) 인증코드 확보(수동 입력) → verify ---
Write-Host "`n[7.2 학교 이메일 인증 검증]" -ForegroundColor Cyan
$schoolCode = Get-EmailVerificationCode -Email $Email
if (-not $schoolCode) {
    Write-Host "(!) 인증코드 미입력 - 학교 이메일 verify 이후 단계를 스킵합니다." -ForegroundColor Yellow
    return
}

Invoke-Api -Method POST -Path "/api/auth/school/email/verify" -Auth -Title "7.2 학교 이메일 인증 검증" -Body @{
    schoolEmail = $Email
    code        = $schoolCode
} | Out-Null
$verifyStatus = Get-LastStatus
Assert-Test -Title "7.2 학교 이메일 verify 성공(2xx)" `
    -Condition ($verifyStatus -and [int]$verifyStatus -lt 400) `
    -Detail "status=$verifyStatus" | Out-Null

# --- 3) 프로필에 schoolVerified=true 노출 확인 ---
$me = Invoke-Api -Method GET -Path "/api/users/me" -Auth -PassThru -Title "7.3 프로필 schoolVerified 확인"
Assert-Test -Title "7.3 프로필 schoolVerified=true" `
    -Condition ([bool]$me.data.schoolVerified) `
    -Detail "schoolVerified=$($me.data.schoolVerified), schoolEmail=$($me.data.schoolEmail)" | Out-Null

# --- 4) 재학생 확정 상태에서 학생 전용 쓰기(팀 생성) 허용 확인 ---
$teamBody = @{
    eventId              = $null
    title                = "학교인증 라운드트립 테스트 팀 $((Get-Random -Maximum 9999))"
    promotionText        = "학교 이메일 인증 검증용 팀"
    role                 = @("백엔드")
    characteristic       = "테스트"
    capacity             = 4
    recruitmentStartDate = (Get-Date).ToString("yyyy-MM-dd")
    recruitmentEndDate   = (Get-Date).AddDays(30).ToString("yyyy-MM-dd")
}
$created = Invoke-Api -Method POST -Path "/api/teams" -Auth -PassThru -Title "7.4 (인증 후) 팀 생성 → 허용 기대" -Body $teamBody
$allowedStatus = Get-LastStatus
Assert-Test -Title "7.4 인증 후 팀 생성 허용(2xx)" `
    -Condition ($allowedStatus -and [int]$allowedStatus -lt 400) `
    -Detail "status=$allowedStatus, teamId=$($created.data.id)" | Out-Null
