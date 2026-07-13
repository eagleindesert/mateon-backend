# 10_chat.ps1 (for-api-server) - Chat (채팅) 테스트  /api/chat + WebSocket(STOMP)  [인증 필요]
# 사용법: powershell -ExecutionPolicy Bypass -File .\10_chat.ps1
# 사전 조건:
#   - 백엔드 서버 실행 중 (원격 주소는 .env 의 MATEON_BASE_URL 로 지정)
#   - 02_auth.ps1 로 유저 A 로그인 완료(.auth-token.txt 존재)
#   - 02_auth.ps1 이 유저 B 계정도 생성해 둠(이 스크립트는 B 를 '로그인만' 해서 사용 → 코드 입력 불필요)
#
# 흐름:
#   1) 유저 A(현재 토큰) + 유저 B(2번째 계정, 로그인) 준비
#   2) [REST] A 가 B 와의 DM 방 생성 (멱등: 두 번 호출해도 같은 roomId)
#   3) [WS/STOMP] A·B 가 /topic/room.{id} 구독 → A 가 /app/chat.send 발행
#        → B(상대)와 A(본인) 모두 실시간 수신 확인 (양방향 검증)
#   4) [REST] A 가 방 목록/메시지 이력 조회 → 방금 보낸 메시지 확인
#   5) [REST] B 안읽음 수 1 확인 → 읽음 처리 → 안읽음 수 0 확인
#
# 참고: 채팅 메시지 "전송"은 REST 가 아니라 WebSocket(STOMP) 로만 이루어진다.
#       그래서 이 스크립트는 curl(REST) 과 System.Net.WebSockets(STOMP) 를 함께 사용한다.
param(
    [string]$UserBEmail,       # 미지정 시 00_common 의 UserBEmail
    [string]$UserBPassword     # 미지정 시 00_common 의 UserBPassword
)
. "$PSScriptRoot\00_common.ps1"

# param 미지정 시 00_common 의 2번째 유저(B) 기본값으로 채운다.
if (-not $UserBEmail)    { $UserBEmail    = $script:UserBEmail }
if (-not $UserBPassword) { $UserBPassword = $script:UserBPassword }

Write-Host "`n########## 10. Chat (채팅) - /api/chat + WebSocket(STOMP) [인증 필요] ##########" -ForegroundColor Magenta

