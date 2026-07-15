# check-cors.ps1 (debug) - CORS 설정이 현재 어떤 오리진까지 허용됐는지 확인
# 사용법: powershell -ExecutionPolicy Bypass -File .\check-cors.ps1
#
# 동작 (다른 테스트 셸(00_common.ps1 등)에 대한 의존 없이 완전 자립):
#
# ── 채팅 연결 구조 (계층) ────────────────────────────────────────────────
# 우리 채팅은 "STOMP over WebSocket (+ SockJS 폴백)" 구조다. 즉 아래처럼 겹겹이 쌓인다:
#
#   STOMP (메시지 프로토콜: CONNECT/SUBSCRIBE/SEND 프레임)
#     └ 전송(Transport) 계층 ← 둘 중 하나로 실어나름
#          ├ (A) 네이티브 WebSocket : ws://.../ws-stomp
#          └ (B) SockJS 폴백        : http://.../ws-stomp/info, /xhr ...
#
# 핵심: STOMP 프로토콜 자체에는 Origin/CORS 개념이 없다(그건 StompAuthChannelInterceptor
# 의 JWT 로 인증한다). CORS 검사는 그 아래 "전송 계층"인 (A) WebSocket 핸드셰이크와
# (B) SockJS HTTP 요청에서 일어난다. 따라서 이 스크립트가 확인하는 CORS 대상은
# 아래 3가지다 - ②③ 이 곧 "STOMP 접속의 CORS" 인 셈이다.
# ─────────────────────────────────────────────────────────────────────────
#
# 1) 일반 REST API CORS (WebSocket 과 무관): SecurityConfig.corsConfigurationSource()
# 는 debug.enabled 값에 따라
#   - true  : 모든 오리진 허용 (AllowedOriginPatterns = "*")
#   - false : http://localhost:3000, http://localhost:5173 만 허용
# 으로 갈린다. 이 스크립트는 실제 서버에 OPTIONS(preflight) 요청을 보내
# "정식으로 등록되지 않은(다른) Origin 헤더"까지 현재 허용(반영)되는지를 확인한다.
#
# 판단 기준: 응답의 Access-Control-Allow-Origin 헤더가
#   - 요청한 Origin 그대로 반영되면 → 그 오리진은 허용된 것
#   - 아예 없으면 → 그 오리진은 차단된 것
#
# 2) (A) 네이티브 WebSocket 전송 CORS: WebSocketConfig.registerStompEndpoints() 도 동일한
# debug.enabled 플래그로 AllowedOrigins/OriginPatterns 를 분기한다. Spring 의
# OriginHandshakeInterceptor 는 실제 WebSocket 업그레이드보다 먼저 Origin 헤더를
# 검사하므로, 순수 GET 요청 + Origin 헤더만으로도(완전한 업그레이드 없이) CORS
# 통과 여부를 판별할 수 있다 (System.Net.WebSockets 가 필요 없어 PowerShell 5.1
# 에서도 동작 - pwsh 7 불필요. [[ps-websocket-needs-pwsh7]] 는 실제 STOMP 연결
# 테스트에만 해당).
#
# 판단 기준: 응답 상태 코드가
#   - 403 Forbidden  → Origin 검사에서 차단된 것
#   - 그 외(예: 400)  → Origin 검사는 통과한 것 (업그레이드 헤더 미비로 인한 400 은 정상)
#
# 3) (B) SockJS 폴백 전송 CORS: withSockJS() 로 등록된 `/ws-stomp/info` 등은 실제 HTTP
# GET 요청이며, Spring 의 AbstractSockJsService 가 자체적으로 Origin 을 검사한다
# (setAllowedOrigins 를 withSockJS() 호출 전에 설정하면 그대로 전달됨). 순수 GET + Origin
# 헤더만으로 검증 가능 (실제 curl/서버 응답으로 검증 완료: 차단 시 403 "Invalid CORS request",
# 허용 시 200 + Access-Control-Allow-Origin 반영).
#
# 판단 기준: 응답의 Access-Control-Allow-Origin 헤더가
#   - 요청한 Origin 그대로 반영되면 → 그 오리진은 허용된 것
#   - 아예 없으면(보통 403) → 그 오리진은 차단된 것

