#requires -Version 5.1
<#
.SYNOPSIS
    mateon-backend Docker 이미지를 빌드하여 DockerHub 로 자동 배포합니다.

.DESCRIPTION
    1. Docker 데몬 / .dockerignore / Dockerfile 존재 여부를 확인합니다.
    2. 이미지를 빌드하고 태그(<user>/<image>:<tag>, :latest)를 붙입니다.
    3. DockerHub 에 로그인(자격증명은 환경변수/파일에서만 로드, --password-stdin) 후 push 합니다.

    ⚠️ 보안: 이 스크립트에는 어떤 비밀정보도 하드코딩하지 마세요.
       - DockerHub 자격증명은 아래 우선순위로 로드됩니다.
         1) 매개변수 -Username / 환경변수 DOCKERHUB_USERNAME
         2) 환경변수 DOCKERHUB_TOKEN (Access Token 권장, 비밀번호 대신)
         3) scripts/docker/.env.deploy 파일 (KEY=VALUE, gitignore 됨)
       - 위 값이 없으면 기존 `docker login` 세션을 그대로 사용합니다.
       - 애플리케이션 시크릿(.env)은 .dockerignore 로 이미지에서 제외됩니다.

.PARAMETER Username
    DockerHub 사용자명 (미지정 시 환경변수 DOCKERHUB_USERNAME 사용).

.PARAMETER ImageName
    이미지 저장소명. 기본값: mateon-backend

.PARAMETER Tag
    이미지 태그. 기본값: 현재 git short SHA (없으면 'latest').

.PARAMETER LatestToo
    :latest 태그도 함께 붙여 push 합니다. 기본 활성화($true).

.PARAMETER Push
    push 여부. $false 이면 빌드/태그만 하고 push 하지 않습니다. 기본 $true.

.PARAMETER Platform
    빌드 대상 플랫폼 (예: linux/amd64). 미지정 시 로컬 기본 플랫폼.

.EXAMPLE
    # 환경변수로 자격증명 지정 후 실행
    $env:DOCKERHUB_USERNAME = "myuser"
    $env:DOCKERHUB_TOKEN    = "dckr_pat_xxx"
    ./scripts/docker/deploy-dockerhub.ps1

.EXAMPLE
    # 특정 태그로, amd64 플랫폼 빌드하여 배포
    ./scripts/docker/deploy-dockerhub.ps1 -Username myuser -Tag v1.0.0 -Platform linux/amd64

.EXAMPLE
    # push 없이 로컬 빌드/태그만 확인
    ./scripts/docker/deploy-dockerhub.ps1 -Username myuser -Push:$false
#>
[CmdletBinding()]
param(
    [string]$Username,
    [string]$ImageName = "mateon-backend",
    [string]$Tag,
    [bool]$LatestToo = $true,
    [bool]$Push = $true,
    [string]$Platform
)

$ErrorActionPreference = "Stop"

function Write-Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "  [OK] $msg" -ForegroundColor Green }
function Write-Warn($msg) { Write-Host "  [!]  $msg" -ForegroundColor Yellow }
function Fail($msg)       { Write-Host "  [X]  $msg" -ForegroundColor Red; exit 1 }

# ---------------------------------------------------------------------------
# 0. 경로 설정 (스크립트 위치 기준으로 프로젝트 루트 계산)
# ---------------------------------------------------------------------------
$ScriptDir   = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = (Resolve-Path (Join-Path $ScriptDir "..\..")).Path
Write-Step "프로젝트 루트: $ProjectRoot"

# ---------------------------------------------------------------------------
# 1. 사전 점검
# ---------------------------------------------------------------------------
Write-Step "사전 점검"

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Fail "docker CLI 를 찾을 수 없습니다. Docker Desktop 이 설치/실행 중인지 확인하세요."
}
# 데몬 연결 확인: PS 5.1 은 Stop 선호도에서 native stderr(경고)를 오류로 취급하므로
# 잠시 Continue 로 낮춰 exit code 만으로 판정한다.
$prevEAP = $ErrorActionPreference
$ErrorActionPreference = "Continue"
docker info > $null 2>&1
$dockerInfoCode = $LASTEXITCODE
$ErrorActionPreference = $prevEAP
if ($dockerInfoCode -ne 0) {
    Fail "Docker 데몬에 연결할 수 없습니다. Docker Desktop 을 실행하세요."
}
Write-Ok "docker 데몬 연결 확인"

$Dockerfile = Join-Path $ProjectRoot "Dockerfile"
if (-not (Test-Path $Dockerfile)) { Fail "Dockerfile 이 없습니다: $Dockerfile" }

