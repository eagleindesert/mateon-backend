# _common.ps1
# 모든 테스트 스크립트가 공통으로 사용하는 헬퍼.
# 각 스크립트 상단에서 `. "$PSScriptRoot\_common.ps1"` 로 로드한다.
#
# - curl.exe 를 사용하여 API 를 호출한다.
# - 로그인 시 발급된 accessToken 을 .auth-token.txt 에 저장/재사용한다.

# .env 파일에서 설정을 읽어 환경변수로 로드한다(파일이 있을 때만).
#   우선순위: 이미 지정된 셸 환경변수 > .env 파일 값 (셸이 우선)
#   경로: 기본 $PSScriptRoot\.env (MATEON_ENV_FILE 로 변경 가능)
#   지원 키 예: MATEON_BASE_URL / MATEON_PG_CONTAINER / MATEON_JWT_SECRET
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
        # 셸에 이미 설정돼 있으면 유지(셸 우선), 없을 때만 .env 값 적용
        if (-not [System.Environment]::GetEnvironmentVariable($key, 'Process')) {
            Set-Item -Path "env:$key" -Value $val
        }
    }
}

$script:EnvFile = if ($env:MATEON_ENV_FILE) { $env:MATEON_ENV_FILE } else { Join-Path $PSScriptRoot ".env" }
Import-DotEnv -Path $script:EnvFile

# 기본 설정 (환경변수로 덮어쓰기 가능)
if (-not $script:BaseUrl) {
    if ($env:MATEON_BASE_URL) { $script:BaseUrl = $env:MATEON_BASE_URL }
    else { $script:BaseUrl = "http://localhost:8080" }
}

$script:TokenFile   = Join-Path $PSScriptRoot ".auth-token.txt"
$script:RefreshFile = Join-Path $PSScriptRoot ".refresh-token.txt"

# 테스트 결과 집계용 전역 트래커.
# run_all.ps1 은 6개 스크립트를 같은 프로세스에서 실행하므로 $global: 로 공유한다.
# (개별 스크립트를 단독 실행하면 새 프로세스마다 비어 있는 상태로 시작한다.)
if (-not (Test-Path variable:global:MateonTestResults)) {
    $global:MateonTestResults = New-Object System.Collections.Generic.List[object]
}

# 집계 초기화 (run_all.ps1 시작 시 호출)
function Reset-TestResults {
    $global:MateonTestResults = New-Object System.Collections.Generic.List[object]
}

