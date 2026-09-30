# chat-client.ps1 - 한 유저용 실시간 채팅 클라이언트 (창 1개 = 유저 1명)
# ----------------------------------------------------------------------------
# launch.ps1 이 이 스크립트를 두 개의 새 PowerShell 창으로 각각 띄운다.
# (직접 실행도 가능: 아래 param 을 채워서 호출)
#
#   powershell -ExecutionPolicy Bypass -File .\chat-client.ps1 `
#       -Label A -Email test19@example.ac.kr -Password Password1234 -RoomId 3
#
# 동작:
#   1) 이메일/비밀번호로 로그인해 accessToken 을 '메모리에만' 보관
#      (공용 .auth-token.txt 를 건드리지 않는다 → 두 창이 서로의 토큰을 덮어쓰지 않음)
#   2) STOMP CONNECT + /topic/room.{RoomId} 구독
#   3) [백그라운드 런스페이스] ReceiveAsync 로 브로드캐스트를 상시 수신 → 콘솔에 실시간 출력
#   4) [메인 스레드] Read-Host 로 입력받아 /app/chat.send 로 발행 (한글 IME 정상 입력)
#      - 빈 줄: 무시,  /quit 또는 /exit: 종료
#
# 참고: 수신 출력은 런스페이스에서 [Console]::WriteLine 으로 직접 찍는다.
#       (별도 런스페이스의 Write-Host 는 부모 콘솔에 안 닿을 수 있어서 Console API 를 쓴다.)
param(
    [Parameter(Mandatory = $true)][string]$Label,      # 창 구분용 라벨 (예: A, B)
    [Parameter(Mandatory = $true)][string]$Email,
    [Parameter(Mandatory = $true)][string]$Password,
    [Parameter(Mandatory = $true)][long]$RoomId,
    [string]$BaseUrl,                                   # 미지정 시 00_common 의 BaseUrl
    [ValidateSet('Cyan','Green','Yellow','Magenta','White','Blue')]
    [string]$Color = 'Cyan'                             # 이 창(나)의 테마 색
)

# 공통 설정/로그인 헬퍼 + STOMP 라이브러리 로드 (상위 for-rest-api 폴더 기준)
. "$PSScriptRoot\..\00_common.ps1"
. "$PSScriptRoot\_stomp-lib.ps1"

if ($BaseUrl) { $script:BaseUrl = $BaseUrl }

$Host.UI.RawUI.WindowTitle = "Chat [$Label] $Email"

Write-Host ("=" * 64) -ForegroundColor $Color
Write-Host " 실시간 채팅 클라이언트  [$Label]  $Email" -ForegroundColor $Color
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
# 2) 최근 이력 몇 건 표시 (있으면) — 대화 맥락 확인용
# ----------------------------------------------------------------------------
try {
    $hraw = & curl.exe -s -H "Authorization: Bearer $token" "$script:BaseUrl/api/chat/rooms/$RoomId/messages?size=8"
    $hist = ($hraw | ConvertFrom-Json).data
    if ($hist -and $hist.Count -gt 0) {
        Write-Host "`n--- 최근 대화 ---" -ForegroundColor DarkGray
        foreach ($h in $hist) {
            $who = if ($h.senderId -eq $myId) { "나" } else { $h.senderName }
            Write-Host ("  {0}: {1}" -f $who, $h.content) -ForegroundColor DarkGray
        }
        Write-Host "-----------------`n" -ForegroundColor DarkGray
    }
} catch { }

# ----------------------------------------------------------------------------
# 3) STOMP 연결 + 구독
# ----------------------------------------------------------------------------
$ws = Connect-Stomp -Token $token
if (-not $ws) {
    Write-Host "(!) STOMP 연결 실패. 서버 실행 및 /ws-stomp 등록 여부를 확인하세요." -ForegroundColor Red
    Read-Host "종료하려면 Enter"
    return
}

# 수신 런스페이스를 먼저 띄운 뒤 구독한다 (구독 직후 오는 브로드캐스트를 놓치지 않도록).
# 브로드캐스트 프레임(문자열)을 담는 스레드-세이프 큐. 런스페이스가 넣고... 사실은
# 런스페이스가 직접 콘솔에 출력하므로 큐 없이 Console API 로 바로 찍는다.

