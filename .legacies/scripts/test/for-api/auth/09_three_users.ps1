# 09_three_users.ps1 (for-api-server) - 유저 3명(A/B/C) 준비 및 슬롯 토큰 확보
#
# 사용법: pwsh -File .\auth\09_three_users.ps1
#         pwsh -File .\auth\09_three_users.ps1 -ForceSignup   # 계정이 없다고 가정하고 가입부터
#         pwsh -File .\auth\09_three_users.ps1 -LoginOnly     # 로그인만 (프롬프트 없음 / 99_run_all 용)
#
# 왜 3명인가:
#   협업 온도 평가는 (1) 자기 자신을 평가할 수 없고 (2) 온도가 공개되려면 받은 평가가 2건 이상이어야
#   한다. 그래서 2명으로는 서로 1건씩만 주고받아 온도가 끝내 공개되지 않는다.
#   팀장(A) + 팀원(B, C) 세 명이 있어야 한 사람이 2건을 받아 온도가 실제로 뜬다.
#
# 02_auth.ps1 과의 차이:
#   02_auth 는 항상 email/request 부터 밟아 매번 수동 코드 입력을 요구한다. 이 스크립트는
#   [로그인 먼저] 시도해서 성공하면 가입 절차를 통째로 건너뛴다. 계정이 이미 있으면 코드 입력이
#   0번이라, 협업 온도 시나리오를 반복 실행하기 쉽다.
#
# 결과:
#   각 유저의 토큰이 슬롯(.auth-token-A/B/C.txt)에 저장된다. 이후 스크립트는
#   `Use-User A` 로 활성 세션을 갈아끼우며 여러 사람 입장에서 API 를 호출할 수 있다.
#   스크립트 종료 시 활성 세션은 A 로 맞춰 둔다(기존 스크립트들이 A 기준으로 동작).
param(
    [string]$EmailA,    [string]$PasswordA,    [string]$NameA,
    [string]$EmailB,    [string]$PasswordB,    [string]$NameB,
    [string]$EmailC,    [string]$PasswordC,    [string]$NameC,
    [switch]$ForceSignup,  # 로그인 선시도를 건너뛰고 곧장 가입 절차(수동 코드)로 간다
    [switch]$LoginOnly     # 로그인만 한다. 계정이 없어도 가입/코드 입력으로 넘어가지 않는다.
                           # 99_run_all 처럼 프롬프트가 뜨면 안 되는(무인 실행) 곳에서 쓴다.
)
. "$PSScriptRoot\..\00_common.ps1"

# param 미지정 시 00_common(.env) 기본값으로 채운다.
if (-not $EmailA)    { $EmailA    = $script:TestEmail }
if (-not $PasswordA) { $PasswordA = $script:TestPassword }
if (-not $NameA)     { $NameA     = $script:TestName }
if (-not $EmailB)    { $EmailB    = $script:UserBEmail }
if (-not $PasswordB) { $PasswordB = $script:UserBPassword }
if (-not $NameB)     { $NameB     = $script:UserBName }
if (-not $EmailC)    { $EmailC    = $script:UserCEmail }
if (-not $PasswordC) { $PasswordC = $script:UserCPassword }
if (-not $NameC)     { $NameC     = $script:UserCName }

Write-Host "`n########## 9. 유저 3명 준비 (협업 온도 시나리오용) ##########" -ForegroundColor Magenta

