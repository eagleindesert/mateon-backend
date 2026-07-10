# 02_auth.ps1 - Auth (인증) API 테스트  /api/auth
# 사용법: powershell -ExecutionPolicy Bypass -File .\02_auth.ps1
# ex) powershell -ExecutionPolicy Bypass -File .\test\scripts\for-rest-api\02_auth.ps1 -Email me@dankook.ac.kr -BypassEmail

#
# 인증코드는 서버가 메일로 발송하는 값이라 자동화가 어렵다. 두 가지 방법:
#   1) -Code <6자리>   : 실제 메일로 받은 코드로 verify + signup 진행
#   2) -BypassEmail    : 메일 없이 DB 에 verified 행을 직접 넣어 signup 진행 (개발용)
#                        (docker 로 실행 중인 PostgreSQL 컨테이너 필요)
param(
    [string]$Email = "test1@dankook.ac.kr",   # 고정 이메일 (DB verified 행과 일치시켜야 함)
    [string]$Password = "Password1234",
    [string]$Name = "테스트유저",
    [string]$Code = "",       # 이메일로 수신한 6자리 인증코드
    [switch]$BypassEmail      # 메일 없이 DB 로 이메일 인증 우회 (개발용)
)
. "$PSScriptRoot\_common.ps1"

Write-Host "`n########## 2. Auth (인증) - /api/auth ##########" -ForegroundColor Magenta
Write-Host "테스트 이메일: $Email" -ForegroundColor Yellow

# 2.1 이메일 인증코드 요청
if ($BypassEmail) {
    Write-Host "`n[2.1 이메일 인증코드 요청] 스킵 (-BypassEmail) - DB 로 인증을 직접 처리합니다." -ForegroundColor Yellow
    Grant-EmailVerification -Email $Email | Out-Null
} else {
    Invoke-Api -Method POST -Path "/api/auth/email/request" -Title "2.1 이메일 인증코드 요청" -Body @{
        email = $Email
    }
}

# 2.2 이메일 인증코드 검증 (코드가 주어졌을 때만)
if ($Code) {
    Invoke-Api -Method POST -Path "/api/auth/email/verify" -Title "2.2 이메일 인증코드 검증" -Body @{
        email = $Email
        code  = $Code
    }
} elseif ($BypassEmail) {
    Write-Host "`n[2.2 이메일 인증코드 검증] 스킵 (-BypassEmail) - 이미 verified=true 로 처리됨." -ForegroundColor Yellow
} else {
    Write-Host "`n[2.2 이메일 인증코드 검증] 스킵 - 실행 시 -Code <6자리> 또는 -BypassEmail 을 사용하세요." -ForegroundColor Yellow
}

# 2.3 회원가입 (이메일 인증 선행 필요 - -Code 또는 -BypassEmail)
if ($Code -or $BypassEmail) {
    $signup = Invoke-Api -Method POST -Path "/api/auth/signup" -PassThru -Title "2.3 회원가입" -Body @{
        email           = $Email
        password        = $Password
        passwordConfirm = $Password
        name            = $Name
        campus          = "JUKJEON"
        college         = "SW융합대학"
        major           = "소프트웨어학과"
        grade           = "3학년"
    }
    if ($signup.data.accessToken) {
        Save-AccessToken $signup.data.accessToken
        Save-RefreshToken $signup.data.refreshToken
        Write-Host "  (i) 회원가입 토큰 저장 완료" -ForegroundColor Green
    }
} else {
    Write-Host "`n[2.3 회원가입] 스킵 - 이메일 인증(-Code) 또는 -BypassEmail 이 필요합니다." -ForegroundColor Yellow
}

# 2.4 로그인 (토큰 저장)
$login = Invoke-Api -Method POST -Path "/api/auth/login" -PassThru -Title "2.4 로그인" -Body @{
    email    = $Email
    password = $Password
}
if ($login.data.accessToken) {
    Save-AccessToken $login.data.accessToken
    Save-RefreshToken $login.data.refreshToken
    Write-Host "  (i) 로그인 토큰 저장 완료 -> 이후 인증 필요 테스트에서 재사용" -ForegroundColor Green
} else {
    Write-Host "  (!) 로그인 실패 - 계정이 없으면 위 회원가입(-Code)을 먼저 진행하세요." -ForegroundColor Yellow
}

# 2.4b [리팩터링 검증] accessToken 의 subject 가 email 이 아니라 userId(숫자)인지 확인.
#   A안 리팩터링(JWT subject: email -> userId)이 정상 반영됐는지 검증하는 핵심 체크.
$tokenForCheck = Get-AccessToken
if ($tokenForCheck) {
    $sub = Get-JwtSubject -Token $tokenForCheck
    Write-Host "`n[2.4b JWT subject 검증]" -ForegroundColor Cyan
    Assert-Test -Title "2.4b JWT subject 가 userId(숫자)" -Condition ($sub -match '^\d+$') -Detail "sub=$sub" | Out-Null
} else {
    Write-Host "`n[2.4b JWT subject 검증] 스킵 - 저장된 accessToken 없음." -ForegroundColor Yellow
}

# 2.5 토큰 갱신
$refresh = Get-RefreshToken
if ($refresh) {
    $refreshed = Invoke-Api -Method POST -Path "/api/auth/token/refresh" -PassThru -Title "2.5 토큰 갱신" -Body @{
        refreshToken = $refresh
    }
    if ($refreshed.data.accessToken) {
        Save-AccessToken $refreshed.data.accessToken
        Save-RefreshToken $refreshed.data.refreshToken
    }
} else {
    Write-Host "`n[2.5 토큰 갱신] 스킵 - 저장된 refreshToken 없음." -ForegroundColor Yellow
}

# 2.6 비밀번호 변경 (실제로 비밀번호가 바뀌므로 기본은 스킵)
Write-Host "`n[2.6 비밀번호 변경] 예시만 표기 - 실제 변경을 원하면 아래 주석을 해제하세요." -ForegroundColor Yellow
# Invoke-Api -Method POST -Path "/api/auth/password/change" -Title "2.6 비밀번호 변경" -Body @{
#     email              = $Email
#     currentPassword    = $Password
#     newPassword        = "NewPassword1234"
#     newPasswordConfirm = "NewPassword1234"
# }

# 2.7 로그아웃 (기본은 스킵 - 이후 인증 테스트를 위해 토큰 유지)
Write-Host "`n[2.7 로그아웃] 예시만 표기 - 실행하면 서버측 세션/토큰이 무효화될 수 있습니다." -ForegroundColor Yellow
# Invoke-Api -Method POST -Path "/api/auth/logout" -Title "2.7 로그아웃" -Body @{ email = $Email }
