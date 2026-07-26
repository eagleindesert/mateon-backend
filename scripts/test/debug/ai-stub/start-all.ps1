# start-all.ps1
# AI 스텁과 백엔드를 한 번에 띄운다. (터미널 두 개를 오가지 않기 위한 편의 스크립트)
#
# 스텁은 별도 창에서 돌린다 — 이 도구의 존재 이유가 "백엔드가 보내는 요청을 눈으로 확인하는 것"
# 이라(README 참고), 요청 덤프가 백엔드 로그와 섞이면 쓸모가 없다. 백엔드는 이 터미널에서
# 포그라운드로 돈다.
#
# .env 는 건드리지 않는다. AI_BASE_URL 대신 커맨드라인 인자(--ai.base-url)로 넘기는데,
# Spring 의 프로퍼티 우선순위상 커맨드라인 인자가 .env(spring.config.import)보다 높아서
# 이 실행에만 적용된다. .env 에 실서버 주소가 들어 있어도 안전하고, 끝난 뒤 되돌릴 것도 없다.
#
# 사용법 (pwsh 7 이상):
#   pwsh -File scripts/test/debug/ai-stub/start-all.ps1
#   pwsh -File scripts/test/debug/ai-stub/start-all.ps1 -Port 8001
#   pwsh -File scripts/test/debug/ai-stub/start-all.ps1 -ExpectedSecret "dev-secret"
#
# 중지: 이 터미널에서 Ctrl+C. 백엔드가 끝나면 스텁 창도 함께 닫는다.

[CmdletBinding()]
param(
    # 스텁 리스닝 포트. 백엔드의 ai.base-url 도 같은 값으로 맞춰서 넘긴다.
    [int]$Port = 8000,

    # 임베딩 차원. 기본값과 다르게 주면 백엔드가 차원 불일치로 거른다 (그 동작을 테스트할 때만 변경).
    [int]$EmbeddingDimension = 1536,

    # 주면 스텁이 X-Internal-Secret 을 검증한다. .env 의 AI_INTERNAL_SECRET 과 같은 값이어야 한다.
    [string]$ExpectedSecret = ""
)

$ErrorActionPreference = 'Stop'

# HttpListener 기반 스텁이 pwsh 7 을 요구하므로 런처도 같은 기준을 적용한다.
if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw "pwsh 7 이상에서 실행하세요. (현재: $($PSVersionTable.PSVersion))"
}

$repoRoot   = (Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
$stubScript = Join-Path $PSScriptRoot 'stub-ai-server.ps1'
$gradlew    = Join-Path $repoRoot 'gradlew.bat'
$envFile    = Join-Path $repoRoot '.env'

# 끝에 슬래시를 붙이면 안 된다. 클라이언트가 baseUrl + "/intents/extract" 로 단순 결합하므로
# "http://localhost:8000/" 은 "//intents/extract" 가 되어 스텁의 라우팅에 걸리지 않는다.
$baseUrl = "http://localhost:$Port"

function Test-PortOpen([int]$TargetPort) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $client.Connect('127.0.0.1', $TargetPort)
        return $true
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

# 백엔드는 AI_INTERNAL_SECRET 이 없으면 부팅 자체가 실패한다(AiServerProperties.validateInternalSecret).
# 스텁을 띄우고 gradle 빌드까지 기다린 뒤에 그걸 알게 되면 시간 낭비라 먼저 확인한다.
if (-not (Test-Path $envFile)) {
    throw ".env 가 없습니다: $envFile  (AI_INTERNAL_SECRET 이 필요합니다)"
}
if (-not ((Get-Content $envFile) -match '^\s*AI_INTERNAL_SECRET\s*=\s*\S')) {
    throw ".env 의 AI_INTERNAL_SECRET 이 비어 있습니다. 값은 아무거나 좋습니다(스텁은 -ExpectedSecret 을 줄 때만 검증)."
}

if (Test-PortOpen $Port) {
    throw "포트 $Port 가 이미 사용 중입니다. 스텁이 이미 떠 있다면 그 창을 쓰거나, -Port 로 다른 포트를 지정하세요."
}

$stub = $null
try {
    # ---- 1/2. 스텁 기동 (별도 창) ----
    Write-Host "[1/2] AI 스텁 기동 → $baseUrl" -ForegroundColor Cyan

    # -NoExit: 스텁이 죽어도 창이 남아 마지막 에러를 읽을 수 있다.
    $stubArgs = @('-NoExit', '-File', $stubScript, '-Port', $Port, '-EmbeddingDimension', $EmbeddingDimension)
    if ($ExpectedSecret) { $stubArgs += @('-ExpectedSecret', $ExpectedSecret) }
    $stub = Start-Process pwsh -ArgumentList $stubArgs -PassThru

    # 리스닝을 시작하기 전에 백엔드가 호출하면 연결 거부가 나므로 준비될 때까지 기다린다.
    $deadline = (Get-Date).AddSeconds(15)
    while (-not (Test-PortOpen $Port)) {
        if ($stub.HasExited) { throw "스텁이 기동 중 종료됐습니다. 스텁 창의 에러를 확인하세요." }
        if ((Get-Date) -gt $deadline) { throw "스텁이 15초 안에 포트 $Port 를 열지 않았습니다." }
        Start-Sleep -Milliseconds 300
    }
    Write-Host "      스텁 준비 완료 (PID $($stub.Id))" -ForegroundColor DarkGray

    # ---- 2/2. 백엔드 기동 (이 터미널, 포그라운드) ----
    Write-Host "[2/2] 백엔드 bootRun (--ai.base-url=$baseUrl)" -ForegroundColor Cyan
    Write-Host "      .env 는 수정하지 않습니다. 중지는 Ctrl+C." -ForegroundColor DarkGray

    Push-Location $repoRoot
    try {
        & $gradlew bootRun "--args=--ai.base-url=$baseUrl"
    } finally {
        Pop-Location
    }
} finally {
    # 백엔드가 끝나면(정상 종료든 Ctrl+C 든) 스텁도 같이 정리한다.
    # 남겨두면 다음 실행에서 포트 충돌로 막힌다.
    if ($stub -and -not $stub.HasExited) {
        Write-Host "스텁 종료 (PID $($stub.Id))" -ForegroundColor DarkGray
        Stop-Process -Id $stub.Id -Force -ErrorAction SilentlyContinue
    }
}
