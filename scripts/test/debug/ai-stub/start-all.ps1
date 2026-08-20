# start-all.ps1
# AI 스텁들과 백엔드를 한 번에 띄운다. (터미널 세 개를 오가지 않기 위한 편의 스크립트)
#
# 스텁이 둘인 이유 — 백엔드는 서로 다른 두 서버에 말을 건다.
#   stub-ai-server.ps1        : 우리 FastAPI 규약 (POST /intents/extract 등)  → ai.base-url
#   stub-spring-ai-server.ps1 : OpenAI 호환 규약 (POST /v1/chat/completions)  → spring.ai.openai.base-url
# 라우터만 OpenAI 를 직접 부르고 나머지 AI 작업은 전부 FastAPI 라서, 한 스텁으로는 못 덮는다.
#
# 스텁은 각자 별도 창에서 돌린다 — 이 도구의 존재 이유가 "백엔드가 보내는 요청을 눈으로 확인하는
# 것"이라(README 참고), 요청 덤프가 서로 섞이거나 백엔드 로그와 섞이면 쓸모가 없다.
# 백엔드는 이 터미널에서 포그라운드로 돈다.
#
# .env 는 건드리지 않는다. 주소도 키도 전부 커맨드라인 인자로 넘기는데, Spring 의 프로퍼티
# 우선순위상 커맨드라인 인자가 .env(spring.config.import)보다 높아서 이 실행에만 적용된다.
# .env 에 실서버 주소나 진짜 키가 들어 있어도 안전하고, 끝난 뒤 되돌릴 것도 없다.
#
# 사용법 (pwsh 7 이상):
#   pwsh -File scripts/test/debug/ai-stub/start-all.ps1
#   pwsh -File scripts/test/debug/ai-stub/start-all.ps1 -Port 8010 -RouterPort 8011
#   pwsh -File scripts/test/debug/ai-stub/start-all.ps1 -ExpectedSecret "dev-secret"
#   pwsh -File scripts/test/debug/ai-stub/start-all.ps1 -RouterForceDomain OUT_OF_SCOPE
#   pwsh -File scripts/test/debug/ai-stub/start-all.ps1 -RouterFailureMode broken-json
#   pwsh -File scripts/test/debug/ai-stub/start-all.ps1 -NoRouterStub   # 라우터는 실서버/폴백대로
#
# 중지: 이 터미널에서 Ctrl+C. 백엔드가 끝나면 스텁 창들도 함께 닫는다.

[CmdletBinding()]
param(
    # FastAPI 스텁 포트. 백엔드의 ai.base-url 도 같은 값으로 맞춰서 넘긴다.
    [int]$Port = 8000,

    # 임베딩 차원. 기본값과 다르게 주면 백엔드가 차원 불일치로 거른다 (그 동작을 테스트할 때만 변경).
    [int]$EmbeddingDimension = 1536,

    # 주면 스텁이 X-Internal-Secret 을 검증한다. .env 의 AI_INTERNAL_SECRET 과 같은 값이어야 한다.
    [string]$ExpectedSecret = "",

    # 라우터 스텁 포트. 백엔드의 spring.ai.openai.base-url 이 여길 가리키게 된다.
    [int]$RouterPort = 8001,

    # 주면 라우터 스텁이 발화와 무관하게 이 도메인으로 답한다.
    [ValidateSet("", "MATCHING_INTENT", "UNCLEAR", "OUT_OF_SCOPE")]
    [string]$RouterForceDomain = "",

    # 라우터 스텁의 고장 주입. "실패하면 매칭으로 통과" 규약을 밖에서 확인할 때 쓴다.
    [ValidateSet("none", "broken-json", "unknown-domain", "empty-domain", "http-500", "temperature-400")]
    [string]$RouterFailureMode = "none",

    # 라우터 스텁을 안 띄운다. 실제 키로 진짜 분류를 보거나, 키 없이 폴백 동작을 볼 때.
    [switch]$NoRouterStub
)

$ErrorActionPreference = 'Stop'

# HttpListener 기반 스텁이 pwsh 7 을 요구하므로 런처도 같은 기준을 적용한다.
if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw "pwsh 7 이상에서 실행하세요. (현재: $($PSVersionTable.PSVersion))"
}

