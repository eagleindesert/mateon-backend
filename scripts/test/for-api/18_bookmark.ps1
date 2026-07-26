# 18_bookmark.ps1 - 활동 북마크(즐겨찾기) API 테스트  /api/bookmarks
# 사용법: powershell -ExecutionPolicy Bypass -File .\18_bookmark.ps1
#
# 전제: 로그인된 토큰(.auth-token.txt)과 04_00_event_init.ps1 이 남긴 .event-ids.json 이 필요하다.
#       둘 중 하나라도 없으면 대상 활동을 특정할 수 없어 스크립트가 스스로 스킵한다.
#
# 원격 서버에는 데이터가 계속 쌓이므로 총 건수로는 아무것도 단정하지 않는다.
# 전부 "이번 실행이 찜한 그 id 가 목록에 있는가/없는가" 로 증분 검증한다.
#
# 이 스크립트는 자기가 만든 북마크를 끝에서 정리한다 — 남겨 두면 다음 실행의
# "찜하지 않은 상태" 전제가 깨져 2번 항목이 201 대신 200 을 받는다.
. "$PSScriptRoot\00_common.ps1"

try {

Write-Host "`n########## 18. Bookmark (활동 북마크) - /api/bookmarks ##########" -ForegroundColor Magenta

$hasToken = [bool](Get-AccessToken)
if (-not $hasToken) {
    Write-Host "  (i) 저장된 accessToken 이 없습니다. 북마크는 전부 인증 필요이므로 건너뜁니다." -ForegroundColor Yellow
    Write-Host "      먼저 .\auth\09_three_users.ps1 -LoginOnly 를 실행하세요." -ForegroundColor DarkGray
    return
}

# init 이 등록한 활동 id 로드 (라벨 -> eventId)
$createdEventIds = @{}
$runTag = $null
$stateFile = Join-Path $PSScriptRoot ".event-ids.json"
if (Test-Path $stateFile) {
    $loaded = Get-Content -Path $stateFile -Raw | ConvertFrom-Json
    foreach ($p in $loaded.PSObject.Properties) { $createdEventIds[$p.Name] = $p.Value }
    if ($createdEventIds.ContainsKey("__runTag")) {
        $runTag = $createdEventIds["__runTag"]
        $createdEventIds.Remove("__runTag")
    }
}

$targetId = $createdEventIds["공모전/과학공학"]
if (-not $targetId) {
    # 라벨이 바뀌었을 수도 있으니 아무거나 하나 집는다.
    foreach ($k in $createdEventIds.Keys) { $targetId = $createdEventIds[$k]; break }
}
if (-not $targetId) {
    Write-Host "  (i) $stateFile 없음 - 대상 활동을 특정할 수 없어 건너뜁니다. (먼저 .\04_00_event_init.ps1 실행)" -ForegroundColor Yellow
    return
}
Write-Host "  (i) 대상 활동 eventId=$targetId 로 검증합니다." -ForegroundColor DarkCyan

# 이전 실행이 남긴 북마크가 있으면 지우고 시작한다. 없으면 200 이라 실패하지 않는다.
Invoke-Api -Method DELETE -Path "/api/bookmarks/events/$targetId" -Auth -NoTrack | Out-Null

# --- 18.1 인증 ---------------------------------------------------------------
# -Auth 를 주지 않으면 Authorization 헤더가 안 나간다 = 비로그인 호출.
Invoke-Api -Method POST -Path "/api/bookmarks/events/$targetId" `
    -Title "18.1 비인증 북마크 등록 (차단 기대)"

# --- 18.2 등록 ---------------------------------------------------------------
$added = Invoke-Api -Method POST -Path "/api/bookmarks/events/$targetId" -Auth -PassThru `
    -Title "18.2 북마크 등록"
Assert-Test -Title "18.2 등록 응답의 bookmarked 가 true 다" `
    -Condition ($added.data.bookmarked -eq $true) -Detail "bookmarked=$($added.data.bookmarked)"
Assert-Test -Title "18.2 등록 응답에 eventId 가 실린다" `
    -Condition ($added.data.eventId -eq $targetId) -Detail "eventId=$($added.data.eventId)"

# --- 18.3 중복 등록 ----------------------------------------------------------
# 별 아이콘을 두 번 누르는 건 정상 조작이다. 여기서 4xx 가 나오면 프론트가 멀쩡한 조작을
# 에러로 표시하게 된다. (제목에 "차단 기대"를 넣지 않았으므로 4xx 는 곧 FAIL 이다.)
$again = Invoke-Api -Method POST -Path "/api/bookmarks/events/$targetId" -Auth -PassThru `
    -Title "18.3 이미 찜한 활동 재등록 (에러가 아니어야 한다)"
Assert-Test -Title "18.3 재등록해도 bookmarked 는 여전히 true 다" `
    -Condition ($again.data.bookmarked -eq $true) -Detail "bookmarked=$($again.data.bookmarked)"

# --- 18.4 목록 조회 ----------------------------------------------------------
$list = Invoke-Api -Method GET -Path "/api/bookmarks/events?page=0&size=100" -Auth -PassThru `
    -Title "18.4 내 북마크 목록 조회"
$inList = @($list.data | Where-Object { $_.id -eq $targetId }).Count -gt 0
Assert-Test -Title "18.4 목록에 방금 찜한 활동이 들어 있다" -Condition $inList -Detail "eventId=$targetId"

$listed = @($list.data | Where-Object { $_.id -eq $targetId })[0]
if ($listed) {
    Assert-Test -Title "18.4 목록에 실린 활동은 bookmarked=true 다" `
        -Condition ($listed.bookmarked -eq $true) -Detail "bookmarked=$($listed.bookmarked)"
    # 목록 응답은 활동 검색과 같은 EventResponseDTO 다. 프론트가 같은 카드 컴포넌트를 재사용하므로
    # 필드가 빠지면 북마크 화면만 조용히 깨진다.
    foreach ($f in @("title", "category", "field", "fieldLabel")) {
        $present = $listed.PSObject.Properties.Name -contains $f
        Assert-Test -Title "18.4 목록 응답에 $f 필드가 있다" -Condition $present
    }
}