try {

# ----------------------------------------------------------------------------
# 한 계정을 '로그인 가능한 상태'로 만들고 토큰을 슬롯에 저장한다.
#   1) 로그인 시도 → 성공하면 끝 (코드 입력 불필요)
#   2) 실패하면 email/request → (수동 코드) → verify → signup → 로그인
# ----------------------------------------------------------------------------
function Initialize-UserSlot {
    param(
        [Parameter(Mandatory = $true)][string]$Slot,
        [Parameter(Mandatory = $true)][string]$Email,
        [Parameter(Mandatory = $true)][string]$Password,
        [Parameter(Mandatory = $true)][string]$Name
    )

    Write-Host "`n----------------------------------------------------------------------" -ForegroundColor DarkGray
    Write-Host "[유저 $Slot] $Email" -ForegroundColor Cyan

    # --- 1) 기존 계정으로 로그인 시도 (실패는 정상 경로라 집계에서 뺀다) ---
    if (-not $ForceSignup) {
        if (Connect-UserSlot -Slot $Slot -Email $Email -Password $Password) {
            Write-Host "  (i) 기존 계정으로 로그인 성공 - 가입 절차를 건너뜁니다." -ForegroundColor Green
            Assert-Test -Title "9.$Slot 유저 $Slot 로그인" -Condition $true -Detail "email=$Email (기존 계정)" | Out-Null
            return $true
        }
        Write-Host "  (i) 로그인 실패." -ForegroundColor Yellow
    }

    # -LoginOnly 면 여기서 멈춘다. 가입 절차는 수동 코드 입력을 요구하는데, 무인 실행(99_run_all)
    # 중에 프롬프트가 뜨면 러너 전체가 멈춰버린다.
    if ($LoginOnly) {
        Write-Host "  (i) -LoginOnly - 가입 절차를 건너뜁니다. 계정 생성이 필요하면 이 스크립트를 단독 실행하세요." -ForegroundColor Yellow
        Assert-Test -Title "9.$Slot 유저 $Slot 로그인" -Condition $false -Detail "email=$Email (계정 없음/로그인 실패)" | Out-Null
        return $false
    }

    # --- 2) 신규 가입: request → (수동 코드) → verify → signup ---
    Invoke-Api -Method POST -Path "/api/auth/email/request" -Title "9.$Slot 이메일 인증코드 요청" -Body @{
        email = $Email
    } | Out-Null

    $code = Get-EmailVerificationCode -Email $Email
    if (-not $code) {
        Write-Host "  (!) 코드 미입력 - 유저 $Slot 준비를 중단합니다." -ForegroundColor Red
        Assert-Test -Title "9.$Slot 유저 $Slot 로그인" -Condition $false -Detail "인증코드 미입력" | Out-Null
        return $false
    }

    $verify = Invoke-Api -Method POST -Path "/api/auth/email/verify" -PassThru -Title "9.$Slot 이메일 인증코드 검증" -Body @{
        email = $Email; code = $code
    }
    $verificationToken = $verify.data.verificationToken
    if (-not $verificationToken) {
        Assert-Test -Title "9.$Slot 유저 $Slot 로그인" -Condition $false -Detail "인증 티켓 확보 실패" | Out-Null
        return $false
    }

    # 이메일 가입 유저는 signup 시점에 schoolVerified=true 가 된다.
    # 팀 생성/지원이 학교 인증을 요구하므로(TeamService.requireSchoolVerified) 이 점이 중요하다 —
    # 덕분에 협업 온도 시나리오에서 07_school_auth 를 따로 돌릴 필요가 없다.
    $signup = Invoke-Api -Method POST -Path "/api/auth/signup" -PassThru -Title "9.$Slot 회원가입" -Body @{
        email             = $Email
        password          = $Password
        passwordConfirm   = $Password
        name              = $Name
        campus            = "죽전"
        college           = "SW융합대학"
        major             = "소프트웨어학과"
        grade             = "3학년"
        verificationToken = $verificationToken
    }

    $access  = $signup.data.accessToken
    $refresh = $signup.data.refreshToken

    # 가입이 EMAIL_ALREADY_EXISTS 등으로 실패했어도 로그인은 될 수 있다.
    if (-not $access) {
        $login = Invoke-Api -Method POST -Path "/api/auth/login" -PassThru -Title "9.$Slot 로그인" -Body @{
            email = $Email; password = $Password
        }
        $access  = $login.data.accessToken
        $refresh = $login.data.refreshToken
    }

    if ($access) {
        Save-UserSlot -Slot $Slot -AccessToken $access -RefreshToken $refresh
    } else {
        # 가입까지 실패했으면 슬롯을 확실히 비운다 (지난 실행의 토큰이 남으면 안 된다).
        Clear-UserSlot -Slot $Slot
    }

    Assert-Test -Title "9.$Slot 유저 $Slot 로그인" -Condition ([bool]$access) -Detail "email=$Email" | Out-Null
    return [bool]$access
}

# ----------------------------------------------------------------------------
#  실행
# ----------------------------------------------------------------------------
$emails = @($EmailA, $EmailB, $EmailC)
$missing = @($emails | Where-Object { -not $_ })
if ($missing.Count -gt 0) {
    Write-Host "`n(!) 유저 3명의 이메일이 모두 필요합니다. .env 에 아래를 지정하세요:" -ForegroundColor Red
    Write-Host "    MATEON_TEST_EMAIL / MATEON_USERB_EMAIL / MATEON_USERC_EMAIL" -ForegroundColor Red
    return
}

$dupes = @($emails | Group-Object | Where-Object { $_.Count -gt 1 })
if ($dupes.Count -gt 0) {
    Write-Host "`n(!) 세 유저의 이메일이 서로 달라야 합니다. 중복: $($dupes.Name -join ', ')" -ForegroundColor Red
    return
}

$okA = Initialize-UserSlot -Slot "A" -Email $EmailA -Password $PasswordA -Name $NameA
$okB = Initialize-UserSlot -Slot "B" -Email $EmailB -Password $PasswordB -Name $NameB
$okC = Initialize-UserSlot -Slot "C" -Email $EmailC -Password $PasswordC -Name $NameC

# --- 검증: 세 유저가 실제로 서로 다른 계정인가 ---
# 같은 계정이 두 슬롯에 들어가면 "자기 자신 평가 불가" 때문에 시나리오가 조용히 깨진다.
# 그래서 토큰 유무가 아니라 JWT subject(userId)로 확인한다.
Write-Host "`n[9.4 슬롯 검증]" -ForegroundColor Cyan
$idA = Get-SlotUserId "A"; $idB = Get-SlotUserId "B"; $idC = Get-SlotUserId "C"
Write-Host "  userId: A=$idA, B=$idB, C=$idC" -ForegroundColor DarkGray

Assert-Test -Title "9.4 유저 3명 토큰 확보" `
    -Condition ($okA -and $okB -and $okC) `
    -Detail "A=$okA, B=$okB, C=$okC" | Out-Null

$allIds = @($idA, $idB, $idC) | Where-Object { $_ }
Assert-Test -Title "9.5 세 유저가 서로 다른 계정" `
    -Condition ($allIds.Count -eq 3 -and (($allIds | Select-Object -Unique).Count -eq 3)) `
    -Detail "userIds=$($allIds -join ', ')" | Out-Null

# --- 활성 세션을 A 로 맞춰 둔다 (기존 스크립트들이 A 기준으로 동작) ---
if ($okA) {
    Use-User "A" | Out-Null
    Write-Host "`n[정리] 활성 세션 = 유저 A. 다른 유저로 호출하려면 스크립트에서 Use-User 'B' 를 부르세요." -ForegroundColor DarkGray
}

} finally {
    Write-TestSummary | Out-Null
}