$repoRoot     = (Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
$stubScript   = Join-Path $PSScriptRoot 'stub-ai-server.ps1'
$routerScript = Join-Path $PSScriptRoot 'stub-spring-ai-server.ps1'
$gradlew      = Join-Path $repoRoot 'gradlew.bat'
$envFile      = Join-Path $repoRoot '.env'

# 끝에 슬래시를 붙이면 안 된다. 클라이언트가 baseUrl + "/intents/extract" 로 단순 결합하므로
# "http://localhost:8000/" 은 "//intents/extract" 가 되어 스텁의 라우팅에 걸리지 않는다.
$baseUrl = "http://localhost:$Port"

# 반대로 이쪽은 /v1 을 붙여야 한다. openai-java SDK 는 base-url 에 "chat/completions" 만
# 이어 붙이고, 기본값이 https://api.openai.com/v1 이라 /v1 은 base-url 쪽 몫이다.
# (스텁은 /chat/completions 도 받아주지만, 실서버와 같은 모양을 보는 편이 낫다.)
$routerBaseUrl = "http://localhost:$RouterPort/v1"

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
if (-not $NoRouterStub -and (Test-PortOpen $RouterPort)) {
    throw "포트 $RouterPort 가 이미 사용 중입니다. -RouterPort 로 다른 포트를 지정하거나 -NoRouterStub 를 주세요."
}

# 스텁 하나를 별도 창에 띄우고 리스닝을 시작할 때까지 기다린다.
# 준비 전에 백엔드가 호출하면 연결 거부가 나므로 반드시 기다려야 한다.
function Start-Stub {
    param([string]$Label, [string]$Script, [int]$TargetPort, [string[]]$ExtraArgs = @())

    # -NoExit: 스텁이 죽어도 창이 남아 마지막 에러를 읽을 수 있다.
    # $args 라는 이름은 못 쓴다 — PowerShell 자동 변수라 함수 안에서 덮어쓰면 부작용이 난다.
    $procArgs = @('-NoExit', '-File', $Script, '-Port', $TargetPort) + $ExtraArgs
    $proc = Start-Process pwsh -ArgumentList $procArgs -PassThru

    $deadline = (Get-Date).AddSeconds(15)
    while (-not (Test-PortOpen $TargetPort)) {
        if ($proc.HasExited) { throw "$Label 스텁이 기동 중 종료됐습니다. 그 창의 에러를 확인하세요." }
        if ((Get-Date) -gt $deadline) { throw "$Label 스텁이 15초 안에 포트 $TargetPort 를 열지 않았습니다." }
        Start-Sleep -Milliseconds 300
    }
    Write-Host "      $Label 스텁 준비 완료 (PID $($proc.Id))" -ForegroundColor DarkGray
    return $proc
}

$stubs = @()
try {
    # ---- 1/3. FastAPI 스텁 기동 (별도 창) ----
    Write-Host "[1/3] AI 스텁 기동 → $baseUrl" -ForegroundColor Cyan

    $stubArgs = @('-EmbeddingDimension', $EmbeddingDimension)
    if ($ExpectedSecret) { $stubArgs += @('-ExpectedSecret', $ExpectedSecret) }
    $stubs += Start-Stub -Label 'AI' -Script $stubScript -TargetPort $Port -ExtraArgs $stubArgs

    # ---- 2/3. 라우터 스텁 기동 (별도 창) ----
    # 백엔드에 넘길 프로퍼티도 여기서 같이 정한다 — 스텁을 안 띄우면 주소를 돌릴 이유도 없다.
    $bootArgs = @("--ai.base-url=$baseUrl")

    if ($NoRouterStub) {
        Write-Host "[2/3] 라우터 스텁 생략 (-NoRouterStub)" -ForegroundColor DarkGray
        Write-Host "      분류는 .env 의 OPENAI_API_KEY 대로 동작합니다 (비어 있으면 매번 폴백)." -ForegroundColor DarkGray
    } else {
        Write-Host "[2/3] 라우터 스텁 기동 → $routerBaseUrl" -ForegroundColor Cyan

        $routerArgs = @()
        if ($RouterForceDomain) { $routerArgs += @('-ForceDomain', $RouterForceDomain) }
        if ($RouterFailureMode -ne 'none') { $routerArgs += @('-FailureMode', $RouterFailureMode) }
        $stubs += Start-Stub -Label '라우터' -Script $routerScript -TargetPort $RouterPort -ExtraArgs $routerArgs

        # api-key 를 빈 값으로 덮어쓴다. 빈 문자열은 Spring AI 가 no-auth 모드로 읽는 값이라
        # Authorization 헤더가 아예 안 나간다 — .env 에 진짜 키가 들어 있어도 그게 평문 HTTP 로
        # 로컬 스텁에 실려 가지 않는다. 스텁이 그 헤더의 부재를 [OK] 로 찍어 주므로 확인도 된다.
        # airouter.enabled 는 기본이 true 지만, .env 에서 꺼 뒀다면 스텁을 띄운 의미가 없어서 못 박는다.
        $bootArgs += "--spring.ai.openai.base-url=$routerBaseUrl"
        $bootArgs += "--spring.ai.openai.api-key="
        $bootArgs += "--airouter.enabled=true"
    }

    # ---- 3/3. 백엔드 기동 (이 터미널, 포그라운드) ----
    Write-Host "[3/3] 백엔드 bootRun" -ForegroundColor Cyan
    foreach ($a in $bootArgs) { Write-Host "      $a" -ForegroundColor DarkGray }
    Write-Host "      .env 는 수정하지 않습니다. 중지는 Ctrl+C." -ForegroundColor DarkGray

    Push-Location $repoRoot
    try {
        # Gradle 은 --args 값을 공백으로 쪼개므로 하나의 문자열로 합쳐 넘긴다.
        & $gradlew bootRun "--args=$($bootArgs -join ' ')"
    } finally {
        Pop-Location
    }
} finally {
    # 백엔드가 끝나면(정상 종료든 Ctrl+C 든) 스텁도 같이 정리한다.
    # 남겨두면 다음 실행에서 포트 충돌로 막힌다.
    foreach ($proc in $stubs) {
        if ($proc -and -not $proc.HasExited) {
            Write-Host "스텁 종료 (PID $($proc.Id))" -ForegroundColor DarkGray
            Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        }
    }
}
