# 20_contest_similarity_map.ps1 - 공모전 유사도 지도  /api/events/{id}/similarity-map
#
# ============================================================================
#  [!] 과금 주의 - 이 스크립트는 실제 임베딩을 호출합니다.
#      활동 등록이 커밋되면 비동기로 AI 서버가 제목·설명 임베딩을 수행합니다.
#      API 응답은 즉시 오지만 과금은 그 뒤에 발생합니다.
#      예상 호출: 등록 3회(임베딩) + 지도 조회 1회 이상(유사도 계산).
#      폴링 중 기준 임베딩만 준비되고 DB 에 다른 후보가 있으면, 그때마다 AI 를 부른다.
#      깨끗한 DB 에서는 후보가 비면 emptyMap 이라 AI 를 안 부른다.
#
#      과금 없이 돌리려면 백엔드가 로컬 스텁을 보게 하세요:
#        pwsh -File ..\debug\ai-stub\stub-ai-server.ps1
#        백엔드 AI_BASE_URL=http://localhost:8000 으로 재기동
# ============================================================================
#
# 사용법: pwsh -File .\20_contest_similarity_map.ps1
# 사전 조건:
#   1) 로그인이 선행되어 .auth-token.txt 가 있어야 한다 (99_run_all 또는 auth\02_auth.ps1).
#   2) 백엔드의 ai.base-url 이 살아있는 AI 서버(또는 스텁)를 가리켜야 한다.
#
# [검증 범위]
#   - 활동 등록은 임베딩을 기다리지 않고 201 로 끝나는지 (3건)
#   - 등록 직후 지도 조회는 400 EVENT_EMBEDDING_NOT_READY 일 수 있는지 (비동기)
#   - 기준+형제 임베딩이 채워진 뒤에는 200 과 형제 2건이 points 에 오는지
#     (원격 DB 잔존분 때문에 points 총개수는 단정하지 않는다)
#   - 점의 좌표 모양(similarity/rankPercentile/radius/x/y)과 referenceRings
#   - 비로그인도 지도를 칠 수 있는지
param()
. "$PSScriptRoot\00_common.ps1"

Write-Host "`n########## 20. 공모전 유사도 지도 - GET /api/events/{id}/similarity-map ##########" -ForegroundColor Magenta

