# 99_run_all.ps1 (for-api-server) - 전체 API 테스트를 순서대로 실행 (원격 서버 대상)
# 사용법:
#   powershell -ExecutionPolicy Bypass -File .\99_run_all.ps1
#   powershell -ExecutionPolicy Bypass -File .\99_run_all.ps1 -Email me@example.ac.kr -Password Password1234
#
# 인증코드는 각 스크립트가 실행 중에 콘솔로 직접 물어본다(수동 입력).
#   02_auth 단계에서 유저 A·B 두 계정의 코드를 각각 입력하게 되며, 이후 10_chat 은 B 를
#   로그인만 하므로 코드 입력이 없다.
#   - 코드 확인 방법: 서버가 보낸 메일 또는 원격 DB(pgAdmin/psql)의 email_verifications.code
param(
    [string]$Email = "",          # 유저 A: 미지정 시 00_common 의 TestEmail
    [string]$Password = "",       # 유저 A: 미지정 시 00_common 의 TestPassword
    [string]$UserBEmail = "",     # 유저 B: 미지정 시 00_common 의 UserBEmail
    [string]$UserBPassword = ""   # 유저 B: 미지정 시 00_common 의 UserBPassword
)

. "$PSScriptRoot\00_common.ps1"

# 이전 실행 결과가 남아있지 않도록 집계 초기화
Reset-TestResults

$authArgs = @{}
if ($Email)         { $authArgs.Email         = $Email }
if ($Password)      { $authArgs.Password      = $Password }
if ($UserBEmail)    { $authArgs.UserBEmail    = $UserBEmail }
if ($UserBPassword) { $authArgs.UserBPassword = $UserBPassword }

# 채팅 스크립트에 넘길 유저 B 인자 (로그인 전용)
$chatArgs = @{}
if ($UserBEmail)    { $chatArgs.UserBEmail    = $UserBEmail }
if ($UserBPassword) { $chatArgs.UserBPassword = $UserBPassword }

Write-Host "===== 1) Health =====" -ForegroundColor Magenta
& "$PSScriptRoot\01_health.ps1"

Write-Host "`n===== 2) Auth (유저 A·B 생성 -> A 토큰 저장) =====" -ForegroundColor Magenta
& "$PSScriptRoot\02_auth.ps1" @authArgs

Write-Host "`n===== 3) User =====" -ForegroundColor Magenta
& "$PSScriptRoot\03_user.ps1"

Write-Host "`n===== 4) Event =====" -ForegroundColor Magenta
& "$PSScriptRoot\04_event.ps1"

Write-Host "`n===== 5) Team =====" -ForegroundColor Magenta
& "$PSScriptRoot\05_team.ps1"

Write-Host "`n===== 6) Notification =====" -ForegroundColor Magenta
& "$PSScriptRoot\06_notification.ps1"

Write-Host "`n===== 7) School Email Auth =====" -ForegroundColor Magenta
$schoolArgs = @{}
if ($Email) { $schoolArgs.Email = $Email }
& "$PSScriptRoot\07_school_auth.ps1" @schoolArgs

Write-Host "`n===== 8) Social Login (Kakao) =====" -ForegroundColor Magenta
# 실제 카카오 토큰이 없으면 음성 테스트(잘못된 토큰 차단)만 수행한다.
# 정상 경로까지 보려면 .env 의 MATEON_KAKAO_ACCESS_TOKEN 을 설정한다.
& "$PSScriptRoot\08_social_kakao.ps1"

Write-Host "`n===== (준비) 유저 A 재로그인 (채팅 전 세션 복구) =====" -ForegroundColor Magenta
# 08 카카오 정상 경로가 실행되면 저장 토큰을 카카오 유저로 덮어쓸 수 있다.
# 코드 재입력 없이 A 세션을 되돌리기 위해 login 만 다시 수행한다(가입/코드 불필요).
$reloginEmail    = if ($Email)    { $Email }    else { $script:TestEmail }
$reloginPassword = if ($Password) { $Password } else { $script:TestPassword }
$reloginA = Invoke-Api -Method POST -Path "/api/auth/login" -PassThru -Title "유저 A 재로그인" -Body @{
    email = $reloginEmail; password = $reloginPassword
}
if ($reloginA.data.accessToken) {
    Save-AccessToken $reloginA.data.accessToken
    Save-RefreshToken $reloginA.data.refreshToken
    Write-Host "  (i) 유저 A 세션 복구 완료" -ForegroundColor Green
} else {
    Write-Host "  (!) 유저 A 재로그인 실패 - 채팅 테스트가 A 세션을 확보하지 못할 수 있습니다." -ForegroundColor Yellow
}

Write-Host "`n===== 10) Chat (REST + WebSocket/STOMP) =====" -ForegroundColor Magenta
& "$PSScriptRoot\10_chat.ps1" @chatArgs

Write-Host "`n===== 전체 테스트 완료 =====" -ForegroundColor Green

# 성공/실패 개수 및 실패 항목 요약 출력
$failedCount = Write-TestSummary

# 실패가 있으면 0 이 아닌 종료 코드로 종료 (CI 등에서 활용 가능)
exit $failedCount
