# launch.ps1 - 두 유저(A·B)의 실시간 채팅을 '두 개의 PowerShell 창'으로 띄운다.
# ----------------------------------------------------------------------------
# 사용법:
#   powershell -ExecutionPolicy Bypass -File .\launch.ps1
#
# 사전 조건:
#   - 백엔드 서버 실행 중 (기본 http://localhost:8080)
#   - (자동 유저 생성 시) 로컬 PostgreSQL 도커 컨테이너 실행 중 (이메일 인증코드 DB 조회)
#
# 흐름:
#   1) 유저 A(00_common 의 TestEmail) / 유저 B(00_common 의 UserBEmail) 준비
#      - 먼저 login 시도 → 실패하면 request→verify(DB코드)→signup 자동 진행
#   2) A 토큰으로 A↔B DM 방 생성(멱등) → roomId 확보
#   3) chat-client.ps1 을 새 창 2개로 실행 (A 창, B 창)
#
# 이 창(런처)은 준비만 하고, 실제 대화는 새로 뜨는 두 창에서 이뤄진다.
param(
    [string]$UserAEmail,    [string]$UserAPassword,   [string]$UserAName,
    [string]$UserBEmail,    [string]$UserBPassword,   [string]$UserBName
)

. "$PSScriptRoot\..\00_common.ps1"

# 미지정 시 00_common 의 기본 계정값으로 채운다. (A=TestEmail, B=UserBEmail)
if (-not $UserAEmail)    { $UserAEmail    = $script:TestEmail }
if (-not $UserAPassword) { $UserAPassword = $script:TestPassword }
if (-not $UserAName)     { $UserAName     = $script:TestName }
if (-not $UserBEmail)    { $UserBEmail    = $script:UserBEmail }
if (-not $UserBPassword) { $UserBPassword = $script:UserBPassword }
if (-not $UserBName)     { $UserBName     = $script:UserBName }

Write-Host "`n########## parallel-chat launcher — 두 창 실시간 채팅 ##########" -ForegroundColor Magenta

# ----------------------------------------------------------------------------
# 계정 준비: login 우선, 실패 시 request→verify(DB)→signup 자동 진행
# ----------------------------------------------------------------------------
function Ensure-User {
    param([string]$Email, [string]$Password, [string]$Name)

    $login = Invoke-Api -Method POST -Path "/api/auth/login" -PassThru -Title "로그인 시도: $Email" -Body @{
        email = $Email; password = $Password
    }
    if ($login.data.accessToken) { return $login.data.accessToken }

    Write-Host "  (i) 계정이 없거나 로그인 실패 → 회원가입을 시도합니다: $Email" -ForegroundColor DarkYellow
    Invoke-Api -Method POST -Path "/api/auth/email/request" -Title "이메일 인증코드 요청: $Email" -Body @{ email = $Email } | Out-Null
    $code = Get-EmailVerificationCode -Email $Email
    if ($code) {
        Invoke-Api -Method POST -Path "/api/auth/email/verify" -Title "인증코드 검증: $Email" -Body @{
            email = $Email; code = $code
        } | Out-Null
        $signup = Invoke-Api -Method POST -Path "/api/auth/signup" -PassThru -Title "회원가입: $Email" -Body @{
            email = $Email; password = $Password; passwordConfirm = $Password
            name = $Name; campus = "JUKJEON"; college = "SW융합대학"; major = "소프트웨어학과"; grade = "3학년"
        }
        if ($signup.data.accessToken) { return $signup.data.accessToken }
    }

    # 이미 가입돼 있었으나 위 login 이 다른 이유로 실패했을 수도 있으니 마지막으로 재로그인
    $login2 = Invoke-Api -Method POST -Path "/api/auth/login" -PassThru -Title "재로그인: $Email" -Body @{
        email = $Email; password = $Password
    }
    if ($login2.data.accessToken) { return $login2.data.accessToken }
    return $null
}

Write-Host "`n[1] 유저 A 준비: $UserAEmail" -ForegroundColor Cyan
$tokenA = Ensure-User -Email $UserAEmail -Password $UserAPassword -Name $UserAName
Write-Host "`n[1] 유저 B 준비: $UserBEmail" -ForegroundColor Green
$tokenB = Ensure-User -Email $UserBEmail -Password $UserBPassword -Name $UserBName