param(
    [string]$Path       = "/health",       # ① 일반 REST preflight 대상 경로 (permitAll 인 헬스체크가 기본값)
    [string]$WsPath     = "/ws-stomp",     # ② 네이티브 WebSocket 핸드셰이크 대상 경로
    [string]$StompPath  = "/ws-stomp/info", # ③ SockJS 폴백(info) 대상 경로
    [string]$BaseUrl                        # 대상 서버 주소. 생략 시 이 폴더의 .env(MATEON_BASE_URL) -> 셸 환경변수 -> localhost:8080 순으로 결정
)

# ============================================================================
#  이 스크립트 전용 최소 헬퍼 (외부 00_common 의존 없이 자립)
# ============================================================================

# .env 파일을 읽어 프로세스 환경변수로 올린다(있을 때만). 값은 항상 덮어써 이 .env 를 우선한다.
function Import-DotEnv {
    param([string]$Path)
    if (-not (Test-Path $Path)) { return }
    foreach ($line in Get-Content -Path $Path) {
        $t = $line.Trim()
        if (-not $t -or $t.StartsWith("#")) { continue }
        $eq = $t.IndexOf("=")
        if ($eq -lt 1) { continue }
        $key = $t.Substring(0, $eq).Trim()
        $val = $t.Substring($eq + 1).Trim()
        # 양쪽 감싼 따옴표 제거
        if ($val.Length -ge 2 -and
            (($val[0] -eq '"' -and $val[-1] -eq '"') -or ($val[0] -eq "'" -and $val[-1] -eq "'"))) {
            $val = $val.Substring(1, $val.Length - 2)
        }
        Set-Item -Path "env:$key" -Value $val
    }
}

# 프로세스 환경변수 값이 있으면 그것을, 없으면 Default 를 돌려준다.
function Resolve-MateonConfig {
    param([string]$EnvVar, $Default = "")
    $v = [System.Environment]::GetEnvironmentVariable($EnvVar, 'Process')
    if ($v) { return $v } else { return $Default }
}

# --- 설정 로드: 이 폴더의 .env(가장 우선) → 셸 환경변수 → 기본값 ---
Import-DotEnv -Path (Join-Path $PSScriptRoot ".env")

if (-not $BaseUrl) {
    $BaseUrl = Resolve-MateonConfig -EnvVar "MATEON_BASE_URL" -Default "http://localhost:8080"
}
$script:BaseUrl = $BaseUrl
$script:Curl    = "curl.exe"

Write-Host "설정 로드 완료. BaseUrl = $script:BaseUrl" -ForegroundColor DarkGray

# 정식 허용 목록(운영 설정 기준)에 있는 오리진
$script:KnownOrigins = @(
    "http://localhost:3000",
    "http://localhost:5173"
)

# 정식 허용 목록에 없는 "다른" 오리진 - 이게 반영되면 CORS 가 풀린 상태(debug.enabled=true)
$script:OtherOrigins = @(
    "http://evil-example.com",
    "https://random-attacker.test",
    "http://localhost:9999"
)

