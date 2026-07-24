# 04_00_event_init.ps1 - Event (활동) 테스트 데이터 준비  POST /api/events
# 사용법: powershell -ExecutionPolicy Bypass -File .\04_00_event_init.ps1
# 참고: 등록(POST)은 인증 필요. 조회 테스트는 04_01_event.ps1 에 있다.
#
# 이 스크립트는 '등록'만 담당한다. 등록한 활동의 id 를 .event-ids.json 에 남겨,
# 뒤이어 도는 04_01_event.ps1 이 "방금 등록한 활동이 검색에 잡히는지"를 검증할 수 있게 한다.
. "$PSScriptRoot\00_common.ps1"

Write-Host "`n########## 4-0. Event (활동) 데이터 준비 - POST /api/events ##########" -ForegroundColor Magenta

$hasToken = [bool](Get-AccessToken)

# 이번 실행을 식별할 꼬리표. 원격 서버는 데이터가 계속 쌓이므로 지난 실행분과 섞이면 안 된다.
$runTag = "auto$(Get-Random -Maximum 999999)"

# 공고 메타(기업형태/참여대상/시상규모/혜택/접수링크)는 컬럼이 아니라 description 상단 섹션으로 넣는다.
# 표시만 하고 검색하지 않는 항목까지 컬럼으로 만들면 events 가 공모전 사이트 스키마를 그대로 흉내내게 된다.
function New-EventDescription {
    param(
        [string]$Organizer,
        [string]$OrganizerType,
        [string]$Eligibility,
        [string]$Prize,
        [string]$Benefits,
        [string]$Fields,      # 원본 분야 전체. 행 분리로 각 행은 분야를 하나만 갖기 때문에 여기 남긴다.
        [string]$ApplyUrl,
        [string]$Body
    )
    return @"
[활동 정보]
주최/주관: $Organizer
기업형태: $OrganizerType
참여대상: $Eligibility
시상규모: $Prize
활동혜택: $Benefits
공모분야: $Fields
접수링크: $ApplyUrl

$Body
"@
}

