# _stomp-lib.ps1
# STOMP over (native) WebSocket 최소 클라이언트 헬퍼 모음.
#   - 서버 WebSocketConfig 가 /ws-stomp 에 네이티브 WebSocket 을 등록해 두었다.
#   - 인증은 STOMP CONNECT 프레임의 native header "Authorization: Bearer ..." 로 전달한다.
#     (StompAuthChannelInterceptor 가 이 헤더를 읽어 검증)
#
# 10_chat.ps1 이 인라인으로 갖고 있던 STOMP 로직을 재사용 가능하게 분리한 것.
# chat-client.ps1 에서 `. "$PSScriptRoot\_stomp-lib.ps1"` 로 로드한다.
#
# 주의: 이 라이브러리의 "발행(SEND)" 계열 함수는 메인 스레드에서만 호출한다.
#       "수신"은 chat-client.ps1 이 별도 런스페이스에서 ReceiveAsync 로 직접 처리한다.
#       (ClientWebSocket 은 동시에 1건 송신 + 1건 수신을 허용하므로 이 조합은 안전하다.)

$script:NUL = [char]0

# STOMP 프레임 문자열 생성 (COMMAND\n header:value\n\n body\0)
function New-StompFrame {
    param([string]$Command, [hashtable]$Headers, [string]$Body)
    $sb = [System.Text.StringBuilder]::new()
    [void]$sb.Append($Command).Append("`n")
    if ($Headers) {
        foreach ($k in $Headers.Keys) { [void]$sb.Append($k).Append(":").Append($Headers[$k]).Append("`n") }
    }
    [void]$sb.Append("`n")
    if ($Body) { [void]$sb.Append($Body) }
    [void]$sb.Append($script:NUL)
    return $sb.ToString()
}

function Send-WsText {
    param($Ws, [string]$Text)
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
    $seg = [System.ArraySegment[byte]]::new($bytes)
    $Ws.SendAsync($seg, [System.Net.WebSockets.WebSocketMessageType]::Text, $true,
        [System.Threading.CancellationToken]::None).GetAwaiter().GetResult() | Out-Null
}

# NUL(\0) 로 구분된 STOMP 프레임 하나를 수신한다. 타임아웃 시 $null.
#   (핸드셰이크 CONNECTED 응답 확인 등 '한 번만' 받을 때 사용. 상시 수신 루프는 런스페이스에서 별도 처리)
function Receive-WsFrameOnce {
    param($Ws, [int]$TimeoutSec = 5)
    $cts = New-Object System.Threading.CancellationTokenSource
    $cts.CancelAfter([TimeSpan]::FromSeconds($TimeoutSec))
    $buffer = New-Object 'byte[]' 8192
    $sb = [System.Text.StringBuilder]::new()
    try {
        while ($true) {
            $seg = [System.ArraySegment[byte]]::new($buffer)
            $res = $Ws.ReceiveAsync($seg, $cts.Token).GetAwaiter().GetResult()
            if ($res.Count -gt 0) {
                [void]$sb.Append([System.Text.Encoding]::UTF8.GetString($buffer, 0, $res.Count))
                if ($sb.ToString().Contains($script:NUL)) { break }
            }
        }
    } catch {
        return $null  # 타임아웃 등
    }
    return $sb.ToString()
}

# STOMP 프레임 body(헤더 다음 빈 줄 이후 ~ NUL 전) 추출
function Get-StompBody {
    param([string]$Frame)
    if (-not $Frame) { return $null }
    $idx = $Frame.IndexOf("`n`n")
    if ($idx -lt 0) { return $null }
    return $Frame.Substring($idx + 2).TrimEnd($script:NUL)
}

# 연결 + CONNECT 핸드셰이크. 성공 시 열린 ClientWebSocket 반환, 실패 시 $null.
#   $BaseUrl 미지정 시 상위 스크립트의 $script:BaseUrl 을 사용한다.
function Connect-Stomp {
    param([string]$Token, [string]$BaseUrl)
    if (-not $BaseUrl) { $BaseUrl = $script:BaseUrl }
    $wsUrl = ($BaseUrl -replace '^http', 'ws') + "/ws-stomp"
    $ws = New-Object System.Net.WebSockets.ClientWebSocket
    try {
        $uri = [System.Uri]$wsUrl
        $ws.ConnectAsync($uri, [System.Threading.CancellationToken]::None).GetAwaiter().GetResult() | Out-Null
    } catch {
        Write-Host "  (!) WebSocket 연결 실패: $wsUrl - $($_.Exception.Message)" -ForegroundColor Red
        return $null
    }
    $connect = New-StompFrame -Command "CONNECT" -Headers @{
        "accept-version" = "1.2"; "host" = "localhost"; "heart-beat" = "0,0"
        "Authorization"  = "Bearer $Token"
    }
    Send-WsText -Ws $ws -Text $connect
    $resp = Receive-WsFrameOnce -Ws $ws -TimeoutSec 5
    if ($resp -and $resp.StartsWith("CONNECTED")) { return $ws }
    Write-Host "  (!) STOMP CONNECT 실패. 응답: $resp" -ForegroundColor Red
    try { $ws.Dispose() } catch {}
    return $null
}

function Subscribe-Room {
    param($Ws, [string]$SubId, [long]$RoomId)
    Send-WsText -Ws $Ws -Text (New-StompFrame -Command "SUBSCRIBE" -Headers @{
        "id" = $SubId; "destination" = "/topic/room.$RoomId"
    })
}

function Send-ChatMessage {
    param($Ws, [long]$RoomId, [string]$Content)
    $body = @{ roomId = $RoomId; content = $Content } | ConvertTo-Json -Compress
    Send-WsText -Ws $Ws -Text (New-StompFrame -Command "SEND" -Headers @{
        "destination" = "/app/chat.send"; "content-type" = "application/json"
    } -Body $body)
}

function Close-Ws {
    param($Ws)
    if ($Ws) {
        try {
            $Ws.CloseAsync([System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure, "bye",
                [System.Threading.CancellationToken]::None).GetAwaiter().GetResult() | Out-Null
        } catch {}
        try { $Ws.Dispose() } catch {}
    }
}