try {

if (-not (Get-AccessToken)) {
    Write-Host "(!) accessToken 이 없습니다. 먼저 .\auth\02_auth.ps1 (또는 99_run_all) 을 실행하세요." -ForegroundColor Red
    return
}

$runTag = Get-Date -Format "HHmmss"
$today = (Get-Date).ToString("yyyy-MM-dd")

# 같은 분야로 3건. 그래프가 점이 되려면 기준 말고 임베딩된 형제가 필요하고,
# 실 AI 면 비슷한 제목/설명이 한 클러스터로 붙는다. 스텁은 후보 순서로 좌표를 찍는다.
$seeds = @(
    @{
        Label       = "기준"
        Title       = "자동테스트 교육 콘텐츠 공모전 $runTag"
        Description = "대학생이 교육 콘텐츠를 기획하고 제작하는 공모전입니다. 수업 자료와 학습 프로그램을 주제로 합니다."
        Organizer   = "메이트온"
    }
    @{
        Label       = "유사A"
        Title       = "자동테스트 교육 프로그램 공모전 $runTag"
        Description = "대학생 대상 교육 프로그램 기획 공모전입니다. 학습 콘텐츠와 수업 자료를 주제로 합니다."
        Organizer   = "메이트온교육"
    }
    @{
        Label       = "유사B"
        Title       = "자동테스트 교육 아이디어 공모전 $runTag"
        Description = "교육 아이디어와 학습 콘텐츠를 제안하는 대학생 공모전입니다. 프로그램 기획이 핵심입니다."
        Organizer   = "한국교육재단"
    }
)

$createdIds = @()
foreach ($seed in $seeds) {
    $body = @{
        category    = "CONTEST"
        field       = "EDUCATION"
        title       = $seed.Title
        description = $seed.Description
        organizer   = $seed.Organizer
        startDate   = $today
    }
    $created = Invoke-Api -Method POST -Path "/api/events" -Auth -PassThru `
      -Title "20.1 활동 등록 ($($seed.Label), 임베딩은 비동기)" -Body $body
    $newId = $created.data.id
    if (-not $newId) {
        Write-Host "(!) 등록 응답에 id 가 없습니다. ($($seed.Label))" -ForegroundColor Red
        return
    }
    $createdIds += $newId
    Write-Host "  (i) eventId=$newId ($($seed.Label))" -ForegroundColor DarkCyan
}

$queryId = $createdIds[0]
$siblingIds = @($createdIds[1], $createdIds[2])
Write-Host "  (i) query=$queryId siblings=$($siblingIds -join ',') — 형제 2건이 points 에 들어올 때까지 폴링합니다." -ForegroundColor DarkCyan

function Get-PointIds {
    param($Map)
    if ($null -eq $Map -or $null -eq $Map.data -or $null -eq $Map.data.points) {
        return @()
    }
    return @($Map.data.points | ForEach-Object { [string]$_.id })
}

$maxPolls = 20
$map = $null
for ($i = 1; $i -le $maxPolls; $i++) {
    # 중간 폴링의 400 은 비동기가 아직인 정상 상태라 집계에서 뺀다.
    # 200 이어도 형제 임베딩이 안 찼으면 emptyMap(또는 잔존 후보만)이라 성공으로 세지 않는다.
    $result = Invoke-Api -Method GET -Path "/api/events/$queryId/similarity-map" -PassThru -NoTrack `
      -Title "20.2 유사도 지도 폴링 #$i"
    if ($result.success) {
        $map = $result
        $pointIds = Get-PointIds $result
        $missing = @($siblingIds | Where-Object { $pointIds -notcontains [string]$_ })
        if ($missing.Count -eq 0) {
            Invoke-Api -Method GET -Path "/api/events/$queryId/similarity-map" -PassThru `
              -Title "20.2 유사도 지도 (후보 임베딩 준비됨)"
            break
        }
        Write-Host ("  (i) 기준은 준비됐지만 형제 {0} 가 아직 points 에 없습니다. (points={1})" -f `
          ($missing -join ","), $pointIds.Count) -ForegroundColor DarkCyan
    }
    Start-Sleep -Seconds 2
}

Assert-Test -Title "20.2a 기준 활동 임베딩이 준비됐다" `
  -Condition ([bool]$map) `
  -Detail $(if ($map) {
      "query.id=$($map.data.query.id) points=$(@(Get-PointIds $map).Count)"
    } else {
      "${maxPolls}회 폴링 동안 200 이 오지 않았습니다. 등록 직후 400 이 정상이고, AI/스텁을 확인하세요."
    })

if ($map) {
    $qid = $map.data.query.id
    $points = @()
    if ($null -ne $map.data.points) { $points = @($map.data.points) }
    $pointIds = Get-PointIds $map
    $missing = @($siblingIds | Where-Object { $pointIds -notcontains [string]$_ })
    $siblingIdSet = @($siblingIds | ForEach-Object { [string]$_ })
    $ourPoints = @($points | Where-Object { $siblingIdSet -contains [string]$_.id })

    Write-Host ("  (i) query.id={0} points={1} maxRadius={2} candidatePoolTotal={3}" -f `
      $qid, $points.Count, $map.data.maxRadius, $map.data.candidatePoolTotal) -ForegroundColor Green

    Assert-Test -Title "20.2b query.id 가 기준 eventId 와 같다" `
      -Condition ([string]$qid -eq [string]$queryId) `
      -Detail "query.id=$qid eventId=$queryId"

    Assert-Test -Title "20.2c 이번에 등록한 나머지 2건이 points 에 있다" `
      -Condition ($missing.Count -eq 0) `
      -Detail $(if ($missing.Count -eq 0) {
          "points=$($points.Count) siblings=$($siblingIds -join ',')"
        } else {
          "missing=$($missing -join ',') pointIds=$($pointIds -join ',')"
        })

    $coordsOk = ($ourPoints.Count -eq $siblingIds.Count)
    foreach ($p in $ourPoints) {
        if ($null -eq $p.similarity -or $null -eq $p.rankPercentile -or $null -eq $p.radius `
          -or $null -eq $p.x -or $null -eq $p.y) {
            $coordsOk = $false
            break
        }
    }
    Assert-Test -Title "20.2d 형제 점마다 similarity/rankPercentile/radius/x/y 가 있다" `
      -Condition ([bool]$coordsOk) `
      -Detail "ourPoints=$($ourPoints.Count)"

    Assert-Test -Title "20.2e candidatePoolTotal 이 형제 수 이상이다" `
      -Condition ([int]$map.data.candidatePoolTotal -ge $siblingIds.Count) `
      -Detail "candidatePoolTotal=$($map.data.candidatePoolTotal)"

    $rings = @()
    if ($null -ne $map.data.referenceRings) { $rings = @($map.data.referenceRings) }
    Assert-Test -Title "20.2f 후보가 있으면 referenceRings 가 비지 않는다" `
      -Condition ($rings.Count -gt 0) `
      -Detail "rings=$($rings.Count)"
}

# 비로그인 조회. 기준 임베딩이 아직이면 400 이라, 준비된 뒤에만 집계한다.
if ($map) {
    Invoke-Api -Method GET -Path "/api/events/$queryId/similarity-map?topN=10" `
      -Title "20.3 유사도 지도 (비로그인)"
} else {
    Write-Host "  (i) 기준 임베딩이 없어 20.3 비로그인 조회는 건너뜁니다." -ForegroundColor Yellow
}

} finally {
    Write-TestSummary | Out-Null
}
