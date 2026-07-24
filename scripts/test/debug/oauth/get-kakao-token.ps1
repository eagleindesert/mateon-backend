# get-kakao-token.ps1 - [로컬 테스트 전용] 카카오 인가코드 → 액세스 토큰 자동 획득
# 사용법:
#   powershell -ExecutionPolicy Bypass -File .\get-kakao-token.ps1
#
# 동작 (DB·docker 의존 없이 완전 자립):
#   1) 카카오 authorize URL 을 출력한다(사람이 브라우저로 접속해 로그인/동의).
#   2) 로그인하면 카카오가 redirect_uri(=/debug/oauth)로 인가코드를 보낸다. Spring 디버그
#      컨트롤러가 완료 페이지에 그 인가코드를 노출한다(주소창 ?code=... 에도 남는다).
#   3) 이 스크립트 프롬프트에 그 인가코드를 붙여넣으면 kauth.kakao.com/oauth/token 으로
#      access token 을 교환한다.
#   4) 받은 access token 을 이 폴더(debug/oauth)의 .env 의 MATEON_KAKAO_ACCESS_TOKEN 에 기록한다.
#      → 08_social_kakao.ps1 로 정상 경로를 검증할 때 이 값을 -KakaoAccessToken 으로 넘긴다.
#
# 사전 조건:
#   - 루트 .env 에 debug.oauth.enabled=true (디버그 컨트롤러 활성) 후 백엔드 기동.
#   - 이 폴더의 .env 에 MATEON_KAKAO_REST_API_KEY (+ 필요 시 MATEON_KAKAO_CLIENT_SECRET).
#   - 카카오 콘솔에 Redirect URI(기본 http://localhost:8080/debug/oauth) 등록.

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

# .env 파일에 Key=Value 를 upsert 한다(있으면 교체, 없으면 추가).
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

# --- 설정 로드: 이 폴더의 .env(가장 우선) → 셸 환경변수 → 기본값 ---
$envFile      = Join-Path $PSScriptRoot ".env"
Import-DotEnv -Path $envFile

$restApiKey   = Resolve-MateonConfig -EnvVar "MATEON_KAKAO_REST_API_KEY"
$redirectUri  = Resolve-MateonConfig -EnvVar "MATEON_KAKAO_REDIRECT_URI"  -Default "http://localhost:8080/debug/oauth"
$clientSecret = Resolve-MateonConfig -EnvVar "MATEON_KAKAO_CLIENT_SECRET"
# access token 을 기록할 대상: 이 스크립트와 같은 디렉토리(debug/oauth)의 .env
$targetEnv    = $envFile

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
Write-Host "    로그인하면 /debug/oauth 완료 페이지가 뜹니다. 페이지의 'code:' 값(또는 주소창 ?code=...)이 인가코드입니다." -ForegroundColor DarkGray

# --- 2) 인가코드 수동 입력 (DB 조회 대신 브라우저에서 확인한 값을 붙여넣기) ---
Write-Host "`n[2] 브라우저에서 확인한 인가코드를 붙여넣으세요." -ForegroundColor Cyan
$code = Read-Host "  인가코드"
if ($code) { $code = $code.Trim() }
if (-not $code) {
    Write-Host "(!) 인가코드가 비어 있습니다. 위 [1] 로그인 후 완료 페이지의 code 값을 붙여넣으세요." -ForegroundColor Red
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

# --- 4) 같은 디렉토리(debug/oauth)의 .env 에 MATEON_KAKAO_ACCESS_TOKEN upsert ---
Set-EnvVar -EnvPath $targetEnv -Key "MATEON_KAKAO_ACCESS_TOKEN" -Value $accessToken
Write-Host "`n[4] 같은 디렉토리(debug/oauth)의 .env 에 MATEON_KAKAO_ACCESS_TOKEN 기록 완료:" -ForegroundColor Green
Write-Host "    $targetEnv" -ForegroundColor DarkGray

Write-Host "`n이제 정상 경로 테스트를 실행하세요 (토큰은 이 폴더 .env 에만 있으므로 -KakaoAccessToken 으로 넘깁니다):" -ForegroundColor Magenta