# ============================================================================
#  STOMP over (native) WebSocket 최소 클라이언트
#  - 서버 WebSocketConfig 가 /ws-stomp 에 네이티브 WebSocket 을 등록해 두었다.
#  - 인증은 STOMP CONNECT 프레임의 native header "Authorization: Bearer ..." 로 전달한다.
#    (StompAuthChannelInterceptor 가 이 헤더를 읽어 검증)
# ============================================================================
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
function Receive-WsFrame {
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
                # NUL 을 만나면 프레임 종료. (심장박동/개행만 온 경우는 계속 수신)
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
function Connect-Stomp {
    param([string]$Token)
    $wsUrl = ($script:BaseUrl -replace '^http', 'ws') + "/ws-stomp"
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
    $resp = Receive-WsFrame -Ws $ws -TimeoutSec 5
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

# 저장된 토큰을 임시로 교체했다가 되돌리기 위한 헬퍼 (B 의 인증 REST 호출용)
function Use-Token   { param([string]$Token) Save-AccessToken $Token }

# ============================================================================
#  0) 유저 A / 유저 B 준비
#     A: 저장된(02_auth 로그인) 토큰 재사용
#     B: 02_auth 가 이미 생성해 둔 계정 → 여기서는 '로그인만' 해서 토큰 확보(코드 입력 불필요)
# ============================================================================
$tokenA = Get-AccessToken
if (-not $tokenA) {
    Write-Host "(!) accessToken(A) 이 없습니다. 먼저 .\02_auth.ps1 을 실행하세요." -ForegroundColor Red
    return
}
$userIdA = Get-JwtSubject -Token $tokenA
Assert-Test -Title "10.0 유저 A userId 확보" -Condition ([bool]($userIdA -match '^\d+$')) -Detail "userIdA=$userIdA" | Out-Null

# --- 유저 B 준비: 로그인만 (02_auth 에서 이미 생성됨) ---
Write-Host "`n[10.0 유저 B 준비] $UserBEmail (로그인만)" -ForegroundColor Cyan
$loginB = Invoke-Api -Method POST -Path "/api/auth/login" -PassThru -Title "10.0 B 로그인" -Body @{
    email = $UserBEmail; password = $UserBPassword
}
$tokenB = $loginB.data.accessToken
if (-not $tokenB) {
    Write-Host "(!) 유저 B 로그인 실패 - 먼저 .\02_auth.ps1 로 유저 B 계정을 생성하세요." -ForegroundColor Red
    Write-Host "    (또는 -UserBEmail/-UserBPassword 로 이미 존재하는 계정을 지정하세요.)" -ForegroundColor Red
    return
}
$userIdB = Get-JwtSubject -Token $tokenB
Assert-Test -Title "10.0 유저 B userId 확보" -Condition ([bool]($userIdB -match '^\d+$')) -Detail "userIdB=$userIdB" | Out-Null

if ($userIdA -eq $userIdB) {
    Write-Host "(!) A 와 B 가 동일 계정입니다. -UserBEmail 을 A 와 다른 이메일로 지정하세요." -ForegroundColor Red
    return
}

# B 로그인이 저장 토큰을 덮어썼으므로 다시 A 토큰으로 복구
Use-Token $tokenA

# ============================================================================
#  2) [REST] A 가 B 와 DM 방 생성 (멱등성 확인)
# ============================================================================
$dm1 = Invoke-Api -Method POST -Path "/api/chat/rooms/dm" -Auth -PassThru -Title "10.1 DM 방 생성 (A→B)" -Body @{ targetUserId = [long]$userIdB }
$roomId = $dm1.data.roomId
Assert-Test -Title "10.1 roomId 반환" -Condition ([bool]$roomId) -Detail "roomId=$roomId" | Out-Null

$dm2 = Invoke-Api -Method POST -Path "/api/chat/rooms/dm" -Auth -PassThru -Title "10.2 DM 방 재생성 (멱등)" -Body @{ targetUserId = [long]$userIdB }
Assert-Test -Title "10.2 멱등: 같은 roomId 반환" -Condition ($dm2.data.roomId -eq $roomId) -Detail "roomId2=$($dm2.data.roomId)" | Out-Null

if (-not $roomId) {
    Write-Host "(!) roomId 를 확보하지 못해 WebSocket 테스트를 진행할 수 없습니다." -ForegroundColor Red
    return
}

# ============================================================================
#  3) [WS/STOMP] 양방향 송수신 검증
# ============================================================================
Write-Host "`n[10.3 WebSocket(STOMP) 양방향 송수신]" -ForegroundColor Cyan
$wsB = Connect-Stomp -Token $tokenB   # 수신자(B) 먼저 연결/구독
$wsA = Connect-Stomp -Token $tokenA   # 발신자(A)

if ($wsA -and $wsB) {
    Subscribe-Room -Ws $wsB -SubId "sub-b" -RoomId $roomId
    Subscribe-Room -Ws $wsA -SubId "sub-a" -RoomId $roomId
    Start-Sleep -Milliseconds 500  # 구독 등록 안정화

    $msgText = "안녕하세요 자동테스트 메시지 $((Get-Random -Maximum 99999))"
    Send-ChatMessage -Ws $wsA -RoomId $roomId -Content $msgText

    $frameB = Receive-WsFrame -Ws $wsB -TimeoutSec 5   # 상대(B) 수신
    $frameA = Receive-WsFrame -Ws $wsA -TimeoutSec 5   # 본인(A) 수신(에코)

    $bodyB = Get-StompBody -Frame $frameB
    $bodyA = Get-StompBody -Frame $frameA
    Write-Host "  B 수신 body: $bodyB" -ForegroundColor DarkGray
    Write-Host "  A 수신 body: $bodyA" -ForegroundColor DarkGray

    Assert-Test -Title "10.3 상대(B) 실시간 수신" -Condition ([bool]($bodyB -and $bodyB.Contains($msgText))) | Out-Null
    Assert-Test -Title "10.3 본인(A) 실시간 수신(에코)" -Condition ([bool]($bodyA -and $bodyA.Contains($msgText))) | Out-Null

    # 잘못된 토큰 CONNECT 는 거부되어야 한다 (음성 테스트)
    Write-Host "`n[10.4 잘못된 토큰 CONNECT 차단]" -ForegroundColor Cyan
    $wsBad = Connect-Stomp -Token "this.is.invalid"
    Assert-Test -Title "10.4 잘못된 토큰 CONNECT 거부" -Condition (-not $wsBad) | Out-Null
    Close-Ws $wsBad
} else {
    Write-Host "  (!) WebSocket 연결 실패로 10.3/10.4 스킵. 서버 실행 및 /ws-stomp 등록 여부를 확인하세요." -ForegroundColor Yellow
    Assert-Test -Title "10.3 WebSocket 연결" -Condition $false -Detail "connect 실패" | Out-Null
}
Close-Ws $wsA
Close-Ws $wsB

# ============================================================================
#  4) [REST] 방 목록 / 메시지 이력 조회 (A)
# ============================================================================
Use-Token $tokenA
$rooms = Invoke-Api -Method GET -Path "/api/chat/rooms" -Auth -PassThru -Title "10.5 내 방 목록 조회 (A)"
$myRoom = $null
if ($rooms.data) { $myRoom = @($rooms.data | Where-Object { $_.roomId -eq $roomId })[0] }
Assert-Test -Title "10.5 방 목록에 생성한 방 포함" -Condition ([bool]$myRoom) | Out-Null

$history = Invoke-Api -Method GET -Path "/api/chat/rooms/$roomId/messages?size=30" -Auth -PassThru -Title "10.6 메시지 이력 조회 (A)"
$lastMsgId = $null
if ($history.data -and $history.data.Count -gt 0) { $lastMsgId = $history.data[-1].messageId }
Assert-Test -Title "10.6 이력에 메시지 존재" -Condition ([bool]($history.data -and $history.data.Count -ge 1)) -Detail "count=$($history.data.Count)" | Out-Null

# ============================================================================
#  5) [REST] 안읽음 수 / 읽음 처리 (B)
# ============================================================================
Write-Host "`n[10.7 안읽음/읽음 처리 (B)]" -ForegroundColor Cyan
Use-Token $tokenB
$roomsB1 = Invoke-Api -Method GET -Path "/api/chat/rooms" -Auth -PassThru -Title "10.7 B 방 목록(읽기 전) - unreadCount 확인"
$roomB1 = $null
if ($roomsB1.data) { $roomB1 = @($roomsB1.data | Where-Object { $_.roomId -eq $roomId })[0] }
Assert-Test -Title "10.7 B 안읽음 수 >= 1" -Condition ([bool]($roomB1 -and $roomB1.unreadCount -ge 1)) -Detail "unread=$($roomB1.unreadCount)" | Out-Null

if ($lastMsgId) {
    Invoke-Api -Method POST -Path "/api/chat/rooms/$roomId/read" -Auth -Title "10.8 B 읽음 처리" -Body @{ lastReadMessageId = [long]$lastMsgId } | Out-Null
    $roomsB2 = Invoke-Api -Method GET -Path "/api/chat/rooms" -Auth -PassThru -Title "10.8 B 방 목록(읽은 후) - unreadCount 확인"
    $roomB2 = $null
    if ($roomsB2.data) { $roomB2 = @($roomsB2.data | Where-Object { $_.roomId -eq $roomId })[0] }
    Assert-Test -Title "10.8 B 안읽음 수 0" -Condition ([bool]($roomB2 -and $roomB2.unreadCount -eq 0)) -Detail "unread=$($roomB2.unreadCount)" | Out-Null
} else {
    Write-Host "  (!) lastMsgId 미확보로 읽음 처리 스킵" -ForegroundColor Yellow
}

# ============================================================================
#  정리: 저장 토큰을 A 로 복구 (다른 스크립트가 A 세션을 이어 쓰도록)
# ============================================================================
Use-Token $tokenA
Write-Host "`n[정리] 저장 토큰을 유저 A 로 복구했습니다." -ForegroundColor DarkGray