# OPTIONS(preflight) 요청을 보내고 Access-Control-Allow-Origin 값을 확인한다.
function Test-CorsOrigin {
    param(
        [Parameter(Mandatory = $true)][string]$Origin,
        [Parameter(Mandatory = $true)][bool]$Known
    )

    $url = "$script:BaseUrl$Path"
    $curlArgs = @(
        "-s", "-S", "-i",
        "-X", "OPTIONS", $url,
        "-H", "Origin: $Origin",
        "-H", "Access-Control-Request-Method: GET",
        "-H", "Access-Control-Request-Headers: Content-Type"
    )

    $raw = (& $script:Curl @curlArgs) -join "`n"

    $status = $null
    if ($raw -match "HTTP/[\d.]+\s+(\d+)") { $status = $Matches[1] }

    $allowOrigin = $null
    if ($raw -match "(?im)^Access-Control-Allow-Origin:\s*(.+?)\s*$") { $allowOrigin = $Matches[1] }

    $reflected = [bool]($allowOrigin -and ($allowOrigin -eq $Origin -or $allowOrigin -eq "*"))

    $label = if ($Known) { "등록됨" } else { "미등록(다른 오리진)" }
    $resultText = if ($reflected) { "허용됨" } else { "차단됨" }
    $color = if ($reflected) {
        if ($Known) { "Green" } else { "Yellow" }  # 미등록인데 허용되면 경고색
    } else {
        if ($Known) { "Red" } else { "Green" }     # 미등록이 차단되면 정상(초록)
    }

    Write-Host ("  [{0}] Origin: {1,-32} Status: {2,-4} Allow-Origin: {3,-10} -> {4}" -f `
        $label, $Origin, $status, $(if ($allowOrigin) { $allowOrigin } else { "(없음)" }), $resultText) -ForegroundColor $color

    return [pscustomobject]@{
        Origin    = $Origin
        Known     = $Known
        Status    = $status
        AllowOrigin = $allowOrigin
        Reflected = $reflected
    }
}

Write-Host "`n########## ① 일반 REST API CORS 확인 ##########" -ForegroundColor Magenta
Write-Host "  대상: $script:BaseUrl$Path`n" -ForegroundColor DarkGray

$results = New-Object System.Collections.Generic.List[object]

Write-Host "-- 정식 등록된 오리진 --" -ForegroundColor Cyan
foreach ($o in $script:KnownOrigins) {
    $results.Add((Test-CorsOrigin -Origin $o -Known $true))
}

Write-Host "`n-- 정식 등록되지 않은(다른) 오리진 --" -ForegroundColor Cyan
foreach ($o in $script:OtherOrigins) {
    $results.Add((Test-CorsOrigin -Origin $o -Known $false))
}

$otherReflected = @($results | Where-Object { -not $_.Known -and $_.Reflected })

Write-Host ""
Write-Host ("=" * 70) -ForegroundColor DarkGray
if ($otherReflected.Count -gt 0) {
    Write-Host "  결론: CORS 가 현재 '풀려' 있습니다 (미등록 오리진도 허용됨 -> debug.enabled=true 추정)" -ForegroundColor Yellow
} else {
    $knownBlocked = @($results | Where-Object { $_.Known -and -not $_.Reflected })
    if ($knownBlocked.Count -gt 0) {
        Write-Host "  결론: 정식 등록된 오리진 중 일부가 차단되고 있습니다. SecurityConfig 설정을 확인하세요." -ForegroundColor Red
    } else {
        Write-Host "  결론: CORS 가 정상적으로 제한되어 있습니다 (등록된 오리진만 허용, debug.enabled=false 추정)" -ForegroundColor Green
    }
}
Write-Host ("=" * 70) -ForegroundColor DarkGray

# ---------------------------------------------------------------------------
# ② (A) 네이티브 WebSocket 전송 CORS 확인 (STOMP 를 실어나르는 전송 계층)
# ---------------------------------------------------------------------------

# 실제 WebSocket 업그레이드 없이 Origin 검사만 확인한다(GET + Origin/Upgrade 관련 헤더).
# - Origin 이 차단되면 OriginHandshakeInterceptor 가 즉시 403 을 반환한다.
# - Origin 이 허용되면 Origin 검사는 통과하고, curl 은 실제 Sec-WebSocket-Key 등을
#   완전히 협상하지 않으므로 보통 400(Bad Request) 등으로 떨어진다 - 403 만 아니면 통과로 판단.
function Test-WebSocketCorsOrigin {
    param(
        [Parameter(Mandatory = $true)][string]$Origin,
        [Parameter(Mandatory = $true)][bool]$Known
    )

    $url = "$script:BaseUrl$WsPath"
    $curlArgs = @(
        "-s", "-S", "-i", "--max-time", "5",
        "-X", "GET", $url,
        "-H", "Origin: $Origin",
        "-H", "Connection: Upgrade",
        "-H", "Upgrade: websocket",
        "-H", "Sec-WebSocket-Version: 13",
        "-H", "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ=="
    )

    $raw = (& $script:Curl @curlArgs) -join "`n"

    $status = $null
    if ($raw -match "HTTP/[\d.]+\s+(\d+)") { $status = $Matches[1] }

    $blocked = [bool]($status -eq "403")

    $label = if ($Known) { "등록됨" } else { "미등록(다른 오리진)" }
    $resultText = if ($blocked) { "차단됨(403)" } else { "통과됨($status)" }
    $color = if (-not $blocked) {
        if ($Known) { "Green" } else { "Yellow" }  # 미등록인데 통과되면 경고색
    } else {
        if ($Known) { "Red" } else { "Green" }     # 미등록이 차단되면 정상(초록)
    }

    Write-Host ("  [{0}] Origin: {1,-32} Status: {2,-4} -> {3}" -f `
        $label, $Origin, $(if ($status) { $status } else { "(무응답)" }), $resultText) -ForegroundColor $color

    return [pscustomobject]@{
        Origin  = $Origin
        Known   = $Known
        Status  = $status
        Blocked = $blocked
    }
}

Write-Host "`n########## ② 네이티브 WebSocket 전송 CORS 확인 (STOMP 전송 계층 A) ##########" -ForegroundColor Magenta
Write-Host "  대상: $script:BaseUrl$WsPath (Origin 검사만 확인, 실제 업그레이드는 수행하지 않음)`n" -ForegroundColor DarkGray

$wsResults = New-Object System.Collections.Generic.List[object]

Write-Host "-- 정식 등록된 오리진 --" -ForegroundColor Cyan
foreach ($o in $script:KnownOrigins) {
    $wsResults.Add((Test-WebSocketCorsOrigin -Origin $o -Known $true))
}

Write-Host "`n-- 정식 등록되지 않은(다른) 오리진 --" -ForegroundColor Cyan
foreach ($o in $script:OtherOrigins) {
    $wsResults.Add((Test-WebSocketCorsOrigin -Origin $o -Known $false))
}

$wsOtherPassed = @($wsResults | Where-Object { -not $_.Known -and -not $_.Blocked })

Write-Host ""
Write-Host ("=" * 70) -ForegroundColor DarkGray
if ($wsOtherPassed.Count -gt 0) {
    Write-Host "  결론: 네이티브 WebSocket CORS 가 현재 '풀려' 있습니다 (미등록 오리진도 통과 -> debug.enabled=true 추정)" -ForegroundColor Yellow
} else {
    $wsKnownBlocked = @($wsResults | Where-Object { $_.Known -and $_.Blocked })
    if ($wsKnownBlocked.Count -gt 0) {
        Write-Host "  결론: 정식 등록된 오리진 중 일부가 네이티브 WebSocket 에서 차단되고 있습니다. WebSocketConfig 설정을 확인하세요." -ForegroundColor Red
    } else {
        Write-Host "  결론: 네이티브 WebSocket CORS 가 정상적으로 제한되어 있습니다 (등록된 오리진만 통과, debug.enabled=false 추정)" -ForegroundColor Green
    }
}
Write-Host ("=" * 70) -ForegroundColor DarkGray

# ---------------------------------------------------------------------------
# ③ (B) SockJS 폴백 전송 CORS 확인 (/info - STOMP 를 실어나르는 전송 계층)
# ---------------------------------------------------------------------------

# 순수 GET + Origin 헤더로 SockJS 의 자체 CORS 체크를 확인한다.
# - Origin 이 차단되면 AbstractSockJsService 가 403("Invalid CORS request") 을 반환한다.
# - Origin 이 허용되면 200 과 함께 Access-Control-Allow-Origin 이 그대로 반영된다.
function Test-StompCorsOrigin {
    param(
        [Parameter(Mandatory = $true)][string]$Origin,
        [Parameter(Mandatory = $true)][bool]$Known
    )

    $url = "$script:BaseUrl$StompPath"
    $curlArgs = @(
        "-s", "-S", "-i",
        "-X", "GET", $url,
        "-H", "Origin: $Origin"
    )

    $raw = (& $script:Curl @curlArgs) -join "`n"

    $status = $null
    if ($raw -match "HTTP/[\d.]+\s+(\d+)") { $status = $Matches[1] }

    $allowOrigin = $null
    if ($raw -match "(?im)^Access-Control-Allow-Origin:\s*(.+?)\s*$") { $allowOrigin = $Matches[1] }

    $reflected = [bool]($allowOrigin -and ($allowOrigin -eq $Origin -or $allowOrigin -eq "*"))

    $label = if ($Known) { "등록됨" } else { "미등록(다른 오리진)" }
    $resultText = if ($reflected) { "허용됨" } else { "차단됨" }
    $color = if ($reflected) {
        if ($Known) { "Green" } else { "Yellow" }  # 미등록인데 허용되면 경고색
    } else {
        if ($Known) { "Red" } else { "Green" }     # 미등록이 차단되면 정상(초록)
    }

    Write-Host ("  [{0}] Origin: {1,-32} Status: {2,-4} Allow-Origin: {3,-10} -> {4}" -f `
        $label, $Origin, $status, $(if ($allowOrigin) { $allowOrigin } else { "(없음)" }), $resultText) -ForegroundColor $color

    return [pscustomobject]@{
        Origin      = $Origin
        Known       = $Known
        Status      = $status
        AllowOrigin = $allowOrigin
        Reflected   = $reflected
    }
}

Write-Host "`n########## ③ SockJS 폴백 전송 CORS 확인 (STOMP 전송 계층 B) ##########" -ForegroundColor Magenta
Write-Host "  대상: $script:BaseUrl$StompPath`n" -ForegroundColor DarkGray

$stompResults = New-Object System.Collections.Generic.List[object]

Write-Host "-- 정식 등록된 오리진 --" -ForegroundColor Cyan
foreach ($o in $script:KnownOrigins) {
    $stompResults.Add((Test-StompCorsOrigin -Origin $o -Known $true))
}

Write-Host "`n-- 정식 등록되지 않은(다른) 오리진 --" -ForegroundColor Cyan
foreach ($o in $script:OtherOrigins) {
    $stompResults.Add((Test-StompCorsOrigin -Origin $o -Known $false))
}

$stompOtherReflected = @($stompResults | Where-Object { -not $_.Known -and $_.Reflected })

Write-Host ""
Write-Host ("=" * 70) -ForegroundColor DarkGray
if ($stompOtherReflected.Count -gt 0) {
    Write-Host "  결론: SockJS 폴백 CORS 가 현재 '풀려' 있습니다 (미등록 오리진도 허용됨 -> debug.enabled=true 추정)" -ForegroundColor Yellow
} else {
    $stompKnownBlocked = @($stompResults | Where-Object { $_.Known -and -not $_.Reflected })
    if ($stompKnownBlocked.Count -gt 0) {
        Write-Host "  결론: 정식 등록된 오리진 중 일부가 SockJS 폴백에서 차단되고 있습니다. WebSocketConfig 설정을 확인하세요." -ForegroundColor Red
    } else {
        Write-Host "  결론: SockJS 폴백 CORS 가 정상적으로 제한되어 있습니다 (등록된 오리진만 허용, debug.enabled=false 추정)" -ForegroundColor Green
    }
}
Write-Host ("=" * 70) -ForegroundColor DarkGray
