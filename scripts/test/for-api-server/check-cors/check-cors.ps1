# check-cors.ps1 (for-api-server) - CORS 설정이 현재 어떤 오리진까지 허용됐는지 확인
# 사용법: powershell -ExecutionPolicy Bypass -File .\check-cors\check-cors.ps1
#
# SecurityConfig.corsConfigurationSource() 는 debug.enabled 값에 따라
#   - true  : 모든 오리진 허용 (AllowedOriginPatterns = "*")
#   - false : http://localhost:3000, http://localhost:5173 만 허용
# 으로 갈린다. 이 스크립트는 실제 서버에 OPTIONS(preflight) 요청을 보내
# "정식으로 등록되지 않은(다른) Origin 헤더"까지 현재 허용(반영)되는지를 확인한다.
#
# 판단 기준: 응답의 Access-Control-Allow-Origin 헤더가
#   - 요청한 Origin 그대로 반영되면 → 그 오리진은 허용된 것
#   - 아예 없으면 → 그 오리진은 차단된 것

param(
    [string]$Path = "/health"   # preflight 대상 경로 (permitAll 인 헬스체크가 기본값)
)

# check-cors/ 하위 폴더에서 실행되므로 부모의 00_common.ps1 을 참조한다 (auth/, parallel-chat/ 와 동일한 패턴)
. "$PSScriptRoot\..\00_common.ps1"

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

Write-Host "`n########## CORS 설정 확인 ##########" -ForegroundColor Magenta
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
