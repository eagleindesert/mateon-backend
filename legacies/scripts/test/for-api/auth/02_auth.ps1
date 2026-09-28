# 02_auth.ps1 (for-api-server) - Auth (인증) API 테스트  /api/auth
# 사용법: powershell -ExecutionPolicy Bypass -File .\auth\02_auth.ps1
#
# 원격 서버 판: 인증코드를 docker(DB)로 자동 조회하지 않고 사람이 직접 입력한다.
#   코드는 서버가 보낸 메일에서 확인하거나, 원격 DB(pgAdmin/psql)의 email_verifications.code 를 읽는다.
#
# 이 스크립트는 실제 엔드포인트(request→verify→signup)를 그대로 밟으며, 유저 A 와 유저 B(채팅 상대)를
# 모두 생성한다. 그래서 이후 10_chat.ps1 은 B 를 로그인만 하면 되고 코드 입력이 필요 없다.
#   - 유저 A: request → (수동 코드) → verify → signup → login → 토큰 저장 (활성 세션)
#   - 유저 B: request → (수동 코드) → verify → signup (계정 존재 보장, 토큰은 저장하지 않음)
#   - 이미 가입된 계정이면 signup 이 실패해도 무시하고 진행한다(로그인으로 이어짐).
param(
    [string]$Email,           # 유저 A: 미지정 시 00_common 의 TestEmail
    [string]$Password,        # 유저 A: 미지정 시 00_common 의 TestPassword
    [string]$Name,            # 유저 A: 미지정 시 00_common 의 TestName
    [string]$UserBEmail,      # 유저 B: 미지정 시 00_common 의 UserBEmail
    [string]$UserBPassword,   # 유저 B: 미지정 시 00_common 의 UserBPassword
    [string]$UserBName,       # 유저 B: 미지정 시 00_common 의 UserBName
    [switch]$SkipUserB        # 지정 시 유저 B 생성을 건너뛴다(유저 A 만 준비)
)
. "$PSScriptRoot\..\00_common.ps1"

# param 미지정 시 00_common 의 기본값으로 채운다.
if (-not $Email)         { $Email         = $script:TestEmail }
if (-not $Password)      { $Password      = $script:TestPassword }
if (-not $Name)          { $Name          = $script:TestName }
if (-not $UserBEmail)    { $UserBEmail    = $script:UserBEmail }
if (-not $UserBPassword) { $UserBPassword = $script:UserBPassword }
if (-not $UserBName)     { $UserBName     = $script:UserBName }

Write-Host "`n########## 2. Auth (인증) - /api/auth ##########" -ForegroundColor Magenta