# --- 18.5 id 목록 조회 -------------------------------------------------------
$ids = Invoke-Api -Method GET -Path "/api/bookmarks/events/ids" -Auth -PassThru `
    -Title "18.5 내 북마크 id 목록 조회"
# 경로가 /events/{eventId} 에 잡히면 여기서 400 이 난다(숫자 정규식으로 막아 둔 지점).
Assert-Test -Title "18.5 id 목록에 방금 찜한 활동 id 가 있다" `
    -Condition (@($ids.data | Where-Object { $_ -eq $targetId }).Count -gt 0) -Detail "eventId=$targetId"

# --- 18.6 검색 응답의 bookmarked ---------------------------------------------
# 이번 실행이 등록한 활동은 시작일 정렬상 뒤로 밀리므로, runTag 키워드로 좁혀서 찾는다.
if ($runTag) {
    $encodedTag = [uri]::EscapeDataString($runTag)

    $authed = Invoke-Api -Method GET -Path "/api/events/search?keyword=$encodedTag&size=100" -Auth -PassThru `
        -Title "18.6 활동 검색 (로그인) - bookmarked 확인"
    $authedHit = @($authed.data | Where-Object { $_.id -eq $targetId })[0]
    if ($authedHit) {
        Assert-Test -Title "18.6 로그인 검색에서 찜한 활동은 bookmarked=true 다" `
            -Condition ($authedHit.bookmarked -eq $true) -Detail "bookmarked=$($authedHit.bookmarked)"
    }
    # 찜하지 않은 활동은 같은 응답 안에서 false 여야 한다.
    $notBookmarked = @($authed.data | Where-Object { $_.id -ne $targetId })[0]
    if ($notBookmarked) {
        Assert-Test -Title "18.6 찜하지 않은 활동은 bookmarked=false 다" `
            -Condition ($notBookmarked.bookmarked -eq $false) -Detail "eventId=$($notBookmarked.id)"
    }

    # 비로그인 검색. /api/events/** 는 permitAll 이라 익명 인증 토큰이 주입되는데, 서버가 그걸
    # userId 로 파싱하려 들면 여기서 500 이 난다. 200 이 나오는지부터가 검증 대상이다.
    $anon = Invoke-Api -Method GET -Path "/api/events/search?keyword=$encodedTag&size=100" -PassThru `
        -Title "18.6 활동 검색 (비로그인) - 500 이 아니어야 한다"
    $anonHit = @($anon.data | Where-Object { $_.id -eq $targetId })[0]
    if ($anonHit) {
        Assert-Test -Title "18.6 비로그인 검색에서는 남의 북마크가 새지 않는다 (bookmarked=false)" `
            -Condition ($anonHit.bookmarked -eq $false) -Detail "bookmarked=$($anonHit.bookmarked)"
    }
} else {
    Write-Host "  (i) __runTag 없음 - 검색 응답의 bookmarked 검증은 건너뜁니다." -ForegroundColor Yellow
}

# --- 18.7 해제 ---------------------------------------------------------------
$removed = Invoke-Api -Method DELETE -Path "/api/bookmarks/events/$targetId" -Auth -PassThru `
    -Title "18.7 북마크 해제"
Assert-Test -Title "18.7 해제 응답의 bookmarked 가 false 다" `
    -Condition ($removed.data.bookmarked -eq $false) -Detail "bookmarked=$($removed.data.bookmarked)"

$afterList = Invoke-Api -Method GET -Path "/api/bookmarks/events?page=0&size=100" -Auth -PassThru `
    -Title "18.7 해제 후 목록 재조회"
Assert-Test -Title "18.7 해제한 활동이 목록에서 빠졌다" `
    -Condition (@($afterList.data | Where-Object { $_.id -eq $targetId }).Count -eq 0) -Detail "eventId=$targetId"

# --- 18.8 중복 해제 ----------------------------------------------------------
$removeAgain = Invoke-Api -Method DELETE -Path "/api/bookmarks/events/$targetId" -Auth -PassThru `
    -Title "18.8 찜한 적 없는 활동 해제 (에러가 아니어야 한다)"
Assert-Test -Title "18.8 재해제해도 bookmarked 는 false 다" `
    -Condition ($removeAgain.data.bookmarked -eq $false) -Detail "bookmarked=$($removeAgain.data.bookmarked)"

# --- 18.9 없는 활동 ----------------------------------------------------------
# 404 를 기대한다. RESOURCE_NOT_FOUND(400)를 쓰면 "요청이 잘못됐다"로 읽혀 프론트가 원인을 못 찾는다.
Invoke-Api -Method POST -Path "/api/bookmarks/events/999999999" -Auth `
    -Title "18.9 없는 활동 북마크 등록 (차단 기대)"

} finally {
    # 어디서 실패하든 이번 실행이 남긴 북마크는 지우고 끝낸다.
    if ($targetId -and (Get-AccessToken)) {
        Invoke-Api -Method DELETE -Path "/api/bookmarks/events/$targetId" -Auth -NoTrack | Out-Null
    }
    Write-TestSummary | Out-Null
}
