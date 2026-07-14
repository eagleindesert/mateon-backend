# 06_notification.ps1 - Notification (알림) API 테스트  /api/notifications  [인증 필요]
# 사용법: powershell -ExecutionPolicy Bypass -File .\06_notification.ps1
# 사전 조건: 02_auth.ps1 로 로그인하여 .auth-token.txt 가 생성되어 있어야 한다.
. "$PSScriptRoot\00_common.ps1"

Write-Host "`n########## 6. Notification (알림) - /api/notifications [인증 필요] ##########" -ForegroundColor Magenta

$token = Get-AccessToken
if (-not $token) {
    Write-Host "(!) accessToken 이 없습니다. 먼저 .\auth\02_auth.ps1 을 실행하세요." -ForegroundColor Red
    return
}

# 6.2 내 알림 목록 조회
Invoke-Api -Method GET -Path "/api/notifications" -Auth -Title "6.2 내 알림 목록 조회"

# 6.1 실시간 알림 구독 (SSE)
# SSE 는 연결이 계속 열려 있으므로 curl.exe 에 --max-time 을 주어 일정 시간만 수신 후 종료한다.
Write-Host "`n[6.1 실시간 알림 구독 (SSE)] 5초간 스트림을 수신합니다..." -ForegroundColor Cyan
$sseUrl = "$script:BaseUrl/api/notifications/subscribe"
Write-Host "  -> $sseUrl" -ForegroundColor DarkGray
& $script:Curl -s -N --max-time 5 `
    -H "Authorization: Bearer $token" `
    -H "Accept: text/event-stream" `
    $sseUrl
Write-Host "`n  (i) SSE 수신 종료 (max-time 5s)" -ForegroundColor Green