try {

# 한 계정을 request→(수동 코드)→verify→signup 으로 생성한다. 성공 시 accessToken 반환(없으면 $null).
#   -SaveTokens 를 주면 발급 토큰을 파일에 저장한다(유저 A 용). B 는 저장하지 않는다.
function New-AccountViaEmailVerify {
    param(
        [Parameter(Mandatory = $true)][string]$Email,
        [Parameter(Mandatory = $true)][string]$Password,
        [Parameter(Mandatory = $true)][string]$Name,
        [string]$Label = "",
        [switch]$SaveTokens
    )
    Write-Host "`n[$Label 계정 준비] $Email" -ForegroundColor Cyan

    # 1) 이메일 인증코드 요청 (서버가 6자리 코드를 생성해 메일 발송 + email_verifications 저장)
    Invoke-Api -Method POST -Path "/api/auth/email/request" -Title "$Label 이메일 인증코드 요청" -Body @{
        email = $Email
    } | Out-Null

    # 2) 인증코드 확보 (수동 입력) → verify → 일회용 티켓(verificationToken)
    $code = Get-EmailVerificationCode -Email $Email
    $verificationToken = ""
    if ($code) {
        $verify = Invoke-Api -Method POST -Path "/api/auth/email/verify" -PassThru -Title "$Label 이메일 인증코드 검증" -Body @{
            email = $Email
            code  = $code
        }
        $verificationToken = $verify.data.verificationToken
        if ($verificationToken) {
            Write-Host "  (i) 인증 티켓 확보: $verificationToken" -ForegroundColor Green
        }
    } else {
        Write-Host "  ($Label) 인증코드 미확보로 verify/signup 을 건너뜁니다. (이미 가입된 계정이면 로그인은 가능)" -ForegroundColor Yellow
    }

    # 3) 회원가입 (인증 티켓 필요). 이미 있으면 EMAIL_ALREADY_EXISTS 로 실패해도 무시.
    $accessToken = $null
    if ($verificationToken) {
        $signup = Invoke-Api -Method POST -Path "/api/auth/signup" -PassThru -Title "$Label 회원가입" -Body @{
            email             = $Email
            password          = $Password
            passwordConfirm   = $Password
            name              = $Name
            campus            = "JUKJEON"
            college           = "SW융합대학"
            major             = "소프트웨어학과"
            grade             = "3학년"
            verificationToken = $verificationToken
        }
        if ($signup.data.accessToken) {
            $accessToken = $signup.data.accessToken
            if ($SaveTokens) {
                Save-AccessToken $signup.data.accessToken
                Save-RefreshToken $signup.data.refreshToken
                Write-Host "  (i) 회원가입 토큰 저장 완료" -ForegroundColor Green
            }
        }
    }
    return $accessToken
}

Write-Host "테스트 이메일(A): $Email" -ForegroundColor Yellow

# ============================================================================
#  유저 A 준비: request → (수동 코드) → verify → signup → login (토큰 저장)
# ============================================================================
New-AccountViaEmailVerify -Email $Email -Password $Password -Name $Name -Label "2.A" -SaveTokens | Out-Null

# 2.4 로그인 (토큰 저장) — 가입을 건너뛴 경우에도 기존 계정으로 로그인해 A 세션을 확정한다.
$login = Invoke-Api -Method POST -Path "/api/auth/login" -PassThru -Title "2.4 로그인 (A)" -Body @{
    email    = $Email
    password = $Password
}
if ($login.data.accessToken) {
    Save-AccessToken $login.data.accessToken
    Save-RefreshToken $login.data.refreshToken
    Write-Host "  (i) 로그인 토큰 저장 완료 -> 이후 인증 필요 테스트에서 재사용" -ForegroundColor Green
} else {
    Write-Host "  (!) 로그인 실패 - 인증코드 입력/회원가입이 정상 진행됐는지 확인하세요." -ForegroundColor Yellow
}

# 2.4b [리팩터링 검증] accessToken 의 subject 가 email 이 아니라 userId(숫자)인지 확인.
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

# 저장된(활성) A 토큰을 잠시 보관해 두었다가, B 준비가 끝나면 A 로 복구한다.
$tokenA = Get-AccessToken

# ============================================================================
#  유저 B 준비: request → (수동 코드) → verify → signup (계정 존재만 보장, 토큰 저장 안 함)
#  10_chat.ps1 은 여기서 만들어진 B 를 로그인만 해서 사용한다.
# ============================================================================
if ($SkipUserB) {
    Write-Host "`n[2.B 유저 B 준비] 스킵(-SkipUserB) - 유저 A 만 준비했습니다." -ForegroundColor Yellow
} else {
    if ($UserBEmail -eq $Email) {
        Write-Host "`n(!) 유저 B 이메일이 A 와 같습니다. -UserBEmail 을 A 와 다른 값으로 지정하세요. (B 준비 스킵)" -ForegroundColor Red
    } else {
        Write-Host "`n테스트 이메일(B): $UserBEmail" -ForegroundColor Yellow
        New-AccountViaEmailVerify -Email $UserBEmail -Password $UserBPassword -Name $UserBName -Label "2.B" | Out-Null

        # B 가 로그인 가능한지 확인(가입 성공 또는 기존 계정). 토큰은 저장하지 않는다.
        $loginB = Invoke-Api -Method POST -Path "/api/auth/login" -PassThru -Title "2.B 로그인 확인 (B)" -Body @{
            email    = $UserBEmail
            password = $UserBPassword
        }
        Assert-Test -Title "2.B 유저 B 로그인 가능(채팅 상대 준비)" `
            -Condition ([bool]$loginB.data.accessToken) `
            -Detail "email=$UserBEmail" | Out-Null
    }
}

# ============================================================================
#  활성 세션을 유저 A 로 복구 (B 로그인 확인이 파일을 건드리지 않았지만 확실히 복구)
# ============================================================================
if ($tokenA) {
    Save-AccessToken $tokenA
    Write-Host "`n[정리] 저장 토큰을 유저 A 로 복구했습니다. (이후 인증 스크립트는 A 로 동작)" -ForegroundColor DarkGray
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

} finally {
    Write-TestSummary | Out-Null
}
