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
         3) scripts/docker/.env 파일 (KEY=VALUE, gitignore 됨)
       - 위 값이 없으면 기존 `docker login` 세션을 그대로 사용합니다.
       - 애플리케이션 시크릿(.env)은 .dockerignore 로 이미지에서 제외됩니다.

.PARAMETER Username
    DockerHub 사용자명 (미지정 시 환경변수 DOCKERHUB_USERNAME 사용).

.PARAMETER ImageName
    이미지 저장소명. 기본값: mateon-backend

.PARAMETER Tag
    이미지 태그. 미지정 시 DockerHub 의 최신 semver 태그를 조회해 patch 를 +1 한
    값으로 자동 지정합니다(예: v1.0.3 존재 → v1.0.4). 태그가 하나도 없으면 v1.0.0.

.PARAMETER LatestToo
    :latest 태그도 함께 붙여 push 합니다. 기본 활성화($true).

.PARAMETER Push
    push 여부. $false 이면 빌드/태그만 하고 push 하지 않습니다. 기본 $true.

.PARAMETER Platform
    빌드 대상 플랫폼. 기본값: linux/arm64 (ARM 클라우드 배포용).
    x86 개발 PC 에서도 buildx + QEMU 에뮬레이션으로 arm64 이미지를 크로스 빌드합니다.
    amd64 로 빌드하려면 -Platform linux/amd64 를 명시하세요.

.EXAMPLE
    # 환경변수로 자격증명 지정 후 실행 (기본 arm64 로 빌드)
    $env:DOCKERHUB_USERNAME = "myuser"
    $env:DOCKERHUB_TOKEN    = "dckr_pat_xxx"
    ./scripts/docker/deploy-dockerhub.ps1

.EXAMPLE
    # 특정 태그로 배포 (arm64)
    ./scripts/docker/deploy-dockerhub.ps1 -Username myuser -Tag v1.0.0

.EXAMPLE
    # amd64 로 빌드하여 배포
    ./scripts/docker/deploy-dockerhub.ps1 -Username myuser -Platform linux/amd64

.EXAMPLE
    # push 없이 로컬 빌드/로드만 확인
    ./scripts/docker/deploy-dockerhub.ps1 -Username myuser -Push:$false
#>
[CmdletBinding()]
param(
    [string]$Username,
    [string]$ImageName = "mateon-backend",
    [string]$Tag,
    [bool]$LatestToo = $true,
    [bool]$Push = $true,
    [string]$Platform = "linux/arm64"
)

$ErrorActionPreference = "Stop"

function Write-Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "  [OK] $msg" -ForegroundColor Green }
function Write-Warn($msg) { Write-Host "  [!]  $msg" -ForegroundColor Yellow }
function Fail($msg)       { Write-Host "  [X]  $msg" -ForegroundColor Red; exit 1 }

# 네이티브 docker 명령을 조용히 실행하고 종료코드만 반환한다.
# PS 5.1 은 $ErrorActionPreference='Stop' 상태에서 네이티브 stderr 출력을 종료 오류로
# 취급해 스크립트가 중단된다. 잠시 Continue 로 낮춰 종료코드로만 판정한다.
function Invoke-DockerQuiet {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$DockerArgs)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    docker @DockerArgs > $null 2>&1
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prev
    return $code
}

# DockerHub 에 이미 올라온 semver 태그 중 최고 버전을 찾아 patch +1 한 다음 태그를 반환.
# public 레포지토리라 인증 없이 조회 가능. 실패/미존재 시 StartVersion 으로 시작.
# 반환: @{ Tag = "<다음 태그>"; Prev = "<직전 태그 또는 $null>" }
function Get-NextDockerHubTag {
    param([string]$Repo, [string]$StartVersion = "v1.0.0")

    # DockerHub API 는 TLS 1.2 필요 (PS 5.1 기본값이 낮을 수 있어 명시)
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $url = "https://hub.docker.com/v2/repositories/$Repo/tags/?page_size=100"
    try {
        $resp = Invoke-RestMethod -Uri $url -Method Get -ErrorAction Stop
    } catch {
        # 404(레포지토리 없음) 또는 네트워크 오류 → 첫 버전으로 시작
        Write-Warn "DockerHub 태그 조회 실패($($_.Exception.Message)). 첫 버전으로 시작합니다."
        return @{ Tag = $StartVersion; Prev = $null }
    }

    # vX.Y.Z / X.Y.Z 형태의 semver 태그만 추림 (latest 등은 제외)
    $semver = @()
    foreach ($t in $resp.results) {
        if ($t.name -match '^(v?)(\d+)\.(\d+)\.(\d+)$') {
            $semver += [pscustomobject]@{
                Name   = $t.name
                Prefix = $matches[1]
                Major  = [int]$matches[2]
                Minor  = [int]$matches[3]
                Patch  = [int]$matches[4]
            }
        }
    }

    if ($semver.Count -eq 0) {
        return @{ Tag = $StartVersion; Prev = $null }
    }

    # 숫자 기준 최고 버전 선택 후 patch +1 (접두사 v 유무는 기존 태그를 따름)
    $top  = $semver | Sort-Object Major, Minor, Patch | Select-Object -Last 1
    $next = "{0}{1}.{2}.{3}" -f $top.Prefix, $top.Major, $top.Minor, ($top.Patch + 1)
    return @{ Tag = $next; Prev = $top.Name }
}

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