if (-not $tokenA -or -not $tokenB) {
    Write-Host "`n(!) 계정 준비 실패 — 서버/DB 컨테이너 상태를 확인하세요. (docker ps)" -ForegroundColor Red
    return
}
$userIdA = Get-JwtSubject -Token $tokenA
$userIdB = Get-JwtSubject -Token $tokenB
if ($userIdA -eq $userIdB) {
    Write-Host "`n(!) A 와 B 가 동일 계정입니다. -UserBEmail 을 A 와 다른 이메일로 지정하세요." -ForegroundColor Red
    return
}
Write-Host "`n(i) A userId=$userIdA / B userId=$userIdB" -ForegroundColor DarkGray

# ----------------------------------------------------------------------------
# DM 방 생성 (A 토큰 사용). Invoke-Api -Auth 는 토큰 파일을 읽으므로 A 토큰을 잠깐 저장한다.
# ----------------------------------------------------------------------------
Save-AccessToken $tokenA
$dm = Invoke-Api -Method POST -Path "/api/chat/rooms/dm" -Auth -PassThru -Title "DM 방 생성 (A→B, 멱등)" -Body @{
    targetUserId = [long]$userIdB
}
$roomId = $dm.data.roomId
if (-not $roomId) {
    Write-Host "`n(!) roomId 를 확보하지 못했습니다. 채팅 창을 띄울 수 없습니다." -ForegroundColor Red
    return
}
Write-Host "`n(i) DM roomId = $roomId" -ForegroundColor DarkGray

# ----------------------------------------------------------------------------
# 채팅 창 2개 실행 (A: Cyan, B: Green)
# ----------------------------------------------------------------------------
$client = Join-Path $PSScriptRoot "chat-client.ps1"

# 채팅 창을 띄울 PowerShell 실행 파일을 결정한다.
#   Windows PowerShell 5.1(powershell.exe)의 .NET Framework ClientWebSocket 은 서버의
#   'Connection: upgrade, keep-alive' 헤더를 거부해 WebSocket 연결이 실패한다.
#   PowerShell 7+(pwsh.exe) 는 정상 처리하므로 pwsh 를 우선 사용한다.
function Resolve-ChatShell {
    $pwsh = Get-Command pwsh.exe -ErrorAction SilentlyContinue
    if ($pwsh) { return $pwsh.Source }
    $known = "$env:ProgramFiles\PowerShell\7\pwsh.exe"
    if (Test-Path $known) { return $known }
    Write-Host "  (!) pwsh(PowerShell 7) 를 찾지 못했습니다. powershell.exe(5.1) 로 실행하면" -ForegroundColor Yellow
    Write-Host "      WebSocket 연결이 'Connection 헤더' 오류로 실패할 수 있습니다." -ForegroundColor Yellow
    Write-Host "      해결: winget install --id Microsoft.PowerShell --source winget" -ForegroundColor Yellow
    return 'powershell.exe'
}
$chatShell = Resolve-ChatShell
Write-Host "(i) 채팅 창 실행 셸: $chatShell" -ForegroundColor DarkGray

function Start-ChatWindow {
    param([string]$Label, [string]$Email, [string]$Password, [string]$Color)
    # pwsh 와 powershell.exe 모두 -NoExit/-ExecutionPolicy/-File 인자를 동일하게 받는다.
    $procArgs = @(
        '-NoExit', '-ExecutionPolicy', 'Bypass', '-File', $client,
        '-Label', $Label, '-Email', $Email, '-Password', $Password,
        '-RoomId', $roomId, '-BaseUrl', $script:BaseUrl, '-Color', $Color
    )
    Start-Process -FilePath $chatShell -ArgumentList $procArgs
}

Write-Host "`n[2] 채팅 창 2개를 띄웁니다..." -ForegroundColor Magenta
Start-ChatWindow -Label 'A' -Email $UserAEmail -Password $UserAPassword -Color 'Cyan'
Start-Sleep -Milliseconds 400
Start-ChatWindow -Label 'B' -Email $UserBEmail -Password $UserBPassword -Color 'Green'

Write-Host "`n두 창이 열렸습니다. 한쪽에서 메시지를 입력하면 다른 쪽 창에 노란색으로 실시간 표시됩니다." -ForegroundColor Green
Write-Host "  - A 창: $UserAEmail (userId=$userIdA)" -ForegroundColor Cyan
Write-Host "  - B 창: $UserBEmail (userId=$userIdB)" -ForegroundColor Green
Write-Host "  - 방 번호: #$roomId" -ForegroundColor DarkGray
Write-Host "각 창에서 /quit 로 종료하세요." -ForegroundColor DarkGray
