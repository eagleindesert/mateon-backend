# 99_run_all.ps1 (for-api-server) - 전체 API 테스트를 순서대로 실행 (원격 서버 대상)
# 사용법:
#   powershell -ExecutionPolicy Bypass -File .\99_run_all.ps1
#   powershell -ExecutionPolicy Bypass -File .\99_run_all.ps1 -Email me@example.ac.kr -Password Password1234
#
# 이 러너는 인증 계열 스크립트(02_auth / 07_school_auth / 08_social_kakao)를 실행하지 않는다.
#   대신 채팅 등 인증이 필요한 테스트를 위해, 코드 입력 없이 유저 A 로그인만 해서 토큰을 확보한다.
#   (auth/ 스크립트는 필요할 때 개별 실행: pwsh -File .\auth\02_auth.ps1 ...)
#   - 전제: 유저 A/B 계정이 이미 존재해야 한다(신규 가입/코드 입력은 하지 않음).
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

# ===== 2) Auth (비활성화) — 인증 계열은 이 러너에서 실행하지 않는다. =====
# 필요 시 개별 실행: pwsh -File .\auth\02_auth.ps1 -Email ... -Password ...
# & "$PSScriptRoot\auth\02_auth.ps1" @authArgs

# ===== (준비) 유저 A 로그인 — 인증 필요 테스트용 토큰 확보 (코드 입력 없음) =====
# 02_auth 를 실행하지 않으므로 여기서 로그인만 해서 A 세션 토큰을 저장한다.
# 전제: 유저 A 계정이 이미 존재해야 한다(신규 가입/코드 입력은 하지 않음).
Write-Host "`n===== (준비) 유저 A 로그인 (토큰 확보) =====" -ForegroundColor Magenta
$loginEmail    = if ($Email)    { $Email }    else { $script:TestEmail }
$loginPassword = if ($Password) { $Password } else { $script:TestPassword }
$loginA = Invoke-Api -Method POST -Path "/api/auth/login" -PassThru -Title "유저 A 로그인" -Body @{
    email = $loginEmail; password = $loginPassword
}
if ($loginA.data.accessToken) {
    Save-AccessToken $loginA.data.accessToken
    Save-RefreshToken $loginA.data.refreshToken
    Write-Host "  (i) 유저 A 로그인/토큰 저장 완료 -> 이후 인증 필요 테스트에서 재사용" -ForegroundColor Green
} else {
    Write-Host "  (!) 유저 A 로그인 실패 - 계정 존재 여부를 확인하세요. 인증 필요 테스트가 스킵될 수 있습니다." -ForegroundColor Yellow
}

Write-Host "`n===== 3) User =====" -ForegroundColor Magenta
& "$PSScriptRoot\03_user.ps1"

Write-Host "`n===== 4) Event =====" -ForegroundColor Magenta
& "$PSScriptRoot\04_event.ps1"

Write-Host "`n===== 5) Team =====" -ForegroundColor Magenta
& "$PSScriptRoot\05_team.ps1"

Write-Host "`n===== 6) Notification =====" -ForegroundColor Magenta
& "$PSScriptRoot\06_notification.ps1"

# ===== 7) School Email Auth (비활성화) — 인증 계열은 이 러너에서 실행하지 않는다. =====
# 필요 시 개별 실행: pwsh -File .\auth\07_school_auth.ps1
# $schoolArgs = @{}
# if ($Email) { $schoolArgs.Email = $Email }
# & "$PSScriptRoot\auth\07_school_auth.ps1" @schoolArgs

# ===== 8) Social Login (Kakao) (비활성화) — 인증 계열은 이 러너에서 실행하지 않는다. =====
# 필요 시 개별 실행: pwsh -File .\auth\08_social_kakao.ps1
# & "$PSScriptRoot\auth\08_social_kakao.ps1"

Write-Host "`n===== 10) Chat (REST + WebSocket/STOMP) =====" -ForegroundColor Magenta
& "$PSScriptRoot\10_chat.ps1" @chatArgs

Write-Host "`n===== 11) Matching Intent (AI 의도 추출) =====" -ForegroundColor Magenta
# 원격 백엔드의 ai.base-url 이 살아있는 FastAPI 를 가리켜야 한다.
# 닿지 않으면 503(AI_SERVER_UNAVAILABLE)으로 실패하며, 스크립트가 원인을 안내한다.
& "$PSScriptRoot\11_matching_intent.ps1"

Write-Host "`n===== 12) Team Embedding (팀 임베딩 연동) =====" -ForegroundColor Magenta
# 임베딩 갱신은 커밋 후 비동기라 AI 서버가 죽어있어도 팀 CRUD 는 전부 성공해야 한다.
& "$PSScriptRoot\12_team_embedding.ps1"

Write-Host "`n===== 전체 테스트 완료 =====" -ForegroundColor Green

# 성공/실패 개수 및 실패 항목 요약 출력
$failedCount = Write-TestSummary

# 실패가 있으면 0 이 아닌 종료 코드로 종료 (CI 등에서 활용 가능)
exit $failedCount