# scripts/docker/.env (KEY=VALUE) 가 있으면 환경변수로 로드
$EnvDeploy = Join-Path $ScriptDir ".env"
if (Test-Path $EnvDeploy) {
    Write-Ok ".env 파일에서 자격증명 로드"
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
    # -Tag 미지정 시 DockerHub 최신 semver 태그를 읽어 patch 자동 증가
    Write-Step "다음 버전 자동 계산 (DockerHub 조회)"
    $result = Get-NextDockerHubTag -Repo "$Username/$ImageName"
    $Tag = $result.Tag
    if ($result.Prev) {
        Write-Ok "직전 버전: $($result.Prev)  →  새 버전: $Tag"
    } else {
        Write-Ok "기존 semver 태그 없음  →  첫 버전: $Tag"
    }
}
$Repo      = "$Username/$ImageName"
$FullTag   = "${Repo}:${Tag}"
$LatestTag = "${Repo}:latest"

Write-Step "빌드 대상"
Write-Host "  이미지 : $FullTag"
if ($LatestToo -and $Tag -ne "latest") { Write-Host "  추가태그: $LatestTag" }
if ($Platform) { Write-Host "  플랫폼 : $Platform" }

# ---------------------------------------------------------------------------
# 4. buildx 준비 (크로스 아키텍처 빌드 = QEMU 에뮬레이션)
# ---------------------------------------------------------------------------
# 다른 아키텍처(예: x86 PC → arm64)로 빌드하려면 buildx 가 필요합니다.
# buildx 는 대상 플랫폼 이미지를 로컬 docker 이미지 스토어에 --load 할 수 없으므로,
# push 할 때는 --push 로 빌드와 업로드를 한 번에 수행합니다.
Write-Step "buildx 준비 ($Platform)"

if ((Invoke-DockerQuiet buildx version) -ne 0) {
    Fail "docker buildx 를 찾을 수 없습니다. Docker Desktop(또는 buildx 플러그인)이 필요합니다."
}

# buildx 빌더는 뒤에 buildkit 컨테이너(buildx_buildkit_<builder>0)를 상시 띄운다.
# 스크립트 종료 시(정상/실패/중간 exit 모두) 그 컨테이너를 자동으로 멈추기 위해
# 빌더 준비부터 빌드까지를 try/finally 로 감싼다. rm 이 아닌 stop 이라 빌더 설정과
# 캐시는 유지되고, 다음 실행 때 buildx 가 컨테이너를 자동으로 다시 시작한다.
$builderName   = "mateon-builder"
$builderToStop = $null
try {
    # 재사용 가능한 빌더(mateon-builder)를 보장. 없으면 생성.
    if ((Invoke-DockerQuiet buildx inspect $builderName) -ne 0) {
        if ((Invoke-DockerQuiet buildx create --name $builderName --use) -ne 0) { Fail "buildx 빌더 생성 실패" }
        Write-Ok "buildx 빌더 생성: $builderName"
    } else {
        Invoke-DockerQuiet buildx use $builderName | Out-Null
        Write-Ok "buildx 빌더 사용: $builderName"
    }
    # 여기까지 왔으면 빌더 컨테이너가 떠 있을 수 있으므로 종료 대상으로 표시
    $builderToStop = $builderName

    # -----------------------------------------------------------------------
    # 5. 로그인 (push 시 buildx 가 빌드와 동시에 업로드하므로 빌드 전에 로그인)
    # -----------------------------------------------------------------------
    if ($Push) {
        Write-Step "DockerHub 로그인"
        if ($Token) {
            $Token | docker login --username $Username --password-stdin
            if ($LASTEXITCODE -ne 0) { Fail "docker login 실패 (토큰/사용자명 확인)" }
            Write-Ok "토큰으로 로그인 성공"
        } else {
            Write-Warn "DOCKERHUB_TOKEN 미설정 — 기존 docker login 세션을 사용합니다."
            Write-Warn "미로그인 상태면 push 가 실패합니다. Access Token 사용을 권장합니다."
        }
    }

    # -----------------------------------------------------------------------
    # 6. 빌드 (+ push)
    # -----------------------------------------------------------------------
    Write-Step "이미지 빌드 ($Platform)"

    $buildArgs = @("buildx", "build", "--platform", $Platform, "-f", $Dockerfile, "-t", $FullTag)
    if ($LatestToo -and $Tag -ne "latest") { $buildArgs += @("-t", $LatestTag) }
    if ($Push) {
        # 빌드 결과를 곧바로 레지스트리로 push (크로스 아키텍처는 --load 불가)
        $buildArgs += "--push"
    } else {
        # push 안 할 때는 로컬 스토어로 load (대상 플랫폼이 호스트와 같을 때만 성공)
        $buildArgs += "--load"
    }
    $buildArgs += $ProjectRoot

    docker @buildArgs
    if ($LASTEXITCODE -ne 0) { Fail "이미지 빌드 실패" }

    if (-not $Push) {
        Write-Ok "빌드/로드 완료: $FullTag"
        Write-Step "완료 (Push 생략됨: -Push:`$false)"
        exit 0
    }

    Write-Ok "빌드 및 push 완료: $FullTag ($Platform)"
    if ($LatestToo -and $Tag -ne "latest") { Write-Ok "push 완료: $LatestTag" }

    Write-Step "배포 완료 🎉"
    Write-Host "  docker pull $FullTag" -ForegroundColor Green
}
finally {
    # buildkit 컨테이너 자동 종료 (finally 라 정상 종료/실패/중간 exit 모두 실행됨)
    if ($builderToStop) {
        Write-Step "buildx 빌더 컨테이너 정리"
        if ((Invoke-DockerQuiet buildx stop $builderToStop) -eq 0) {
            Write-Ok "buildx 빌더 컨테이너 종료: $builderToStop (다음 실행 시 자동 재시작)"
        } else {
            Write-Warn "buildx 빌더 컨테이너 종료를 건너뜁니다: $builderToStop"
        }
    }
}
