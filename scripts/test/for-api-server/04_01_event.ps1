# 04_01_event.ps1 - Event (활동) 조회 API 테스트  /api/events
# 사용법: powershell -ExecutionPolicy Bypass -File .\04_01_event.ps1
# 참고: /recommended 는 인증 필요. 나머지는 비인증 접근 가능. 검색 결과는 활동 시작일 최신순이며
#       로그인 여부와 무관하게 순서가 같다.
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
$anonymousResult = Invoke-Api -Method GET -Path "/api/events/search" -PassThru -Title "4.1 활동 검색 (필터 없음)"

# 4.1 활동 검색 - category 필터
$contestResult = Invoke-Api -Method GET -Path "/api/events/search?category=CONTEST" -PassThru -Title "4.1 활동 검색 (category=CONTEST)"

# 검색 결과에 특정 활동이 들어있는지(또는 없는지) 확인하는 공통 헬퍼.
# 원격 서버는 데이터가 계속 쌓이므로 총 건수가 아니라 '방금 만든 id 포함' 으로 증분 검증한다.
function Assert-Contains {
    param($Result, [string]$Label, [string]$Title, [switch]$Absent)
    $id = $createdEventIds[$Label]
    if (-not $id) { return }
    $hit = @($Result.data | Where-Object { $_.id -eq $id }).Count -gt 0
    # 삼항 연산자는 pwsh 7 전용이라 쓰지 않는다 - 이 스크립트는 Windows PowerShell 5.1 로도 돌아야 한다.
    $expected = $hit
    if ($Absent) { $expected = -not $hit }
    Assert-Test -Title $Title -Condition $expected -Detail "eventId=$id"
}

# 응답 계약 확인 - 새 필드가 실리고, 폐기 예정 필드도 응답에서 사라지지 않았는지 본다.
# 프론트가 아직 campusScope/targetColleges 를 읽고 있어서, 없어지면 화면이 조용히 깨진다.
$sample = @($contestResult.data | Where-Object { $_.id -eq $createdEventIds["공모전/과학공학"] })[0]
if ($sample) {
    Assert-Test -Title "4.1 응답에 organizer 필드가 있다" `
        -Condition ($sample.organizer -eq "단국대 SW중심대학사업단") -Detail "organizer=$($sample.organizer)"
    Assert-Test -Title "4.1 응답에 targetSchool 필드가 있다" `
        -Condition ($sample.targetSchool -eq "단국대학교") -Detail "targetSchool=$($sample.targetSchool)"
    foreach ($legacy in @("campusScope", "targetColleges", "field", "fieldLabel")) {
        $present = $sample.PSObject.Properties.Name -contains $legacy
        Assert-Test -Title "4.1 기존 응답 필드 $legacy 가 그대로 남아 있다" -Condition $present
    }
}

# init 에서 등록한 활동이 검색에 잡히는지 확인한다.
foreach ($label in @("공모전/과학공학", "공모전/디자인", "MABC/기획아이디어", "MABC/과학공학")) {
    Assert-Contains $contestResult $label "4.1 등록한 활동($label)이 CONTEST 검색 결과에 포함"
}

