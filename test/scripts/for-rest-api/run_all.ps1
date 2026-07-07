# run_all.ps1 - 전체 API 테스트를 순서대로 실행
# 사용법:
#   powershell -ExecutionPolicy Bypass -File .\run_all.ps1
#   powershell -ExecutionPolicy Bypass -File .\run_all.ps1 -Email test@dankook.ac.kr -Password Password1234 -Code 123456
#
# 회원가입/인증까지 자동으로 진행하려면 이메일로 수신한 -Code(6자리)를 넘긴다.
# 이미 가입된 계정이 있다면 -Email/-Password 만 넘겨 로그인 후 나머지를 테스트할 수 있다.
param(
    [string]$Email = "",
    [string]$Password = "Password1234",
    [string]$Code = ""
)

$authArgs = @{}
if ($Email)    { $authArgs.Email = $Email }
if ($Password) { $authArgs.Password = $Password }
if ($Code)     { $authArgs.Code = $Code }

Write-Host "===== 1) Health =====" -ForegroundColor Magenta
& "$PSScriptRoot\01_health.ps1"

Write-Host "`n===== 2) Auth (로그인 -> 토큰 저장) =====" -ForegroundColor Magenta
& "$PSScriptRoot\02_auth.ps1" @authArgs

Write-Host "`n===== 3) User =====" -ForegroundColor Magenta
& "$PSScriptRoot\03_user.ps1"

Write-Host "`n===== 4) Event =====" -ForegroundColor Magenta
& "$PSScriptRoot\04_event.ps1"

Write-Host "`n===== 5) Team =====" -ForegroundColor Magenta
& "$PSScriptRoot\05_team.ps1"

Write-Host "`n===== 6) Notification =====" -ForegroundColor Magenta
& "$PSScriptRoot\06_notification.ps1"

Write-Host "`n===== 전체 테스트 완료 =====" -ForegroundColor Green
