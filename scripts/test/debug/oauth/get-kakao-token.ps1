# get-kakao-token.ps1 - [로컬 테스트 전용] 카카오 인가코드 → 액세스 토큰 자동 획득
# 사용법:
#   powershell -ExecutionPolicy Bypass -File .\get-kakao-token.ps1
#
# 동작:
#   1) 카카오 authorize URL 을 출력한다(사람이 브라우저로 접속해 로그인/동의).
#   2) 로그인 후 카카오가 redirect_uri(=/debug/oauth)로 인가코드를 보내면, Spring 디버그
#      컨트롤러가 그 코드를 DB(oauth_debug_codes)에 저장한다.
#   3) 이 스크립트가 docker exec psql 로 인가코드를 읽어, kauth.kakao.com/oauth/token 으로
#      access token 을 교환한다.
#   4) 받은 access token 을 for-rest-api/.env 의 MATEON_KAKAO_ACCESS_TOKEN 에 기록한다.
#      → 이후 08_social_kakao.ps1 이 실제 카카오 로그인 정상 경로를 검증할 수 있다.
#
# 사전 조건:
#   - 루트 .env 에 debug.oauth.enabled=true (디버그 컨트롤러 활성) 후 백엔드 기동.
#   - 이 폴더의 .env 에 MATEON_KAKAO_REST_API_KEY (+ 필요 시 MATEON_KAKAO_CLIENT_SECRET).
#   - 카카오 콘솔에 Redirect URI(기본 http://localhost:8080/debug/oauth) 등록.
#   - 로컬 PostgreSQL 도커 컨테이너 접근 가능(인가코드 읽기에 psql 사용).

# 공통 DB 헬퍼/Import-DotEnv/Resolve-MateonConfig 재사용 (for-rest-api/00_common.ps1).
. "$PSScriptRoot\..\..\for-rest-api\00_common.ps1"

# 이 디버그 셸 전용 .env(get-kakao-token 바로 옆)를 '가장 마지막'에 로드해 최우선 적용한다.
#   00_common 이 for-rest-api/.env·셸 값을 이미 프로세스 env 로 올렸는데, 그 뒤에 덮어써서
#   이 .env 가 항상 이긴다. → 카카오 앱 시크릿은 디버그 도구 옆 .env 에만 두면 된다.
$script:DotEnvOverridesShell = $true
Import-DotEnv -Path (Join-Path $PSScriptRoot ".env")

$restApiKey   = Resolve-MateonConfig -EnvVar "MATEON_KAKAO_REST_API_KEY"  -Default ""
$redirectUri  = Resolve-MateonConfig -EnvVar "MATEON_KAKAO_REDIRECT_URI"  -Default "http://localhost:8080/debug/oauth"
$clientSecret = Resolve-MateonConfig -EnvVar "MATEON_KAKAO_CLIENT_SECRET" -Default ""
# access token 을 기록할 대상: 08_social_kakao.ps1 이 읽는 for-rest-api/.env
$targetEnv    = Join-Path $PSScriptRoot "..\..\for-rest-api\.env"

Write-Host "`n########## 카카오 액세스 토큰 자동 획득 ##########" -ForegroundColor Magenta

if (-not $restApiKey) {
    Write-Host "(!) MATEON_KAKAO_REST_API_KEY 가 없습니다. 이 폴더의 .env(debug/oauth/.env) 에 설정하세요." -ForegroundColor Red
    return
}

# --- 1) authorize URL 안내 ---
$authorizeUrl = "https://kauth.kakao.com/oauth/authorize?response_type=code" +
                "&client_id=$restApiKey&redirect_uri=$redirectUri"
Write-Host "`n[1] 아래 URL 을 브라우저로 열어 카카오 로그인/동의를 진행하세요:" -ForegroundColor Cyan
Write-Host "    $authorizeUrl" -ForegroundColor Yellow
Write-Host "    (로그인하면 /debug/oauth 로 리다이렉트되며 인가코드가 DB 에 저장됩니다.)" -ForegroundColor DarkGray

