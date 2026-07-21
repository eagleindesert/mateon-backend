# 04_event.ps1 - Event (활동) API 테스트  /api/events
# 사용법: powershell -ExecutionPolicy Bypass -File .\04_event.ps1
# 참고: 등록(POST)과 /recommended 는 인증 필요. 나머지는 비인증 접근 가능(인증 시 개인화 정렬).
. "$PSScriptRoot\00_common.ps1"

Write-Host "`n########## 4. Event (활동) - /api/events ##########" -ForegroundColor Magenta

$hasToken = [bool](Get-AccessToken)

# 4.0 활동 등록 [인증 필수]
# 조회 테스트보다 먼저 실행한다 - 방금 등록한 활동이 뒤의 검색 결과에 잡히는지 확인하기 위해서다.
$createdEventId = $null
if ($hasToken) {
    $createdEvent = Invoke-Api -Method POST -Path "/api/events" -Auth -PassThru -Title "4.0 활동 등록 (공모전)" -Body @{
        category              = "CONTEST"
        # field 는 활동 '분야'로 category(종류)와 다른 축이다. 값 목록은 Event.Field 참고.
        field                 = "SCIENCE_ENGINEERING_TECH_IT"
        title                 = "자동테스트 공모전 $((Get-Random -Maximum 9999))"
        description           = "백엔드/프론트엔드 개발자를 위한 아이디어 공모전입니다."
        detailUrl             = "https://example.com/contest"
        startDate             = (Get-Date).ToString("yyyy-MM-dd")
        endDate               = (Get-Date).AddDays(30).ToString("yyyy-MM-dd")
        campusScope           = "ALL"
        targetColleges        = "SW융합대학"
        summarizedDescription = "아이디어 공모전"
        recommendedTargets    = "백엔드 개발자, 프론트엔드 개발자"
        # externalId 는 손으로 등록하는 활동이라 생략한다(선택 필드).
    }
    $createdEventId = $createdEvent.data.id
    if ($createdEventId) { Write-Host "  (i) 생성된 eventId = $createdEventId" -ForegroundColor Green }
} else {
    Write-Host "`n[4.0 활동 등록] 스킵 - 인증 필요. 먼저 .\auth\02_auth.ps1 로그인." -ForegroundColor Yellow
    # 비인증 등록이 막히는지 확인한다. 제목의 '차단 기대' 가 4xx 를 성공으로 집계하게 한다.
    Invoke-Api -Method POST -Path "/api/events" -Title "4.0 활동 등록 (비인증 - 차단 기대)" -Body @{
        category = "CONTEST"
        title    = "비인증 등록 시도"
    }
}

# 4.1 활동 검색 - 필터 없음 (비인증)
Invoke-Api -Method GET -Path "/api/events/search" -Title "4.1 활동 검색 (필터 없음)"

# 4.1 활동 검색 - category 필터
$contestResult = Invoke-Api -Method GET -Path "/api/events/search?category=CONTEST" -PassThru -Title "4.1 활동 검색 (category=CONTEST)"

# 4.0 에서 등록한 활동이 검색에 잡히는지 확인한다.
# 원격 서버는 데이터가 계속 쌓이므로 총 건수가 아니라 '방금 만든 id 포함' 으로 증분 검증한다.
if ($createdEventId) {
    $found = @($contestResult.data | Where-Object { $_.id -eq $createdEventId }).Count -gt 0
    Assert-Test -Title "4.0 등록한 활동이 CONTEST 검색 결과에 포함" -Condition $found -Detail "eventId=$createdEventId"
}

# 4.1 활동 검색 - field(분야) 필터
$fieldResult = Invoke-Api -Method GET -Path "/api/events/search?field=SCIENCE_ENGINEERING_TECH_IT" -PassThru -Title "4.1 활동 검색 (field=과학/공학/기술/IT)"
if ($createdEventId) {
    $foundByField = @($fieldResult.data | Where-Object { $_.id -eq $createdEventId }).Count -gt 0
    Assert-Test -Title "4.0 등록한 활동이 분야 필터 검색에 포함" -Condition $foundByField -Detail "eventId=$createdEventId"
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