$receiver = {
    # 이 스크립트블록은 별도 런스페이스에서 돈다. 사용 변수는 SessionStateProxy 로 주입됨:
    #   $ws(ClientWebSocket), $myId(long), $selfColorName(string)
    $NUL = [char]0
    $buffer = New-Object 'byte[]' 8192
    $acc = [System.Text.StringBuilder]::new()

    function Show-Line {
        param([string]$Frame)
        if (-not $Frame.StartsWith("MESSAGE")) { return }
        $bi = $Frame.IndexOf("`n`n")
        if ($bi -lt 0) { return }
        $body = $Frame.Substring($bi + 2).TrimEnd([char]0)
        if (-not $body) { return }
        try { $m = $body | ConvertFrom-Json } catch { return }

        $ts = ""
        try { if ($m.createdAt) { $ts = ([datetime]$m.createdAt).ToString("HH:mm:ss") } } catch { }

        $mine = ($m.senderId -eq $myId)
        $prev = [Console]::ForegroundColor
        if ($mine) {
            [Console]::ForegroundColor = [ConsoleColor]::DarkGray
            [Console]::WriteLine(("[{0}] 나 ✓  {1}" -f $ts, $m.content))
        } else {
            [Console]::ForegroundColor = [ConsoleColor]::Yellow
            [Console]::WriteLine("")
            [Console]::WriteLine(("[{0}] {1} ▶  {2}" -f $ts, $m.senderName, $m.content))
        }
        [Console]::ForegroundColor = $prev
    }

    while ($ws.State -eq [System.Net.WebSockets.WebSocketState]::Open) {
        try {
            $seg = [System.ArraySegment[byte]]::new($buffer)
            $res = $ws.ReceiveAsync($seg, [System.Threading.CancellationToken]::None).GetAwaiter().GetResult()
            if ($res.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) { break }
            if ($res.Count -gt 0) {
                [void]$acc.Append([System.Text.Encoding]::UTF8.GetString($buffer, 0, $res.Count))
                $s = $acc.ToString()
                while ($s.IndexOf($NUL) -ge 0) {
                    $i = $s.IndexOf($NUL)
                    $frame = $s.Substring(0, $i)
                    if ($frame.Trim().Length -gt 0) { Show-Line -Frame $frame }
                    $s = $s.Substring($i + 1)
                }
                [void]$acc.Clear(); [void]$acc.Append($s)
            }
        } catch { break }
    }
}

$rs = [runspacefactory]::CreateRunspace()
$rs.ApartmentState = 'MTA'
$rs.Open()
$rs.SessionStateProxy.SetVariable('ws', $ws)
$rs.SessionStateProxy.SetVariable('myId', $myId)
$psRun = [powershell]::Create()
$psRun.Runspace = $rs
[void]$psRun.AddScript($receiver)
$async = $psRun.BeginInvoke()

Subscribe-Room -Ws $ws -SubId ("sub-" + $Label) -RoomId $RoomId
Start-Sleep -Milliseconds 300

Write-Host ""
Write-Host "연결됨 — room #$RoomId 구독 완료. 메시지를 입력하고 Enter 로 전송하세요." -ForegroundColor $Color
Write-Host "(종료: /quit 또는 /exit)  노랑=상대 수신,  회색=내 메시지 서버 반영 확인" -ForegroundColor DarkGray
Write-Host ""

# ----------------------------------------------------------------------------
# 4) 전송 루프 (메인 스레드, Read-Host → 한글 IME 정상)
#    보낸 메시지는 서버가 브로드백해 주므로 화면에는 수신 런스페이스가 '나 ✓' 로 찍는다.
# ----------------------------------------------------------------------------
try {
    while ($true) {
        $line = Read-Host "$Label(나)"
        if ($null -eq $line) { break }
        $t = $line.Trim()
        if ($t -eq '/quit' -or $t -eq '/exit') { break }
        if ($t.Length -eq 0) { continue }
        if ($ws.State -ne [System.Net.WebSockets.WebSocketState]::Open) {
            Write-Host "(!) 연결이 끊겼습니다. 종료합니다." -ForegroundColor Red
            break
        }
        Send-ChatMessage -Ws $ws -RoomId $RoomId -Content $line
    }
} finally {
    Write-Host "`n연결 종료 중..." -ForegroundColor DarkGray
    Close-Ws $ws
    try { $psRun.Stop() } catch {}
    try { $rs.Close() } catch {}
    Write-Host "종료되었습니다. [$Label]" -ForegroundColor $Color
}
