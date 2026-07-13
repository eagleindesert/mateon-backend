# 00_common.ps1
# 모든 테스트 스크립트가 공통으로 사용하는 헬퍼.
# 각 스크립트 상단에서 `. "$PSScriptRoot\00_common.ps1"` 로 로드한다.
#
# - curl.exe 를 사용하여 API 를 호출한다.
# - 로그인 시 발급된 accessToken 을 .auth-token.txt 에 저장/재사용한다.

# ============================================================================
#  설정 (CONFIG) — 여기 Default 만 고치면 모든 스크립트에 반영됩니다.
#  ----------------------------------------------------------------------------
#  각 항목의 우선순위는 아래 $DotEnvOverridesShell 토글로 결정됩니다:
#     * $true  (기본): .env 파일  >  셸 환경변수  >  Default   ← .env 가 이김
#     * $false        : 셸 환경변수  >  .env 파일  >  Default   ← 셸이 이김
#  ($true 여도 .env 에 없는 키는 셸 값이, 그것도 없으면 Default 가 적용됩니다.)
# ============================================================================
$script:DotEnvOverridesShell = $true

$script:MateonConfig = @(
    # Name          Default(기본값)                     EnvVar(셸 환경변수 이름)         # 설명
    @{ Name = "EnvFile";     Default = (Join-Path $PSScriptRoot ".env"); EnvVar = "MATEON_ENV_FILE" }      # .env 파일 경로
    @{ Name = "BaseUrl";     Default = "http://localhost:8080";          EnvVar = "MATEON_BASE_URL" }      # API 서버 주소
    @{ Name = "PgContainer"; Default = "mateon-postgres";                EnvVar = "MATEON_PG_CONTAINER" }  # PostgreSQL 도커 컨테이너
    @{ Name = "PgUser";      Default = "admin";                          EnvVar = "MATEON_PG_USER" }       # psql 접속 계정
    @{ Name = "PgDatabase";  Default = "mateon_db";                      EnvVar = "MATEON_PG_DB" }         # psql 대상 DB 이름
    @{ Name = "JwtSecret";   Default = "";                               EnvVar = "MATEON_JWT_SECRET" }    # (예약) 현재 미사용

    # 테스트 계정 기본값 — 스크립트에 -Email/-Password/-Name 을 안 주면 아래 값이 쓰인다.
    @{ Name = "TestEmail";    Default = "test22@snu.ac.kr";           EnvVar = "MATEON_TEST_EMAIL" }    # 기본 테스트 이메일
    @{ Name = "TestPassword"; Default = "Password1234";                  EnvVar = "MATEON_TEST_PASSWORD" } # 기본 테스트 비밀번호
    @{ Name = "TestName";     Default = "테스트유저";                     EnvVar = "MATEON_TEST_NAME" }     # 기본 테스트 이름

    # 2번째 유저(B) 기본값 — 10_chat.ps1 의 채팅 상대 계정. -UserB* 를 안 주면 아래 값이 쓰인다.
    @{ Name = "UserBEmail";    Default = "chatmate@snu.ac.kr";          EnvVar = "MATEON_USERB_EMAIL" }    # 유저 B 이메일
    @{ Name = "UserBPassword"; Default = "Password1234";                 EnvVar = "MATEON_USERB_PASSWORD" } # 유저 B 비밀번호
    @{ Name = "UserBName";     Default = "채팅메이트";                    EnvVar = "MATEON_USERB_NAME" }     # 유저 B 이름

    # 카카오 실제 액세스 토큰(선택) — 있으면 08_social_kakao.ps1 이 실제 로그인까지 검증한다.
    # (get-kakao-token.ps1 이 인가코드 교환 후 이 값을 .env 에 자동 기록한다.)
    @{ Name = "KakaoAccessToken"; Default = "";                          EnvVar = "MATEON_KAKAO_ACCESS_TOKEN" } # 카카오 access token

    # 참고: 카카오 앱 설정(REST API 키/redirect_uri/client secret)은 디버그 전용이라
    #       여기 두지 않고 ../debug/oauth/get-kakao-token.ps1 이 자기 폴더 .env 로 해석한다.
)

# 셸 환경변수 값이 있으면 그것을, 없으면 Default 를 돌려준다.
function Resolve-MateonConfig {
    param([string]$EnvVar, $Default)
    $v = [System.Environment]::GetEnvironmentVariable($EnvVar, 'Process')
    if ($v) { return $v } else { return $Default }
}

# .env 파일에서 설정을 읽어 환경변수로 로드한다(파일이 있을 때만).
#   $DotEnvOverridesShell 가 $true 면 .env 값으로 항상 덮어써서 .env 를 우선한다.
#   $false 면 셸에 이미 있는 값은 유지하여 셸을 우선한다.
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
        # .env 우선이면 무조건 덮어쓰고, 아니면 셸에 없을 때만 적용한다.
        if ($script:DotEnvOverridesShell -or -not [System.Environment]::GetEnvironmentVariable($key, 'Process')) {
            Set-Item -Path "env:$key" -Value $val
        }
    }
}

# --- 설정 확정 ---
# 1) .env 파일 경로부터 결정(셸 env > Default)한 뒤 .env 로드
$script:EnvFile = Resolve-MateonConfig -EnvVar "MATEON_ENV_FILE" -Default (($script:MateonConfig | Where-Object { $_.Name -eq "EnvFile" }).Default)
Import-DotEnv -Path $script:EnvFile