# 성공/실패 개수와 실패 목록을 출력한다.
function Write-TestSummary {
    $results = $global:MateonTestResults
    $total   = $results.Count
    $failedItems = @($results | Where-Object { -not $_.Ok })
    $passed  = $total - $failedItems.Count

    Write-Host ""
    Write-Host ("=" * 70) -ForegroundColor DarkGray
    Write-Host " 테스트 요약 (Test Summary)" -ForegroundColor Magenta
    Write-Host ("=" * 70) -ForegroundColor DarkGray
    Write-Host ("  전체: {0}" -f $total) -ForegroundColor White
    Write-Host ("  성공: {0}" -f $passed) -ForegroundColor Green
    Write-Host ("  실패: {0}" -f $failedItems.Count) -ForegroundColor $(if ($failedItems.Count -gt 0) { "Red" } else { "Green" })

    if ($failedItems.Count -gt 0) {
        Write-Host ""
        Write-Host "  실패한 항목:" -ForegroundColor Red
        foreach ($f in $failedItems) {
            $statusText = if ($f.Status) { $f.Status } else { "NO_RESPONSE" }
            Write-Host ("    - [{0}] {1}  ({2} {3}) -> Status {4}" -f `
                $statusText, $f.Title, $f.Method, $f.Path, $statusText) -ForegroundColor Red
        }
    } else {
        Write-Host ""
        Write-Host "  모든 테스트 통과 🎉" -ForegroundColor Green
    }
    Write-Host ("=" * 70) -ForegroundColor DarkGray

    return $failedItems.Count
}

# curl.exe 실행 파일 확인
$script:Curl = "curl.exe"

# 이메일 인증 우회에 사용할 PostgreSQL 도커 컨테이너 이름 (docker-compose.yml 기준)
if (-not $script:PgContainer) {
    if ($env:MATEON_PG_CONTAINER) { $script:PgContainer = $env:MATEON_PG_CONTAINER }
    else { $script:PgContainer = "mateon-postgres" }
}

# 이메일 인증 우회:
#   서버의 signup 로직은 email_verifications.verified 플래그만 확인하므로,
#   verified=true 행을 DB 에 직접 삽입하면 실제 메일 수신 없이 회원가입이 가능하다.
#   (docker 로 실행 중인 PostgreSQL 컨테이너에 psql 로 직접 INSERT)
function Grant-EmailVerification {
    param([Parameter(Mandatory = $true)][string]$Email)
    $sql = "DELETE FROM email_verifications WHERE email='$Email'; " +
           "INSERT INTO email_verifications(code, email, expires_at, verified) " +
           "VALUES ('000000','$Email', NOW() + INTERVAL '1 day', true);"
    docker exec $script:PgContainer psql -U admin -d mateon_db -c $sql | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "  (i) 이메일 인증 우회 완료: $Email (verified=true 직접 삽입)" -ForegroundColor Green
        return $true
    } else {
        Write-Host "  (!) 이메일 인증 우회 실패 - 컨테이너 '$script:PgContainer' 확인 (docker ps)" -ForegroundColor Red
        return $false
    }
}

function Save-AccessToken {
    param([string]$Token)
    if ($Token) { Set-Content -Path $script:TokenFile -Value $Token -Encoding utf8 }
}

function Save-RefreshToken {
    param([string]$Token)
    if ($Token) { Set-Content -Path $script:RefreshFile -Value $Token -Encoding utf8 }
}

function Get-AccessToken {
    if (Test-Path $script:TokenFile) { return (Get-Content -Path $script:TokenFile -Raw).Trim() }
    return $null
}

function Get-RefreshToken {
    if (Test-Path $script:RefreshFile) { return (Get-Content -Path $script:RefreshFile -Raw).Trim() }
    return $null
}

# JWT accessToken 의 payload 에서 subject(sub) 를 추출한다.
#   서버 리팩터링(A안) 이후 subject 는 email 이 아니라 userId(숫자)여야 한다.
#   base64url 디코딩 후 JSON 의 sub 필드를 반환한다.
function Get-JwtSubject {
    param([string]$Token)
    if (-not $Token) { return $null }
    $parts = $Token.Split(".")
    if ($parts.Count -lt 2) { return $null }
    $payload = $parts[1].Replace("-", "+").Replace("_", "/")
    switch ($payload.Length % 4) {
        2 { $payload += "==" }
        3 { $payload += "=" }
    }
    try {
        $bytes = [System.Convert]::FromBase64String($payload)
        $json  = [System.Text.Encoding]::UTF8.GetString($bytes)
        return ($json | ConvertFrom-Json).sub
    } catch {
        return $null
    }
}

# 서버 응답 없이 클라이언트에서 조건을 검증하고 결과를 집계에 추가한다.
#   (Invoke-Api 와 동일하게 $global:MateonTestResults 에 기록되어 요약에 포함된다.)
function Assert-Test {
    param(
        [Parameter(Mandatory = $true)][string]$Title,
        [Parameter(Mandatory = $true)][bool]$Condition,
        [string]$Detail
    )
    $ok    = [bool]$Condition
    $tag   = if ($ok) { "PASS" } else { "FAIL" }
    $color = if ($ok) { "Green" } else { "Red" }
    $line  = "  [$tag] $Title"
    if ($Detail) { $line += " - $Detail" }
    Write-Host $line -ForegroundColor $color

    $global:MateonTestResults.Add([pscustomobject]@{
        Title  = $Title
        Method = "ASSERT"
        Path   = ""
        Status = $tag
        Ok     = $ok
    })
    return $ok
}

# API 호출 헬퍼
#   -Method  : GET/POST/PUT/PATCH/DELETE
#   -Path    : "/api/..." (BaseUrl 뒤에 붙는다)
#   -Body    : PSObject/Hashtable (JSON 으로 직렬화)
#   -Auth    : $true 이면 저장된 accessToken 을 Authorization 헤더로 추가
#   -Title   : 콘솔에 출력할 테스트 이름
function Invoke-Api {
    param(
        [string]$Method = "GET",
        [Parameter(Mandatory = $true)][string]$Path,
        $Body,
        [switch]$Auth,
        [switch]$PassThru,   # 지정 시 파싱된 응답 객체를 반환 (미지정 시 콘솔 출력만)
        [string]$Title
    )

    $url = "$script:BaseUrl$Path"
    if ($Title) {
        Write-Host ""
        Write-Host ("=" * 70) -ForegroundColor DarkGray
        Write-Host "[$Method] $Title" -ForegroundColor Cyan
        Write-Host "  -> $url" -ForegroundColor DarkGray
    }

    $curlArgs = @("-s", "-S", "-w", "`nHTTP_STATUS:%{http_code}", "-X", $Method, $url)
    $curlArgs += @("-H", "Content-Type: application/json")

    if ($Auth) {
        $token = Get-AccessToken
        if (-not $token) {
            Write-Host "  (!) 저장된 accessToken 이 없습니다. 먼저 02_auth.ps1 로그인 테스트를 실행하세요." -ForegroundColor Yellow
        } else {
            $curlArgs += @("-H", "Authorization: Bearer $token")
        }
    }

    # 요청 본문은 임시 파일로 전달한다.
    # (PowerShell 5.1 은 큰따옴표가 포함된 인자를 curl.exe 같은 네이티브 exe 로 넘길 때
    #  따옴표를 누락시켜 JSON 이 깨진다. --data-binary "@파일" 로 우회한다.)
    $bodyFile = $null
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 10 -Compress
        $bodyFile = New-TemporaryFile
        # BOM 없는 UTF-8 로 기록 (BOM 이 있으면 서버 JSON 파서가 실패할 수 있음)
        [System.IO.File]::WriteAllText($bodyFile.FullName, $json, (New-Object System.Text.UTF8Encoding($false)))
        $curlArgs += @("--data-binary", "@$($bodyFile.FullName)")
        Write-Host "  Request Body: $json" -ForegroundColor DarkGray
    }

    # curl.exe 의 다중 라인 출력은 문자열 배열로 캡처되므로 단일 문자열로 합친다.
    try {
        $raw = (& $script:Curl @curlArgs) -join "`n"
    } finally {
        if ($bodyFile) { Remove-Item $bodyFile.FullName -Force -ErrorAction SilentlyContinue }
    }

    # 응답 본문과 상태코드 분리
    $status = $null
    $bodyText = $raw
    if ($raw -match "HTTP_STATUS:(\d+)\s*$") {
        $status = $Matches[1]
        $bodyText = ($raw -replace "HTTP_STATUS:\d+\s*$", "").Trim()
    }

    $ok = [bool]($status -and [int]$status -lt 400)
    $statusColor = if ($ok) { "Green" } else { "Red" }
    Write-Host "  Status: $status" -ForegroundColor $statusColor

    # 결과 집계 (요약 출력에 사용)
    $global:MateonTestResults.Add([pscustomobject]@{
        Title  = if ($Title) { $Title } else { "$Method $Path" }
        Method = $Method
        Path   = $Path
        Status = $status
        Ok     = $ok
    })

    # 본문을 JSON 으로 예쁘게 출력 (실패 시 원문 그대로)
    $result = $null
    if ($bodyText) {
        try {
            $result = $bodyText | ConvertFrom-Json
            Write-Host "  Response:" -ForegroundColor DarkGray
            ($result | ConvertTo-Json -Depth 10) -split "`n" | ForEach-Object { Write-Host "    $_" }
        } catch {
            Write-Host "  Response: $bodyText" -ForegroundColor DarkGray
            $result = $bodyText
        }
    }

    # -PassThru 를 준 경우에만 파이프라인으로 반환 (미지정 시 콘솔에 표가 중복 출력되지 않도록)
    if ($PassThru) { return $result }
}

Write-Host "공통 설정 로드 완료. BaseUrl = $script:BaseUrl" -ForegroundColor DarkGray