# 4.1 활동 검색 - field(분야) 필터
# 분야를 세 개 돌린다 - 각 분야 검색이 '해당 분야는 포함하고 다른 분야는 제외' 하는지 양쪽으로 본다.
# MABC 두 건은 분야가 2개인 공고 하나를 나눠 등록한 것이라, 각 분야 검색에서 자기 행만 잡혀야 한다.
$fieldCases = @(
    @{
        Field = "SCIENCE_ENGINEERING_TECH_IT"; Title = "과학/공학/기술/IT"
        Included = @("공모전/과학공학", "대외활동/과학공학", "MABC/과학공학")
        Excluded = @("공모전/디자인", "MABC/기획아이디어")
    }
    @{
        Field = "DESIGN_PHOTO_ART_VIDEO"; Title = "디자인/사진/예술/영상"
        Included = @("공모전/디자인")
        Excluded = @("공모전/과학공학", "대외활동/과학공학", "MABC/기획아이디어", "MABC/과학공학")
    }
    @{
        Field = "PLANNING_IDEA"; Title = "기획/아이디어"
        Included = @("MABC/기획아이디어")
        Excluded = @("MABC/과학공학", "공모전/과학공학", "공모전/디자인")
    }
)
foreach ($case in $fieldCases) {
    $fieldResult = Invoke-Api -Method GET -Path "/api/events/search?field=$($case.Field)" -PassThru -Title "4.1 활동 검색 (field=$($case.Title))"
    foreach ($label in $case.Included) {
        Assert-Contains $fieldResult $label "4.1 분야 검색($($case.Title))에 $label 포함"
    }
    foreach ($label in $case.Excluded) {
        Assert-Contains $fieldResult $label "4.1 분야 검색($($case.Title))에서 $label 제외" -Absent
    }
}

# 4.1 활동 검색 - 분야 오타는 400 으로 막혀야 한다
Invoke-Api -Method GET -Path "/api/events/search?field=IT" -Title "4.1 활동 검색 (분야 오타 - 차단 기대)"

# 4.1 활동 검색 - school(대상 대학교) 필터
# 대상 학교가 다른 활동은 빠지고, 전국 대상(대상 학교 없음)도 이 필터에는 안 잡힌다.
$schoolResult = Invoke-Api -Method GET -Path "/api/events/search?school=%EB%8B%A8%EA%B5%AD%EB%8C%80%ED%95%99%EA%B5%90" -PassThru -Title "4.1 활동 검색 (school=단국대학교)"
Assert-Contains $schoolResult "공모전/과학공학"  "4.1 대학교 검색(단국대학교)에 대상 학교가 맞는 활동 포함"
Assert-Contains $schoolResult "공모전/디자인"    "4.1 대학교 검색(단국대학교)에서 다른 학교 대상 활동 제외" -Absent
Assert-Contains $schoolResult "대외활동/과학공학" "4.1 대학교 검색(단국대학교)에서 전국 대상 활동 제외" -Absent

# 4.1 활동 검색 - 대학교 필터는 부분일치라 짧게 적어도 잡힌다
$partialResult = Invoke-Api -Method GET -Path "/api/events/search?school=%EB%8B%A8%EA%B5%AD%EB%8C%80" -PassThru -Title "4.1 활동 검색 (school=단국대 - 부분일치)"
Assert-Contains $partialResult "공모전/과학공학" "4.1 대학교 부분일치 검색(단국대)에 포함"

# 4.1 활동 검색 - school + category 필터
Invoke-Api -Method GET -Path "/api/events/search?school=%EB%8B%A8%EA%B5%AD%EB%8C%80%ED%95%99%EA%B5%90&category=CONTEST" -Title "4.1 활동 검색 (school + category)"

# 4.1 활동 검색 - college 필터 [deprecated] school 로 전환 중이나 아직 동작해야 한다
Invoke-Api -Method GET -Path "/api/events/search?college=SW%EC%9C%B5%ED%95%A9%EB%8C%80%ED%95%99&category=SCHOOL" -Title "4.1 활동 검색 (college + category, deprecated)"

# 4.1 활동 검색 - 로그인해도 순서가 그대로인지 확인한다.
# 예전에는 로그인 사용자에게 관련도 점수순으로 정렬해 줬다. 지금은 시작일 최신순 하나뿐이라
# 두 결과의 id 나열이 완전히 같아야 한다.
if ($hasToken) {
    $authedResult = Invoke-Api -Method GET -Path "/api/events/search" -Auth -PassThru -Title "4.1 활동 검색 (인증 - 순서 동일 기대)"
    $anonIds  = @($anonymousResult.data | ForEach-Object { $_.id }) -join ","
    $authIds  = @($authedResult.data    | ForEach-Object { $_.id }) -join ","
    Assert-Test -Title "4.1 로그인 여부와 무관하게 검색 순서가 같다" -Condition ($anonIds -eq $authIds)
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