# --- 2) DB 에서 인가코드 읽기 ---
Write-Host "`n[2] DB 에서 인가코드 읽는 중..." -ForegroundColor Cyan
$code = (docker exec $script:PgContainer psql -U $script:PgUser -d $script:PgDatabase -t -A -c `
    "SELECT code FROM oauth_debug_codes ORDER BY created_at DESC LIMIT 1;" 2>$null | Select-Object -First 1)
if ($code) { $code = $code.Trim() }

if (-not $code) {
    Write-Host "(!) 저장된 인가코드가 없습니다. 위 [1] 의 URL 로 먼저 로그인한 뒤 다시 실행하세요." -ForegroundColor Red
    Write-Host "    (컨테이너 '$script:PgContainer' 접근 실패일 수도 있음 → docker ps 확인)" -ForegroundColor DarkGray
    return
}
Write-Host "  (i) 인가코드 확보: $($code.Substring(0, [Math]::Min(12, $code.Length)))..." -ForegroundColor Green

# --- 3) 액세스 토큰 교환 ---
Write-Host "`n[3] kauth.kakao.com/oauth/token 으로 교환 중..." -ForegroundColor Cyan
$curlArgs = @(
    "-s", "-X", "POST", "https://kauth.kakao.com/oauth/token",
    "-H", "Content-Type: application/x-www-form-urlencoded",
    "--data-urlencode", "grant_type=authorization_code",
    "--data-urlencode", "client_id=$restApiKey",
    "--data-urlencode", "redirect_uri=$redirectUri",
    "--data-urlencode", "code=$code"
)
if ($clientSecret) { $curlArgs += @("--data-urlencode", "client_secret=$clientSecret") }

$raw = (& curl.exe @curlArgs) -join "`n"
$resp = $null
try { $resp = $raw | ConvertFrom-Json } catch {}

if (-not $resp -or -not $resp.access_token) {
    Write-Host "(!) 액세스 토큰 교환 실패. 카카오 응답:" -ForegroundColor Red
    Write-Host "    $raw" -ForegroundColor DarkGray
    Write-Host "    (흔한 원인: 인가코드 만료/재사용, redirect_uri 불일치, client_secret 필요)" -ForegroundColor DarkGray
    return
}
$accessToken = $resp.access_token
Write-Host "  (i) access token 획득 (expires_in=$($resp.expires_in)s)" -ForegroundColor Green

# --- 4) for-rest-api/.env 에 MATEON_KAKAO_ACCESS_TOKEN upsert ---
function Set-EnvVar {
    param([string]$EnvPath, [string]$Key, [string]$Value)
    $lines = if (Test-Path $EnvPath) { @(Get-Content -Path $EnvPath) } else { @() }
    $found = $false
    $out = foreach ($l in $lines) {
        if ($l -match "^\s*$([regex]::Escape($Key))\s*=") { $found = $true; "$Key=$Value" } else { $l }
    }
    if (-not $found) { $out = @($out) + "$Key=$Value" }
    [System.IO.File]::WriteAllLines($EnvPath, @($out), (New-Object System.Text.UTF8Encoding($false)))
}

Set-EnvVar -EnvPath $targetEnv -Key "MATEON_KAKAO_ACCESS_TOKEN" -Value $accessToken
Write-Host "`n[4] for-rest-api/.env 에 MATEON_KAKAO_ACCESS_TOKEN 기록 완료:" -ForegroundColor Green
Write-Host "    $targetEnv" -ForegroundColor DarkGray

# --- 5) 사용한 인가코드 정리(일회성) ---
Invoke-PgSql -Sql "DELETE FROM oauth_debug_codes;" | Out-Null

Write-Host "`n이제 정상 경로 테스트를 실행하세요:" -ForegroundColor Magenta
Write-Host "    powershell -ExecutionPolicy Bypass -File ..\..\for-rest-api\08_social_kakao.ps1" -ForegroundColor Yellow