# .dockerignore 로 .env 가 제외되는지 방어적으로 확인
$DockerIgnore = Join-Path $ProjectRoot ".dockerignore"
if (-not (Test-Path $DockerIgnore)) {
    Write-Warn ".dockerignore 가 없습니다. .env 등 시크릿이 빌드 컨텍스트에 포함될 수 있습니다!"
} elseif (-not (Select-String -Path $DockerIgnore -Pattern '(^|/)\.env|\*\.env' -Quiet)) {
    Write-Warn ".dockerignore 에 .env 제외 규칙이 보이지 않습니다. 확인하세요."
} else {
    Write-Ok ".dockerignore 에서 .env 제외 확인"
}

# ---------------------------------------------------------------------------
# 2. 자격증명 로드 (하드코딩 금지 — 환경변수 또는 gitignore된 파일에서만)
# ---------------------------------------------------------------------------
Write-Step "DockerHub 자격증명 로드"

# scripts/docker/.env.deploy (KEY=VALUE) 가 있으면 환경변수로 로드
$EnvDeploy = Join-Path $ScriptDir ".env.deploy"
if (Test-Path $EnvDeploy) {
    Write-Ok ".env.deploy 파일에서 자격증명 로드"
    Get-Content $EnvDeploy | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
            $k, $v = $line.Split("=", 2)
            Set-Item -Path "Env:$($k.Trim())" -Value $v.Trim()
        }
    }
}

if (-not $Username) { $Username = $env:DOCKERHUB_USERNAME }
if (-not $Username) {
    Fail "DockerHub 사용자명이 필요합니다. -Username 인자 또는 DOCKERHUB_USERNAME 환경변수를 설정하세요."
}
$Token = $env:DOCKERHUB_TOKEN
# 예시 placeholder 토큰은 무시 (실수로 로그인 시도해 unauthorized 나는 것 방지)
if ($Token -and ($Token -like "dckr_pat_replace*" -or $Token -like "*replace-with-your*")) {
    Write-Warn "DOCKERHUB_TOKEN 이 예시 placeholder 값입니다. 무시하고 기존 docker login 세션을 사용합니다."
    $Token = $null
}
Write-Ok "사용자: $Username"

# ---------------------------------------------------------------------------
# 3. 태그 결정
# ---------------------------------------------------------------------------
if (-not $Tag) {
    $sha = ""
    try { $sha = (git -C $ProjectRoot rev-parse --short HEAD 2>$null).Trim() } catch { }
    $Tag = if ($sha) { $sha } else { "latest" }
}
$Repo      = "$Username/$ImageName"
$FullTag   = "${Repo}:${Tag}"
$LatestTag = "${Repo}:latest"

Write-Step "빌드 대상"
Write-Host "  이미지 : $FullTag"
if ($LatestToo -and $Tag -ne "latest") { Write-Host "  추가태그: $LatestTag" }
if ($Platform) { Write-Host "  플랫폼 : $Platform" }

# ---------------------------------------------------------------------------
# 4. 빌드
# ---------------------------------------------------------------------------
Write-Step "이미지 빌드"

$buildArgs = @("build", "-f", $Dockerfile, "-t", $FullTag)
if ($LatestToo -and $Tag -ne "latest") { $buildArgs += @("-t", $LatestTag) }
if ($Platform) { $buildArgs += @("--platform", $Platform) }
$buildArgs += $ProjectRoot

docker @buildArgs
if ($LASTEXITCODE -ne 0) { Fail "이미지 빌드 실패" }
Write-Ok "빌드 완료: $FullTag"

if (-not $Push) {
    Write-Step "완료 (Push 생략됨: -Push:`$false)"
    exit 0
}

# ---------------------------------------------------------------------------
# 5. 로그인 (--password-stdin, 토큰이 있을 때만; 없으면 기존 세션 사용)
# ---------------------------------------------------------------------------
Write-Step "DockerHub 로그인"

if ($Token) {
    $Token | docker login --username $Username --password-stdin
    if ($LASTEXITCODE -ne 0) { Fail "docker login 실패 (토큰/사용자명 확인)" }
    Write-Ok "토큰으로 로그인 성공"
} else {
    Write-Warn "DOCKERHUB_TOKEN 미설정 — 기존 docker login 세션을 사용합니다."
    Write-Warn "미로그인 상태면 push 가 실패합니다. Access Token 사용을 권장합니다."
}

# ---------------------------------------------------------------------------
# 6. Push
# ---------------------------------------------------------------------------
Write-Step "이미지 Push"

docker push $FullTag
if ($LASTEXITCODE -ne 0) { Fail "push 실패: $FullTag" }
Write-Ok "push 완료: $FullTag"

if ($LatestToo -and $Tag -ne "latest") {
    docker push $LatestTag
    if ($LASTEXITCODE -ne 0) { Fail "push 실패: $LatestTag" }
    Write-Ok "push 완료: $LatestTag"
}

Write-Step "배포 완료 🎉"
Write-Host "  docker pull $FullTag" -ForegroundColor Green