# category / field / 대상 학교를 섞어서 등록한다.
#   - 필터 검색이 '실제로 걸러내는지' 보려면 걸러져 나가야 할 데이터도 있어야 한다.
#   - 마지막 두 건은 분야가 2개인 공고 하나(MABC 2026 형태)를 분야별로 나눈 것이다.
#     같은 공고임을 표시하려고 externalId 를 공유시킨다 — 이 규칙이 깨지면 나중에 손으로 묶어야 한다.
$mabcExternalId = "$runTag-mabc"
$mabcDescription = New-EventDescription `
    -Organizer "업스테이지" -OrganizerType "중소기업" `
    -Eligibility "만 19세 이상 누구나 (개인 또는 최대 3인 팀)" `
    -Prize "총상금 1,200만 원" -Benefits "입사시 가산점, 상장 수여" `
    -Fields "기획/아이디어, 과학/공학" -ApplyUrl "https://example.com/apply" `
    -Body "코딩 없이 아이디어만으로 만드는 나만의 AI 에이전트. 전국 단위 챌린지입니다."

$eventSeeds = @(
    @{
        Label = "공모전/과학공학"; Category = "CONTEST"; Field = "SCIENCE_ENGINEERING_TECH_IT"
        TargetSchool = "단국대학교"; Organizer = "단국대 SW중심대학사업단"; ExternalId = $null
        Description = "백엔드/프론트엔드 개발자를 위한 교내 아이디어 공모전입니다."
    }
    @{
        Label = "공모전/디자인"; Category = "CONTEST"; Field = "DESIGN_PHOTO_ART_VIDEO"
        TargetSchool = "고려대학교"; Organizer = "한국디자인진흥원"; ExternalId = $null
        Description = "브랜드 아이덴티티 디자인 공모전입니다."
    }
    @{
        Label = "대외활동/과학공학"; Category = "EXTERNAL"; Field = "SCIENCE_ENGINEERING_TECH_IT"
        TargetSchool = $null; Organizer = "과학기술정보통신부"; ExternalId = $null   # 전국 대상
        Description = "전국 대학생 대상 기술 서포터즈입니다."
    }
    @{
        Label = "MABC/기획아이디어"; Category = "CONTEST"; Field = "PLANNING_IDEA"
        TargetSchool = $null; Organizer = "업스테이지"; ExternalId = $mabcExternalId
        Description = $mabcDescription
    }
    @{
        Label = "MABC/과학공학"; Category = "CONTEST"; Field = "SCIENCE_ENGINEERING_TECH_IT"
        TargetSchool = $null; Organizer = "업스테이지"; ExternalId = $mabcExternalId
        Description = $mabcDescription
    }
)

# 등록한 활동을 라벨별로 모아둔다 - 04_01 의 필터 검증이 이 맵을 그대로 읽는다.
$createdEventIds = @{}
if ($hasToken) {
    foreach ($seed in $eventSeeds) {
        # 제목에 runTag 를 넣어 이번 실행분을 알아볼 수 있게 한다.
        $body = @{
            category              = $seed.Category
            # field 는 활동 '분야'로 category(종류)와 다른 축이다. 값 목록은 Event.Field 참고.
            field                 = $seed.Field
            title                 = "자동테스트 $($seed.Label) $runTag"
            description           = $seed.Description
            detailUrl             = "https://example.com/contest"
            startDate             = (Get-Date).ToString("yyyy-MM-dd")
            endDate               = (Get-Date).AddDays(30).ToString("yyyy-MM-dd")
            organizer             = $seed.Organizer
            summarizedDescription = $seed.Label
            recommendedTargets    = "백엔드 개발자, 프론트엔드 개발자"
        }
        # 대상 대학교. 비우면 전국 대상이라 아예 보내지 않는다.
        if ($seed.TargetSchool) { $body.targetSchool = $seed.TargetSchool }
        # 분야가 여러 개인 공고를 나눠 등록할 때만 채운다. 나눈 행끼리 같은 값을 공유해야 한다.
        if ($seed.ExternalId)   { $body.externalId   = $seed.ExternalId }
        # campusScope / targetColleges 는 deprecated 라 새 활동에는 채우지 않는다.
        # (기존 값을 읽는 경로는 여전히 살아 있고, 응답에도 그대로 내려온다.)

        $created = Invoke-Api -Method POST -Path "/api/events" -Auth -PassThru -Title "4.0 활동 등록 ($($seed.Label))" -Body $body
        $newId = $created.data.id
        if ($newId) {
            $createdEventIds[$seed.Label] = $newId
            Write-Host "  (i) 생성된 eventId = $newId ($($seed.Label))" -ForegroundColor Green
        }
    }

    # externalId 는 응답에 내려오지 않아 스크립트가 검증할 수 없다. 사람이 확인할 수 있게 남긴다.
    if ($createdEventIds["MABC/기획아이디어"] -and $createdEventIds["MABC/과학공학"]) {
        Write-Host "  (i) 분야별로 나눈 두 행이 externalId=$mabcExternalId 를 공유합니다." -ForegroundColor DarkCyan
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
    # 04_01 이 키워드 검색으로 이번 실행분만 좁혀 검증할 수 있도록 runTag 도 함께 남긴다.
    # 제목에 심은 runTag 는 이번 실행에만 유일해서, 원격 DB 에 데이터가 쌓여 있어도 검색이 좁혀진다.
    # ('__' 접두어로 라벨→id 항목과 구분한다. 04_01 이 읽은 뒤 맵에서 제거한다.)
    $createdEventIds['__runTag'] = $runTag
    $createdEventIds | ConvertTo-Json -Depth 5 | Set-Content -Path $stateFile -Encoding utf8
    Write-Host "`n  (i) 등록한 eventId 를 저장했습니다: $stateFile" -ForegroundColor DarkCyan
} else {
    Remove-Item $stateFile -Force -ErrorAction SilentlyContinue
}
