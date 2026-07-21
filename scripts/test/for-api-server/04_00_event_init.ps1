# 04_00_event_init.ps1 - Event (활동) 테스트 데이터 준비  POST /api/events
# 사용법: powershell -ExecutionPolicy Bypass -File .\04_00_event_init.ps1
# 참고: 등록(POST)은 인증 필요. 조회 테스트는 04_01_event.ps1 에 있다.
#
# 이 스크립트는 '등록'만 담당한다. 등록한 활동의 id 를 .event-ids.json 에 남겨,
# 뒤이어 도는 04_01_event.ps1 이 "방금 만든 활동이 검색에 잡히는지"를 검증할 수 있게 한다.
. "$PSScriptRoot\00_common.ps1"

Write-Host "`n########## 4-0. Event (활동) 데이터 준비 - POST /api/events ##########" -ForegroundColor Magenta

$hasToken = [bool](Get-AccessToken)

# category / field 를 섞어서 3건 등록한다 - 뒤의 필터 검색이 '분야로 실제 걸러지는지'까지 보려면
# 같은 분야만 있어서는 안 되고, 걸러져 나가야 할 다른 분야 데이터도 있어야 하기 때문이다.
$eventSeeds = @(
    @{ Category = "CONTEST";  Field = "SCIENCE_ENGINEERING_TECH_IT"; Label = "공모전/과학공학" }
    @{ Category = "CONTEST";  Field = "DESIGN_PHOTO_ART_VIDEO";      Label = "공모전/디자인" }
    @{ Category = "EXTERNAL"; Field = "SCIENCE_ENGINEERING_TECH_IT"; Label = "대외활동/과학공학" }
)

# 등록한 활동을 분야 라벨별로 모아둔다 - 04_01 의 필터 검증이 이 맵을 그대로 읽는다.
$createdEventIds = @{}
if ($hasToken) {
    foreach ($seed in $eventSeeds) {
        $created = Invoke-Api -Method POST -Path "/api/events" -Auth -PassThru -Title "4.0 활동 등록 ($($seed.Label))" -Body @{
            category              = $seed.Category
            # field 는 활동 '분야'로 category(종류)와 다른 축이다. 값 목록은 Event.Field 참고.
            field                 = $seed.Field
            title                 = "자동테스트 $($seed.Label) $((Get-Random -Maximum 9999))"
            description           = "백엔드/프론트엔드 개발자를 위한 $($seed.Label) 활동입니다."
            detailUrl             = "https://example.com/contest"
            startDate             = (Get-Date).ToString("yyyy-MM-dd")
            endDate               = (Get-Date).AddDays(30).ToString("yyyy-MM-dd")
            campusScope           = "ALL"
            targetColleges        = "SW융합대학"
            summarizedDescription = $seed.Label
            recommendedTargets    = "백엔드 개발자, 프론트엔드 개발자"
            # externalId 는 손으로 등록하는 활동이라 생략한다(선택 필드).
        }
        $newId = $created.data.id
        if ($newId) {
            $createdEventIds[$seed.Label] = $newId
            Write-Host "  (i) 생성된 eventId = $newId ($($seed.Label))" -ForegroundColor Green
        }
    }
} else {
    Write-Host "`n[4.0 활동 등록] 스킵 - 인증 필요. 먼저 .\auth\02_auth.ps1 로그인." -ForegroundColor Yellow
    # 비인증 등록이 막히는지 확인한다. 제목의 '차단 기대' 가 4xx 를 성공으로 집계하게 한다.
    Invoke-Api -Method POST -Path "/api/events" -Title "4.0 활동 등록 (비인증 - 차단 기대)" -Body @{
        category = "CONTEST"
        title    = "비인증 등록 시도"
    }
}

# 등록 결과를 파일로 넘긴다. 04_01 은 별도 프로세스로 실행될 수도 있어 변수로는 전달되지 않는다.
# 등록이 하나도 안 됐으면(비인증 등) 이전 실행의 낡은 id 가 남지 않도록 파일을 지운다 —
# 그래야 04_01 이 "검증할 데이터 없음"으로 정확히 스킵한다.
$stateFile = Join-Path $PSScriptRoot ".event-ids.json"
if ($createdEventIds.Count -gt 0) {
    $createdEventIds | ConvertTo-Json -Depth 5 | Set-Content -Path $stateFile -Encoding utf8
    Write-Host "`n  (i) 등록한 eventId 를 저장했습니다: $stateFile" -ForegroundColor DarkCyan
} else {
    Remove-Item $stateFile -Force -ErrorAction SilentlyContinue
}
