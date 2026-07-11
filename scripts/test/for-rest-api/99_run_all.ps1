# 99_run_all.ps1 - 전체 API 테스트를 순서대로 실행
# 사용법:
#   powershell -ExecutionPolicy Bypass -File .\99_run_all.ps1
#   powershell -ExecutionPolicy Bypass -File .\99_run_all.ps1 -Email test@dankook.ac.kr -Password Password1234 -Code 123456
#
# 회원가입/인증까지 자동으로 진행하려면 이메일로 수신한 -Code(6자리)를 넘긴다.
# 이미 가입된 계정이 있다면 -Email/-Password 만 넘겨 로그인 후 나머지를 테스트할 수 있다.
# -Email/-Password 를 생략하면 00_common 의 TestEmail/TestPassword 기본값이 쓰인다.
#
# 기본 동작: 02_auth 가 request→(DB 코드 조회)→verify→signup 의 정식 절차를 자동으로 밟는다.
#   -Code 를 주면 DB 조회 대신 실제 인증코드로 진행한다.
param(
    [string]$Email = "",      # 미지정 시 각 스크립트가 00_common 의 TestEmail 사용
    [string]$Password = "",   # 미지정 시 각 스크립트가 00_common 의 TestPassword 사용
    [string]$Code = ""
)

. "$PSScriptRoot\00_common.ps1"

# 이전 실행 결과가 남아있지 않도록 집계 초기화
Reset-TestResults

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

Write-Host "`n===== 7) School Auth & Gating =====" -ForegroundColor Magenta
$schoolArgs = @{}
if ($Email) { $schoolArgs.Email = $Email }
& "$PSScriptRoot\07_school_auth.ps1" @schoolArgs

Write-Host "`n===== 8) Social Login (Kakao) =====" -ForegroundColor Magenta
# 실제 카카오 토큰이 없으면 음성 테스트(잘못된 토큰 차단)만 수행한다.
# 정상 경로까지 보려면 .env 의 MATEON_KAKAO_ACCESS_TOKEN 을 설정한다.
& "$PSScriptRoot\08_social_kakao.ps1"

Write-Host "`n===== 전체 테스트 완료 =====" -ForegroundColor Green

# 성공/실패 개수 및 실패 항목 요약 출력
$failedCount = Write-TestSummary

# 실패가 있으면 0 이 아닌 종료 코드로 종료 (CI 등에서 활용 가능)
exit $failedCount
