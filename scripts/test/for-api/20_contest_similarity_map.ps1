# 20_contest_similarity_map.ps1 - 공모전 유사도 지도  /api/events/{id}/similarity-map
#
# ============================================================================
#  [!] 과금 주의 - 이 스크립트는 실제 임베딩을 호출합니다.
#      활동 등록이 커밋되면 비동기로 AI 서버가 제목·설명 임베딩을 수행합니다.
#      API 응답은 즉시 오지만 과금은 그 뒤에 발생합니다.
#      예상 호출: 등록 1회(임베딩) + 지도 조회 1회(유사도 계산, 후보가 있을 때).
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
#   - 활동 등록은 임베딩을 기다리지 않고 201 로 끝나는지
#   - 등록 직후 지도 조회는 400 EVENT_EMBEDDING_NOT_READY 일 수 있는지 (비동기)
#   - 임베딩이 채워진 뒤에는 200 과 query.id / points / maxRadius 가 오는지
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
$body = @{
    category    = "CONTEST"
    field       = "EDUCATION"
    title       = "자동테스트 유사도지도 $runTag"
    description = "임베딩과 유사도 지도를 검증하기 위한 테스트 공모전입니다."
    organizer   = "메이트온"
    startDate   = (Get-Date).ToString("yyyy-MM-dd")
}

$created = Invoke-Api -Method POST -Path "/api/events" -Auth -PassThru -Title "20.1 활동 등록 (임베딩은 비동기)" -Body $body
$eventId = $created.data.id
if (-not $eventId) {
    Write-Host "(!) 등록 응답에 id 가 없습니다." -ForegroundColor Red
    return
}
Write-Host "  (i) eventId=$eventId — 임베딩이 채워질 때까지 지도를 폴링합니다." -ForegroundColor DarkCyan

$map = $null
for ($i = 1; $i -le 15; $i++) {
    # 중간 폴링의 400 은 비동기가 아직인 정상 상태라 집계에서 뺀다.
    $result = Invoke-Api -Method GET -Path "/api/events/$eventId/similarity-map" -PassThru -NoTrack `
      -Title "20.2 유사도 지도 폴링 #$i"
    if ($result.success) {
        $map = $result
        Invoke-Api -Method GET -Path "/api/events/$eventId/similarity-map" -PassThru `
          -Title "20.2 유사도 지도 (임베딩 준비됨)"
        break
    }
    Start-Sleep -Seconds 2
}

if (-not $map) {
    Write-Host "  (i) 15회 폴링 동안 임베딩이 안 찼습니다. 등록 직후 400 이 정상이고, AI/스텁을 확인하세요." -ForegroundColor Yellow
} else {
    $qid = $map.data.query.id
    $points = @($map.data.points)
    Write-Host "  (i) query.id=$qid points=$($points.Count) maxRadius=$($map.data.maxRadius)" -ForegroundColor Green
    if ($qid -ne $eventId) {
        Write-Host "  [!!] query.id 가 등록한 eventId 와 다릅니다." -ForegroundColor Red
    }
}

# 비로그인 조회. 임베딩이 아직이면 400 도 허용한다 (인증과 무관한 실패).
Invoke-Api -Method GET -Path "/api/events/$eventId/similarity-map?topN=10" `
  -Title "20.3 유사도 지도 (비로그인)"

} finally {
    Show-TestSummary
}
