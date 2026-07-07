# _common.ps1
# 모든 테스트 스크립트가 공통으로 사용하는 헬퍼.
# 각 스크립트 상단에서 `. "$PSScriptRoot\_common.ps1"` 로 로드한다.
#
# - curl.exe 를 사용하여 API 를 호출한다.
# - 로그인 시 발급된 accessToken 을 .auth-token.txt 에 저장/재사용한다.

# 기본 설정 (환경변수로 덮어쓰기 가능)
if (-not $script:BaseUrl) {
    if ($env:MATEON_BASE_URL) { $script:BaseUrl = $env:MATEON_BASE_URL }
    else { $script:BaseUrl = "http://localhost:8080" }
}

$script:TokenFile   = Join-Path $PSScriptRoot ".auth-token.txt"
$script:RefreshFile = Join-Path $PSScriptRoot ".refresh-token.txt"

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

    $statusColor = if ($status -and [int]$status -lt 400) { "Green" } else { "Red" }
    Write-Host "  Status: $status" -ForegroundColor $statusColor

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
