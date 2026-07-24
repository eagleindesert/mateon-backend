# 04_01_event.ps1 - Event (활동) 조회 API 테스트  /api/events
# 사용법: powershell -ExecutionPolicy Bypass -File .\04_01_event.ps1
# 참고: /recommended 는 인증 필요. 나머지는 비인증 접근 가능. 검색 결과는 활동 시작일 최신순이며
#       로그인 여부와 무관하게 순서가 같다.
#
# 등록(POST)은 04_00_event_init.ps1 이 담당한다. 이 스크립트는 조회만 돌리며,
# init 이 남긴 .event-ids.json 을 읽어 "방금 등록한 활동이 검색에 잡히는지"를 검증한다.
# 파일이 없으면(= init 미실행) 증분 검증만 건너뛰고 나머지 조회 테스트는 그대로 돈다.
. "$PSScriptRoot\00_common.ps1"

try {

Write-Host "`n########## 4-1. Event (활동) 조회 - /api/events ##########" -ForegroundColor Magenta

$hasToken = [bool](Get-AccessToken)

# init 이 등록한 활동 id 로드 (라벨 -> eventId)
$createdEventIds = @{}
# init 이 제목에 심은 이번 실행 꼬리표. 키워드 검색으로 이번 실행분만 좁혀 검증할 때 쓴다.
$runTag = $null
$stateFile = Join-Path $PSScriptRoot ".event-ids.json"
if (Test-Path $stateFile) {
    $loaded = Get-Content -Path $stateFile -Raw | ConvertFrom-Json
    foreach ($p in $loaded.PSObject.Properties) { $createdEventIds[$p.Name] = $p.Value }
    # runTag 는 라벨→id 맵이 아니라 이번 실행 식별자다. 꺼내 두고 맵에서는 뺀다(활동 건수/검증에 안 섞이게).
    if ($createdEventIds.ContainsKey("__runTag")) {
        $runTag = $createdEventIds["__runTag"]
        $createdEventIds.Remove("__runTag")
    }
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

# [주석 처리 이유]
# 서버에는 이미 실제 활동 데이터가 많이 쌓여 있어, 이번 실행이 등록한 활동이 검색 결과에서
# 순위가 뒤로 밀린다(정렬은 시작일 최신순 + 페이지네이션이라 페이지 안에 안 들어온다).
# 그래서 '등록한 활동이 결과에 포함되는지'를 이 스크립트로는 안정적으로 확인할 수 없어 주석 처리한다.
# init 에서 등록한 활동이 검색에 잡히는지 확인한다.
#foreach ($label in @("공모전/과학공학", "공모전/디자인", "MABC/기획아이디어", "MABC/과학공학")) {
#    Assert-Contains $contestResult $label "4.1 등록한 활동($label)이 CONTEST 검색 결과에 포함"
#}

# 4.1 활동 검색 - field(분야) 필터
# 분야를 세 개 돌린다 - 각 분야 검색이 '해당 분야는 포함하고 다른 분야는 제외' 하는지 양쪽으로 본다.
# MABC 두 건은 분야가 2개인 공고 하나를 나눠 등록한 것이라, 각 분야 검색에서 자기 행만 잡혀야 한다.
# [주석 처리 이유] 위 블록과 같다 - 서버의 기존 데이터가 많아 이번에 등록한 활동이 순위에서
# 밀려 페이지 안에 안 들어오므로, 포함/제외를 이 스크립트로는 안정적으로 확인할 수 없다.
#$fieldCases = @(
#    @{
#        Field = "SCIENCE_ENGINEERING_TECH_IT"; Title = "과학/공학/기술/IT"
#        Included = @("공모전/과학공학", "대외활동/과학공학", "MABC/과학공학")
#        Excluded = @("공모전/디자인", "MABC/기획아이디어")
#    }
#    @{
#        Field = "DESIGN_PHOTO_ART_VIDEO"; Title = "디자인/사진/예술/영상"
#        Included = @("공모전/디자인")
#        Excluded = @("공모전/과학공학", "대외활동/과학공학", "MABC/기획아이디어", "MABC/과학공학")
#    }
#    @{
#        Field = "PLANNING_IDEA"; Title = "기획/아이디어"
#        Included = @("MABC/기획아이디어")
#        Excluded = @("MABC/과학공학", "공모전/과학공학", "공모전/디자인")
#    }
#)
#foreach ($case in $fieldCases) {
#    $fieldResult = Invoke-Api -Method GET -Path "/api/events/search?field=$($case.Field)" -PassThru -Title "4.1 활동 검색 (field=$($case.Title))"
#    foreach ($label in $case.Included) {
#        Assert-Contains $fieldResult $label "4.1 분야 검색($($case.Title))에 $label 포함"
#    }
#    foreach ($label in $case.Excluded) {
#        Assert-Contains $fieldResult $label "4.1 분야 검색($($case.Title))에서 $label 제외" -Absent
#    }
#}

# 4.1 활동 검색 - 분야 오타는 400 으로 막혀야 한다
Invoke-Api -Method GET -Path "/api/events/search?field=IT" -Title "4.1 활동 검색 (분야 오타 - 차단 기대)"

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
# [주의] /api/events/recommended 는 deprecated(종료 예정) 엔드포인트다.
# 서버가 @Deprecated + Deprecation/Sunset 응답 헤더(RFC 8594, sunset 2026-12-31)와 경고 로그를 남긴다.
# 프론트가 아직 호출 중이라 호환성 때문에 동작만 그대로 두고 있으며, 신규 사용은 하지 않는다.
# 이 스크립트에서 계속 호출하는 것도 '전환 완료 전까지 계약이 안 깨졌는지' 확인하려는 목적일 뿐이다.
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

# ==========================================================================
# 4.4 페이지네이션 — 응답 크기를 잘라 트래픽 과부하를 막는다.
#   원격 DB 는 데이터가 계속 쌓이므로 '총 몇 건'이 아니라 '관찰 가능한 상한/동작'만 본다:
#     - size 로 요청한 개수 이하만 온다 (기본 20, 명시 size, 상한 100).
#     - page 를 넘기면 다른 활동이 온다 (겹치지 않는다).
#   search 는 진짜 오프셋 페이징이고, /api/events(랜덤)는 offset 없이 size 캡만 지원한다.
# ==========================================================================
Write-Host "`n---------- 4.4 페이지네이션 ----------" -ForegroundColor Magenta

# (a) 기본 size: 파라미터를 안 주면 한 페이지 기본값(20) 이하만 온다.
$defaultPage = Invoke-Api -Method GET -Path "/api/events/search" -PassThru -Title "4.4 검색 기본 페이지 (size 미지정)"
Assert-Test -Title "4.4 size 미지정 시 20건 이하로 잘린다" `
    -Condition (@($defaultPage.data).Count -le 20) -Detail "count=$(@($defaultPage.data).Count)"

# (b) size 지정: 요청한 개수 이하만 온다.
$size3 = Invoke-Api -Method GET -Path "/api/events/search?size=3" -PassThru -Title "4.4 검색 size=3"
Assert-Test -Title "4.4 size=3 이면 3건 이하만 온다" `
    -Condition (@($size3.data).Count -le 3) -Detail "count=$(@($size3.data).Count)"

# (c) size 상한: 큰 값을 보내도 서버가 상한(100)으로 잘라 전건 조회를 막는다. 과부하 방지의 핵심 계약이다.
$hugeSize = Invoke-Api -Method GET -Path "/api/events/search?size=100000" -PassThru -Title "4.4 검색 size=100000 (상한 100)"
Assert-Test -Title "4.4 과도한 size 는 100건으로 상한 처리된다" `
    -Condition (@($hugeSize.data).Count -le 100) -Detail "count=$(@($hugeSize.data).Count)"

# (d) page 이동: page=0 과 page=1 은 서로 다른 활동을 준다(겹치지 않는다).
#   첫 페이지가 꽉 찼을 때만 의미가 있다 - 데이터가 2건 미만이면 넘길 다음 페이지가 없다.
$page0 = Invoke-Api -Method GET -Path "/api/events/search?page=0&size=2" -PassThru -Title "4.4 검색 page=0&size=2"
$page1 = Invoke-Api -Method GET -Path "/api/events/search?page=1&size=2" -PassThru -Title "4.4 검색 page=1&size=2"
$ids0 = @($page0.data | ForEach-Object { $_.id })
$ids1 = @($page1.data | ForEach-Object { $_.id })
if ($ids0.Count -eq 2) {
    $overlap = @($ids0 | Where-Object { $ids1 -contains $_ }).Count
    Assert-Test -Title "4.4 page=0 과 page=1 의 활동이 겹치지 않는다" `
        -Condition ($overlap -eq 0) -Detail "page0=[$($ids0 -join ',')] page1=[$($ids1 -join ',')]"
} else {
    Write-Host "  (i) 검색 결과가 2건 미만이라 페이지 이동 검증을 건너뜁니다." -ForegroundColor Yellow
}

# (e) 전체 조회(랜덤)도 size 로 크기를 묶는다. page 는 지원하지 않는다(매 호출 랜덤 표본).
$randSize5 = Invoke-Api -Method GET -Path "/api/events?size=5" -PassThru -Title "4.4 전체 조회 size=5"
Assert-Test -Title "4.4 전체 조회도 size=5 면 5건 이하만 온다" `
    -Condition (@($randSize5.data).Count -le 5) -Detail "count=$(@($randSize5.data).Count)"

$randHuge = Invoke-Api -Method GET -Path "/api/events?size=100000" -PassThru -Title "4.4 전체 조회 size=100000 (상한 100)"
Assert-Test -Title "4.4 전체 조회도 과도한 size 는 100건으로 상한 처리된다" `
    -Condition (@($randHuge.data).Count -le 100) -Detail "count=$(@($randHuge.data).Count)"

# ==========================================================================
# 4.5 키워드 검색 — 제목·설명·주최를 아우르는 부분일치(OR)이며, 기존 필터와는 AND 로 묶인다.
#   category/field 필터의 '포함' 검증은 원격 DB 에 데이터가 많아 등록 활동이 페이지 밖으로 밀려
#   주석 처리돼 있다(위 참고). 키워드는 다르다 — init 이 제목에 심은 유일한 runTag 로 검색하면
#   이번 실행분만 좁혀지므로, '등록한 활동이 검색에 잡히는지'를 안정적으로 확인할 수 있다.
#   (runTag 가 없으면 = init 미실행이라 키워드 검증은 통째로 건너뛴다.)
# ==========================================================================
Write-Host "`n---------- 4.5 키워드 검색 ----------" -ForegroundColor Magenta

if ($runTag -and $createdEventIds.Count -gt 0) {
    $createdIds = @($createdEventIds.Values)

    # (a) runTag 로 검색하면 이번 실행이 등록한 활동이 전부 잡힌다 (제목에 runTag 가 들어 있다).
    $kwResult = Invoke-Api -Method GET -Path "/api/events/search?keyword=$runTag&size=100" -PassThru -Title "4.5 키워드 검색 (keyword=runTag)"
    $kwIds = @($kwResult.data | ForEach-Object { $_.id })
    $allPresent = $true
    foreach ($id in $createdIds) { if ($kwIds -notcontains $id) { $allPresent = $false } }
    Assert-Test -Title "4.5 runTag 키워드 검색에 등록한 활동이 모두 잡힌다" `
        -Condition $allPresent -Detail "created=$($createdIds.Count) found=$($kwIds.Count)"

    # (b) 존재하지 않는 키워드로 검색하면 이번 실행분이 하나도 안 잡힌다 (키워드가 실제로 거른다).
    # 한글 파라미터는 이 레포 관례대로 퍼센트 인코딩해 보낸다(curl.exe 로 raw 한글 URL 전달 회피).
    $encNoneKw = [uri]::EscapeDataString("${runTag}_없는키워드")
    $noneResult = Invoke-Api -Method GET -Path "/api/events/search?keyword=$encNoneKw&size=100" -PassThru -Title "4.5 키워드 검색 (매칭 없음)"
    $noneIds = @($noneResult.data | ForEach-Object { $_.id })
    $anyLeak = $false
    foreach ($id in $createdIds) { if ($noneIds -contains $id) { $anyLeak = $true } }
    Assert-Test -Title "4.5 매칭 없는 키워드에는 등록한 활동이 안 잡힌다" -Condition (-not $anyLeak)

    # (c) 키워드와 category 를 함께 주면 AND 로 묶인다 — runTag 로 좁힌 뒤 CONTEST 만 남기면
    #     같은 실행의 EXTERNAL 활동('대외활동/과학공학')은 빠진다.
    $kwContest = Invoke-Api -Method GET -Path "/api/events/search?keyword=$runTag&category=CONTEST&size=100" -PassThru -Title "4.5 키워드+category (keyword=runTag&category=CONTEST)"
    $kwContestIds = @($kwContest.data | ForEach-Object { $_.id })
    $contestId  = $createdEventIds["공모전/과학공학"]
    $externalId = $createdEventIds["대외활동/과학공학"]
    if ($contestId) {
        Assert-Test -Title "4.5 키워드+CONTEST 에 CONTEST 활동이 잡힌다" `
            -Condition ($kwContestIds -contains $contestId) -Detail "eventId=$contestId"
    }
    if ($externalId) {
        Assert-Test -Title "4.5 키워드+CONTEST 에서 EXTERNAL 활동은 빠진다 (키워드와 필터는 AND)" `
            -Condition ($kwContestIds -notcontains $externalId) -Detail "eventId=$externalId"
    }

    # (d) 빈 키워드(keyword=)는 필터를 걸지 않는다 — 키워드를 아예 안 준 검색과 결과가 같아야 한다.
    #     누적 DB 라 건수 자체는 못 박지 않고, 같은 정렬(startDate desc, id desc)이 결정적이라
    #     두 호출의 id 나열이 완전히 같은지로 '빈 키워드 = 미지정'을 확인한다(4.1 순서 검증과 같은 방식).
    $noKw    = Invoke-Api -Method GET -Path "/api/events/search?size=50" -PassThru -Title "4.5 키워드 미지정 (기준)"
    $emptyKw = Invoke-Api -Method GET -Path "/api/events/search?keyword=&size=50" -PassThru -Title "4.5 빈 키워드 (keyword=)"
    $noKwIds    = @($noKw.data    | ForEach-Object { $_.id }) -join ","
    $emptyKwIds = @($emptyKw.data | ForEach-Object { $_.id }) -join ","
    Assert-Test -Title "4.5 빈 키워드는 키워드 미지정과 동일하게 필터를 걸지 않는다" `
        -Condition ($noKwIds -eq $emptyKwIds) -Detail "미지정=[$noKwIds] 빈키워드=[$emptyKwIds]"

    # (e) keyword=전체 도 필터를 걸지 않는다 — school/college 와 같은 contains() 의 '전체' 분기를
    #     공유하므로, 키워드 미지정 검색과 결과가 같아야 한다(위 (d)와 같은 결정적 비교).
    #     한글 파라미터는 이 레포 관례대로 퍼센트 인코딩해 보낸다(curl.exe 로 raw 한글 URL 전달 회피).
    $encAll = [uri]::EscapeDataString("전체")
    $allKw  = Invoke-Api -Method GET -Path "/api/events/search?keyword=$encAll&size=50" -PassThru -Title "4.5 키워드=전체 (필터 미적용)"
    $allKwIds = @($allKw.data | ForEach-Object { $_.id }) -join ","
    Assert-Test -Title "4.5 키워드='전체'는 키워드 미지정과 동일하게 필터를 걸지 않는다" `
        -Condition ($noKwIds -eq $allKwIds) -Detail "미지정=[$noKwIds] 전체키워드=[$allKwIds]"

    # (f) 키워드+size: runTag 로 좁힌 결과에도 size 상한이 그대로 적용된다.
    #     등록 건수는 5건이라 size=2 로 자르면 전체가 아니라 일부만 와야 한다.
    $kwSize2 = Invoke-Api -Method GET -Path "/api/events/search?keyword=$runTag&size=2" -PassThru -Title "4.5 키워드+size=2"
    Assert-Test -Title "4.5 키워드 검색도 size=2 면 2건 이하만 온다" `
        -Condition (@($kwSize2.data).Count -le 2) -Detail "count=$(@($kwSize2.data).Count)"

    # (g) 키워드+page: runTag 로 좁힌 결과 안에서도 page 이동이 실제로 다른 활동을 주는지 본다.
    #     키워드 필터와 페이지네이션이 함께 적용되는지가 핵심이라, 4.4 의 page 검증과 별개로 확인한다.
    #     등록 건수(createdIds.Count)가 4건 이상이어야 두 페이지(size=2 x 2)가 모두 꽉 찬다.
    if ($createdIds.Count -ge 4) {
        $kwPage0 = Invoke-Api -Method GET -Path "/api/events/search?keyword=$runTag&page=0&size=2" -PassThru -Title "4.5 키워드+page=0&size=2"
        $kwPage1 = Invoke-Api -Method GET -Path "/api/events/search?keyword=$runTag&page=1&size=2" -PassThru -Title "4.5 키워드+page=1&size=2"
        $kwIds0 = @($kwPage0.data | ForEach-Object { $_.id })
        $kwIds1 = @($kwPage1.data | ForEach-Object { $_.id })
        $kwOverlap = @($kwIds0 | Where-Object { $kwIds1 -contains $_ }).Count
        Assert-Test -Title "4.5 키워드 검색에서도 page=0 과 page=1 의 활동이 겹치지 않는다" `
            -Condition ($kwOverlap -eq 0) -Detail "page0=[$($kwIds0 -join ',')] page1=[$($kwIds1 -join ',')]"

        # 페이지로 나뉘어 온 결과가 실제로 이번 실행이 등록한 활동인지도 확인한다 -
        # 겹치지만 않고 엉뚱한(runTag 와 무관한) 활동이 끼어들어도 위 검증만으로는 못 잡는다.
        $kwPagedIds = $kwIds0 + $kwIds1
        $allFromCreated = $true
        foreach ($id in $kwPagedIds) { if ($createdIds -notcontains $id) { $allFromCreated = $false } }
        Assert-Test -Title "4.5 키워드 페이지네이션 결과는 모두 등록한 활동이다" -Condition $allFromCreated `
            -Detail "paged=[$($kwPagedIds -join ',')]"
    } else {
        Write-Host "  (i) 등록 활동이 4건 미만이라 키워드 페이지 이동 검증을 건너뜁니다." -ForegroundColor Yellow
    }
} else {
    Write-Host "  (i) runTag 없음 - 키워드 검색 검증은 건너뜁니다. (먼저 .\04_00_event_init.ps1 실행)" -ForegroundColor Yellow
}

} finally {
    Write-TestSummary | Out-Null
}
