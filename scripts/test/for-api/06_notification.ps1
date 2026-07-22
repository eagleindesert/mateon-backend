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
#
# 주의: 여기서 연결을 강제로 끊는 동작(프론트에서 사용자가 탭을 닫는 것과 같다)은 의도된 것이다.
#       서버에 죽은 emitter 가 남고, 뒤에 도는 15_review.ps1 의 팀 종료 알림이 그리로 전송을
#       시도하게 된다. 예전엔 이 전송 실패가 알림 저장을 롤백시켰다(15.7d 참고).
Write-Host "`n[6.1 실시간 알림 구독 (SSE)] 5초간 스트림을 수신합니다..." -ForegroundColor Cyan
$sseUrl = "$script:BaseUrl/api/notifications/subscribe"
Write-Host "  -> $sseUrl" -ForegroundColor DarkGray
# SSE 는 Invoke-Api 로 감쌀 수 없어(스트림이라 끝나지 않는다) 직접 호출하고 결과만 집계에 넣는다.
$sseOut = (& $script:Curl -s -N --max-time 5 `
    -H "Authorization: Bearer $token" `
    -H "Accept: text/event-stream" `
    $sseUrl) -join "`n"
Write-Host $sseOut
Write-Host "`n  (i) SSE 수신 종료 (max-time 5s)" -ForegroundColor Green

# 구독 직후 서버가 connect 이벤트를 흘려보내는지 확인한다 (NotificationService.subscribe 의 더미 전송).
Assert-Test -Title "6.1b SSE 구독 시 connect 이벤트 수신" `
    -Condition ($sseOut -match "event:\s*connect") `
    -Detail "received=$($sseOut.Length) chars" | Out-Null
