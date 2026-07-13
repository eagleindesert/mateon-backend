# notification-client.ps1 - 한 유저용 실시간 알림(SSE) 뷰어 (창 1개 = 유저 1명)
# ----------------------------------------------------------------------------
# launch.ps1 이 chat-client 두 창에 이어 이 스크립트를 '알림 감시 창'으로 띄운다.
# (직접 실행도 가능)
#
#   powershell -ExecutionPolicy Bypass -File .\notification-client.ps1 `
#       -Label B-noti -Email chatmate@snu.ac.kr -Password Password1234
#
# 동작:
#   1) 이메일/비밀번호로 로그인해 accessToken 을 '메모리에만' 보관
#      (공용 .auth-token.txt 를 건드리지 않는다 → 채팅 창들의 토큰과 충돌 없음)
#   2) GET /api/notifications/subscribe 를 curl 로 스트리밍 수신 (text/event-stream)
#   3) event:/data: 라인을 파싱해 알림을 실시간으로 콘솔에 예쁘게 출력
#
# 참고: 알림(SSE)은 일반 HTTP GET 스트림이라 WebSocket 과 달리 pwsh 7 이 필요 없다.
#       채팅(STOMP/WebSocket)만 Connection 헤더 협상 때문에 pwsh 7 이 필요하다.
#
# 알림 발생 시점(서버): ChatService 가 메시지 저장 후 '발신자를 제외한' 방 멤버에게
#   notificationService.send(...) 로 SSE 알림을 민다. 따라서 A 창에서 메시지를 보내면
#   B 의 이 창에 '🔔 …님의 메시지' 가 뜬다. (TeamService 지원 알림도 동일하게 표시됨)
param(
    [Parameter(Mandatory = $true)][string]$Label,      # 창 구분용 라벨 (예: B-noti)
    [Parameter(Mandatory = $true)][string]$Email,
    [Parameter(Mandatory = $true)][string]$Password,
    [string]$BaseUrl,                                   # 미지정 시 00_common 의 BaseUrl
    [ValidateSet('Cyan','Green','Yellow','Magenta','White','Blue')]
    [string]$Color = 'Magenta'                          # 이 창의 테마 색
)

# 공통 설정/로그인 헬퍼 로드 (상위 for-rest-api 폴더 기준)
. "$PSScriptRoot\..\00_common.ps1"

if ($BaseUrl) { $script:BaseUrl = $BaseUrl }

# 한글 알림이 깨지지 않도록 콘솔 출력 인코딩을 UTF-8 로 고정 (curl stdout 디코딩용)
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

$Host.UI.RawUI.WindowTitle = "Noti [$Label] $Email"

Write-Host ("=" * 64) -ForegroundColor $Color
Write-Host " 실시간 알림(SSE) 뷰어  [$Label]  $Email" -ForegroundColor $Color
Write-Host ("=" * 64) -ForegroundColor $Color

# ----------------------------------------------------------------------------
# 1) 로그인 (토큰은 메모리에만 보관 — 파일 저장 안 함)
# ----------------------------------------------------------------------------
$login = Invoke-Api -Method POST -Path "/api/auth/login" -PassThru -Title "로그인 ($Label)" -Body @{
    email = $Email; password = $Password
}
$token = $login.data.accessToken
if (-not $token) {
    Write-Host "(!) 로그인 실패: $Email — launch.ps1 로 먼저 계정이 준비됐는지 확인하세요." -ForegroundColor Red
    Read-Host "종료하려면 Enter"
    return
}
$myId = [long](Get-JwtSubject -Token $token)
Write-Host ("(i) 로그인 성공. userId={0}" -f $myId) -ForegroundColor DarkGray

# ----------------------------------------------------------------------------
# 2) SSE 구독 스트림 수신 (curl -N: 무버퍼 스트리밍)
#    curl 이 개행마다 흘려보내는 라인을 파이프라인으로 실시간 처리한다.
# ----------------------------------------------------------------------------
$sseUrl = "$script:BaseUrl/api/notifications/subscribe"
Write-Host ""
Write-Host "구독 중 — $sseUrl" -ForegroundColor $Color
Write-Host "이 창을 열어두고 채팅 창(A)에서 메시지를 보내면 여기에 알림이 뜹니다. (종료: Ctrl+C)" -ForegroundColor DarkGray
Write-Host ""

# SSE 는 이벤트 블록 단위: 'event:<이름>' 다음 'data:<본문>', 빈 줄로 블록 종료.
# 현재 이벤트 이름을 추적하며 data 라인이 오면 이벤트별로 출력한다.
$script:CurrentEvent = 'message'

& curl.exe -s -N `
    -H "Authorization: Bearer $token" `
    -H "Accept: text/event-stream" `
    $sseUrl | ForEach-Object {
    $line = $_

    # 빈 줄 = 이벤트 블록 경계 → 다음 블록 기본 이벤트로 리셋
    if ([string]::IsNullOrEmpty($line)) { $script:CurrentEvent = 'message'; return }
    # ':' 로 시작하면 SSE 주석/하트비트 → 무시
    if ($line.StartsWith(':')) { return }

    if ($line.StartsWith('event:')) {
        $script:CurrentEvent = $line.Substring(6).Trim()
        return
    }

    if ($line.StartsWith('data:')) {
        $data = $line.Substring(5).TrimStart()

        switch ($script:CurrentEvent) {
            'connect' {
                Write-Host ("[연결됨] {0}" -f $data) -ForegroundColor DarkGray
            }
            'notification' {
                try {
                    $n = $data | ConvertFrom-Json
                    $ts = ""
                    try { if ($n.createdAt) { $ts = ([datetime]$n.createdAt).ToString("HH:mm:ss") } } catch { }
                    Write-Host ""
                    Write-Host ("🔔 [{0}] {1}" -f $ts, $n.title) -ForegroundColor $Color
                    Write-Host ("   {0}   (type={1}, id={2})" -f $n.content, $n.type, $n.id) -ForegroundColor Yellow
                } catch {
                    # JSON 파싱 실패 시 원문 그대로
                    Write-Host ("🔔 {0}" -f $data) -ForegroundColor $Color
                }
            }
            default {
                Write-Host ("[{0}] {1}" -f $script:CurrentEvent, $data) -ForegroundColor DarkGray
            }
        }
    }
}

Write-Host ""
Write-Host "스트림이 종료되었습니다. [$Label]" -ForegroundColor $Color
