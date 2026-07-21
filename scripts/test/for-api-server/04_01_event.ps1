# 04_01_event.ps1 - Event (활동) 조회 API 테스트  /api/events
# 사용법: powershell -ExecutionPolicy Bypass -File .\04_01_event.ps1
# 참고: /recommended 는 인증 필요. 나머지는 비인증 접근 가능(인증 시 개인화 정렬).
#
# 등록(POST)은 04_00_event_init.ps1 이 담당한다. 이 스크립트는 조회만 돌리며,
# init 이 남긴 .event-ids.json 을 읽어 "방금 등록한 활동이 검색에 잡히는지"를 검증한다.
# 파일이 없으면(= init 미실행) 증분 검증만 건너뛰고 나머지 조회 테스트는 그대로 돈다.
. "$PSScriptRoot\00_common.ps1"

Write-Host "`n########## 4-1. Event (활동) 조회 - /api/events ##########" -ForegroundColor Magenta

$hasToken = [bool](Get-AccessToken)

# init 이 등록한 활동 id 로드 (라벨 -> eventId)
$createdEventIds = @{}
$stateFile = Join-Path $PSScriptRoot ".event-ids.json"
if (Test-Path $stateFile) {
    $loaded = Get-Content -Path $stateFile -Raw | ConvertFrom-Json
    foreach ($p in $loaded.PSObject.Properties) { $createdEventIds[$p.Name] = $p.Value }
    Write-Host "  (i) init 이 등록한 활동 $($createdEventIds.Count)건을 검증에 사용합니다." -ForegroundColor DarkCyan
} else {
    Write-Host "  (i) $stateFile 없음 - 등록 활동 증분 검증은 건너뜁니다. (먼저 .\04_00_event_init.ps1 실행)" -ForegroundColor Yellow
}

# 4.1 활동 검색 - 필터 없음 (비인증)
Invoke-Api -Method GET -Path "/api/events/search" -Title "4.1 활동 검색 (필터 없음)"

# 4.1 활동 검색 - category 필터
$contestResult = Invoke-Api -Method GET -Path "/api/events/search?category=CONTEST" -PassThru -Title "4.1 활동 검색 (category=CONTEST)"

# init 에서 등록한 활동이 검색에 잡히는지 확인한다.
# 원격 서버는 데이터가 계속 쌓이므로 총 건수가 아니라 '방금 만든 id 포함' 으로 증분 검증한다.
foreach ($label in @("공모전/과학공학", "공모전/디자인")) {
    $id = $createdEventIds[$label]
    if (-not $id) { continue }
    $found = @($contestResult.data | Where-Object { $_.id -eq $id }).Count -gt 0
    Assert-Test -Title "4.1 등록한 활동($label)이 CONTEST 검색 결과에 포함" -Condition $found -Detail "eventId=$id"
}

# 4.1 활동 검색 - field(분야) 필터
# 분야를 두 개 돌린다 - 각 분야 검색이 '해당 분야는 포함하고 다른 분야는 제외' 하는지 양쪽으로 본다.
$fieldCases = @(
    @{ Field = "SCIENCE_ENGINEERING_TECH_IT"; Title = "과학/공학/기술/IT"; Included = @("공모전/과학공학", "대외활동/과학공학"); Excluded = @("공모전/디자인") }
    @{ Field = "DESIGN_PHOTO_ART_VIDEO";      Title = "디자인/사진/예술/영상"; Included = @("공모전/디자인"); Excluded = @("공모전/과학공학", "대외활동/과학공학") }
)
foreach ($case in $fieldCases) {
    $fieldResult = Invoke-Api -Method GET -Path "/api/events/search?field=$($case.Field)" -PassThru -Title "4.1 활동 검색 (field=$($case.Title))"
    foreach ($label in $case.Included) {
        $id = $createdEventIds[$label]
        if (-not $id) { continue }
        $found = @($fieldResult.data | Where-Object { $_.id -eq $id }).Count -gt 0
        Assert-Test -Title "4.1 분야 검색($($case.Title))에 $label 포함" -Condition $found -Detail "eventId=$id"
    }
    foreach ($label in $case.Excluded) {
        $id = $createdEventIds[$label]
        if (-not $id) { continue }
        $absent = @($fieldResult.data | Where-Object { $_.id -eq $id }).Count -eq 0
        Assert-Test -Title "4.1 분야 검색($($case.Title))에서 $label 제외" -Condition $absent -Detail "eventId=$id"
    }
}

# 4.1 활동 검색 - 분야 오타는 400 으로 막혀야 한다
Invoke-Api -Method GET -Path "/api/events/search?field=IT" -Title "4.1 활동 검색 (분야 오타 - 차단 기대)"

# 4.1 활동 검색 - college + category 필터
Invoke-Api -Method GET -Path "/api/events/search?college=SW%EC%9C%B5%ED%95%A9%EB%8C%80%ED%95%99&category=SCHOOL" -Title "4.1 활동 검색 (college + category)"

# 4.1 활동 검색 - 인증 사용자 개인화 정렬
if ($hasToken) {
    Invoke-Api -Method GET -Path "/api/events/search" -Auth -Title "4.1 활동 검색 (인증 - 개인화 정렬)"
}

# 4.2 맞춤 활동 추천 [인증 필수]
if ($hasToken) {
    Invoke-Api -Method GET -Path "/api/events/recommended" -Auth -Title "4.2 맞춤 활동 추천 (전체 카테고리)"
    Invoke-Api -Method GET -Path "/api/events/recommended?category=EXTERNAL" -Auth -Title "4.2 맞춤 활동 추천 (category=EXTERNAL)"
} else {
    Write-Host "`n[4.2 맞춤 활동 추천] 스킵 - 인증 필요. 먼저 .\auth\02_auth.ps1 로그인." -ForegroundColor Yellow
    # 인증 없이 호출하면 401/UNAUTHORIZED 를 반환하는지 확인
    Invoke-Api -Method GET -Path "/api/events/recommended" -Title "4.2 맞춤 활동 추천 (비인증 - 실패 확인)"
}

# 4.3 전체 활동 조회 (랜덤)
Invoke-Api -Method GET -Path "/api/events" -Title "4.3 전체 활동 조회 (랜덤)"