# 2) 나머지 설정을 $DotEnvOverridesShell 토글에 따른 우선순위로 $script: 변수에 채운다.
#    Import-DotEnv 가 .env 값을 (토글에 따라) 프로세스 env 로 올려주므로 여기서 한 번에 해석된다.
foreach ($c in $script:MateonConfig) {
    if ($c.Name -eq "EnvFile") { continue }  # 위에서 이미 처리
    Set-Variable -Name $c.Name -Value (Resolve-MateonConfig -EnvVar $c.EnvVar -Default $c.Default) -Scope Script
}

$script:TokenFile   = Join-Path $PSScriptRoot ".auth-token.txt"
$script:RefreshFile = Join-Path $PSScriptRoot ".refresh-token.txt"

# 테스트 결과 집계용 전역 트래커.
# 99_run_all.ps1 은 여러 스크립트를 같은 프로세스에서 실행하므로 $global: 로 공유한다.
# (개별 스크립트를 단독 실행하면 새 프로세스마다 비어 있는 상태로 시작한다.)
if (-not (Test-Path variable:global:MateonTestResults)) {
    $global:MateonTestResults = New-Object System.Collections.Generic.List[object]
}

# 집계 초기화 (99_run_all.ps1 시작 시 호출)
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

# 이메일 인증코드 조회(정식 절차 자동화):
#   /api/auth/email/request 가 생성해 email_verifications.code 에 저장한 6자리 코드를,
#   실제 메일 수신 대신 DB 에서 직접 읽어와 /api/auth/email/verify 를 그대로 밟는다.
#   (docker 로 실행 중인 PostgreSQL 컨테이너에 psql 로 SELECT)
#   email 에 unique 제약이 없어 여러 행이 있을 수 있으므로 가장 최근(id 최대) 코드를 읽는다.
function Get-EmailVerificationCode {
    param([Parameter(Mandatory = $true)][string]$Email)
    $sql = "SELECT code FROM email_verifications WHERE email='$Email' ORDER BY id DESC LIMIT 1;"
    # -t: 튜플만, -A: 정렬 없이 값만 → 순수 코드 문자열만 얻는다.
    $out = docker exec $script:PgContainer psql -U $script:PgUser -d $script:PgDatabase -t -A -c $sql
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  (!) 인증코드 조회 실패 - 컨테이너 '$script:PgContainer' 확인 (docker ps)" -ForegroundColor Red
        return $null
    }
    $code = ($out | Out-String).Trim()
    if (-not $code) {
        Write-Host "  (!) 인증코드가 DB 에 없습니다: $Email (email/request 성공 여부 확인)" -ForegroundColor Red
        return $null
    }
    return $code
}

# psql 을 도커 컨테이너 안에서 실행하는 범용 헬퍼. 성공 시 $true.
function Invoke-PgSql {
    param([Parameter(Mandatory = $true)][string]$Sql)
    docker exec $script:PgContainer psql -U $script:PgUser -d $script:PgDatabase -c $Sql | Out-Null
    return ($LASTEXITCODE -eq 0)
}

# 유저의 학교 인증 상태를 강제로 바꾼다(테스트용).
#   소셜만 로그인한 '미인증' 상태를 재현하려면 -Verified:$false 로 호출.
function Set-SchoolVerified {
    param(
        [Parameter(Mandatory = $true)][string]$Email,
        [bool]$Verified = $true
    )
    $flag = if ($Verified) { "true" } else { "false" }
    $sql = if ($Verified) {
        "UPDATE users SET school_verified=true, school_email=email WHERE email='$Email';"
    } else {
        "UPDATE users SET school_verified=false, school_email=NULL WHERE email='$Email';"
    }
    if (Invoke-PgSql -Sql $sql) {
        Write-Host "  (i) school_verified=$flag 설정 완료: $Email" -ForegroundColor Green
        return $true
    } else {
        Write-Host "  (!) school_verified 설정 실패 - 컨테이너 '$script:PgContainer' 확인 (docker ps)" -ForegroundColor Red
        return $false
    }
}

# 학교 이메일 인증코드를 '미검증(verified=false)' 상태로 알려진 코드로 심는다(테스트용).
#   실제 request 엔드포인트는 랜덤 코드를 발급하므로, 메일 없이 verify 를 테스트하려면
#   이 헬퍼로 코드를 직접 심은 뒤 그 코드로 /school/email/verify 를 호출한다.
function Grant-SchoolEmailCode {
    param(
        [Parameter(Mandatory = $true)][string]$Email,
        [string]$Code = "000000"
    )
    $sql = "DELETE FROM email_verifications WHERE email='$Email'; " +
           "INSERT INTO email_verifications(code, email, expires_at, verified) " +
           "VALUES ('$Code','$Email', NOW() + INTERVAL '1 day', false);"
    if (Invoke-PgSql -Sql $sql) {
        Write-Host "  (i) 학교 이메일 인증코드 심기 완료: $Email (code=$Code, verified=false)" -ForegroundColor Green
        return $true
    } else {
        Write-Host "  (!) 학교 이메일 인증코드 심기 실패 - 컨테이너 '$script:PgContainer' 확인 (docker ps)" -ForegroundColor Red
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
