# 04_02_event_extract_image.ps1 - 공모전 포스터 이미지 자동 입력  POST /api/events/extract-image
# 사용법:
#   powershell -ExecutionPolicy Bypass -File .\04_02_event_extract_image.ps1
#   powershell -ExecutionPolicy Bypass -File .\04_02_event_extract_image.ps1 -ImagePath .\poster.jpg
#   powershell -ExecutionPolicy Bypass -File .\04_02_event_extract_image.ps1 -SkipRegister
#
# [!] 이 스크립트는 호출 1건당 AI(Vision LLM) 를 1회 호출한다. 과금을 피하려면 백엔드가
#     로컬 스텁(debug\ai-stub\stub-ai-server.ps1)을 보게 하고 돌린다.
#
# 이 엔드포인트는 저장을 하지 않는다 — 이미지에서 뽑은 '등록 초안'만 돌려주고, 실제 등록은
# 사용자가 초안을 고친 뒤 POST /api/events 로 하는 별도 단계다. 그래서 이 스크립트의 핵심
# 검증도 "초안이 그대로 POST /api/events 의 본문이 되는가"(왕복)에 있다. 여기가 깨지면
# 프론트는 필드를 하나하나 손으로 옮겨 담아야 한다.
#
# 원격 서버는 데이터가 계속 쌓이므로 총 건수 같은 건 보지 않는다. 프론트가 실제로 관찰하는 것
# (상태코드, 응답 필드 존재, enum 코드값, 이미지 URL 이 열리는지)만 확인한다.
param(
    # 실제 공모전 포스터로 돌리고 싶을 때 지정한다. 미지정 시 글자를 그린 임시 PNG 를 만들어 쓴다
    # (스텁은 고정 응답이라 무관하지만, 진짜 AI 서버에 붙일 때는 읽을 글자가 있어야 의미가 있다).
    [string]$ImagePath = "",

    # 왕복 검증(초안 -> POST /api/events)을 건너뛴다. 원격 DB 에 활동을 남기고 싶지 않을 때 쓴다.
    [switch]$SkipRegister
)

. "$PSScriptRoot\00_common.ps1"

try {

Write-Host "`n########## 4-2. 공모전 이미지 자동 입력 - POST /api/events/extract-image ##########" -ForegroundColor Magenta

$hasToken = [bool](Get-AccessToken)
$runTag = "img$(Get-Random -Maximum 999999)"
$tempFiles = New-Object System.Collections.Generic.List[string]

# ---------------------------------------------------------------------------
# 테스트용 이미지 준비
#   System.Drawing 으로 글자를 그린다. 진짜 AI 서버에 붙였을 때 읽을 내용이 있어야
#   추출 결과가 의미를 갖는다. 쓸 수 없는 환경(비-Windows 등)이면 최소 크기 PNG 로 떨어진다 —
#   그 경우에도 업로드/검증 경로는 그대로 돌아간다.
# ---------------------------------------------------------------------------
function New-TestPoster {
    param([string]$OutPath, [string]$Format = "Png")
    try {
        Add-Type -AssemblyName System.Drawing -ErrorAction Stop
        $bmp = New-Object System.Drawing.Bitmap 800, 600
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.Clear([System.Drawing.Color]::White)
        $font  = New-Object System.Drawing.Font("Malgun Gothic", 26)
        $small = New-Object System.Drawing.Font("Malgun Gothic", 18)
        $brush = [System.Drawing.Brushes]::Black
        $g.DrawString("2026 제10회 51초 영화 공모전", $font,  $brush, 40, 60)
        $g.DrawString("주최: 부산시사회복지협의회",   $small, $brush, 40, 160)
        $g.DrawString("주제: 연결",                   $small, $brush, 40, 210)
        $g.DrawString("접수기간: 2026-07-01 ~ 2026-07-31", $small, $brush, 40, 260)
        $g.DrawString("분야: 기획/아이디어",           $small, $brush, 40, 310)
        $g.DrawString("대상: 제한 없음",               $small, $brush, 40, 360)
        $g.Dispose()
        $bmp.Save($OutPath, [System.Drawing.Imaging.ImageFormat]::$Format)
        $bmp.Dispose()
        return $true
    } catch {
        # 최소 크기의 유효한 PNG (1x1). 그림은 없지만 파일 형식은 정상이라 400 이 나지 않는다.
        $onePixelPng = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        [System.IO.File]::WriteAllBytes($OutPath, [Convert]::FromBase64String($onePixelPng))
        Write-Host "  (i) System.Drawing 사용 불가 - 최소 PNG 로 대체합니다 (추출 내용은 무의미)." -ForegroundColor Yellow
        return $false
    }
}

if ($ImagePath) {
    if (-not (Test-Path $ImagePath)) { throw "지정한 이미지가 없습니다: $ImagePath" }
    $pngPath = (Resolve-Path $ImagePath).Path
    Write-Host "  (i) 지정한 이미지를 사용합니다: $pngPath" -ForegroundColor DarkCyan
} else {
    $pngPath = Join-Path ([System.IO.Path]::GetTempPath()) "mateon-poster-$runTag.png"
    New-TestPoster -OutPath $pngPath | Out-Null
    $tempFiles.Add($pngPath)
}
$pngMime = "image/png"
if ([System.IO.Path]::GetExtension($pngPath) -match '^\.jpe?g$') { $pngMime = "image/jpeg" }

# ---------------------------------------------------------------------------
# 4.6.1 인증 — 활동 등록의 일부이므로 로그인 없이는 막혀야 한다.
#   SecurityConfig 는 first-match-wins 라, 이 경로가 /api/events/** permitAll 아래로 밀리면
#   비로그인에게 조용히 열린다. 그 사고를 여기서 잡는다.
# ---------------------------------------------------------------------------
Write-Host "`n---------- 4.6.1 인증 ----------" -ForegroundColor Magenta

Invoke-ApiUpload -Path "/api/events/extract-image" -FilePath $pngPath -Mime $pngMime `
    -Title "4.6.1 이미지 추출 (비인증 - 차단 기대)"

if (-not $hasToken) {
    Write-Host "`n[4.6 이미지 추출] 이후 항목 스킵 - 인증 필요. 먼저 .\auth\02_auth.ps1 로그인." -ForegroundColor Yellow
    return
}

# ---------------------------------------------------------------------------
# 4.6.2 정상 추출 — 프론트가 초안 화면을 그리는 데 필요한 계약을 확인한다.
# ---------------------------------------------------------------------------
Write-Host "`n---------- 4.6.2 정상 추출 (PNG) ----------" -ForegroundColor Magenta

$draftResult = Invoke-ApiUpload -Path "/api/events/extract-image" -FilePath $pngPath -Mime $pngMime `
    -Auth -PassThru -Title "4.6.2 이미지 추출 (PNG)"
$draft = $draftResult.data

# 초안을 못 받아도 아래 4.6.3(잘못된 업로드)은 그대로 돌린다 — 그 경로들은 AI/저장소에
# 닿기 전에 거절되므로, AI 나 OCI 가 준비되지 않은 환경에서도 검증할 수 있어야 한다.
if (-not $draft) {
    Write-Host "  (!) 초안을 받지 못했습니다. 초안 계약 검증과 왕복(4.6.4)은 건너뜁니다." -ForegroundColor Red
    Write-Host "      503 = AI 서버(ai.base-url)에 닿지 않음 / 502 이미지 저장소 업로드 실패 =" -ForegroundColor DarkGray
    Write-Host "      .env 의 OCI_* 가 자리표시자이거나 키가 틀린 경우입니다." -ForegroundColor DarkGray
} else {
    # (a) 초안 필드가 전부 실린다. 값이 null 일 수는 있어도(이미지에 없는 정보는 추측하지 않는다)
    #     키 자체가 빠지면 프론트 폼이 조용히 비어버린다.
    foreach ($key in @("category", "field", "title", "organizer", "targetSchool",
                       "startDate", "endDate", "detailUrl", "imageUrl",
                       "description", "summarizedDescription", "recommendedTargets", "externalId")) {
        Assert-Test -Title "4.6.2 초안에 $key 필드가 있다" `
            -Condition ($draft.PSObject.Properties.Name -contains $key)
    }

    # (b) category/field 는 프론트 셀렉트 박스가 그대로 쓰는 코드값이다. 라벨("공모전")이나
    #     목록에 없는 값이 오면 화면에서 선택된 항목이 사라진다. 서버가 ETC 로 떨어뜨리는 게 정상 동작.
    $categories = @("CONTEST", "EXTERNAL", "SCHOOL", "ETC")
    $fields = @("TRAVEL_HOTEL_AIRLINE", "PRESS_MEDIA", "CULTURE_HISTORY", "EVENT_FESTIVAL", "EDUCATION",
                "DESIGN_PHOTO_ART_VIDEO", "ECONOMY_FINANCE", "MANAGEMENT_CONSULTING_MARKETING",
                "POLITICS_SOCIETY_LAW", "SPORTS_FITNESS", "MEDICAL_HEALTH", "BEAUTY_COSMETICS",
                "SCIENCE_ENGINEERING_TECH_IT", "COOKING_FOOD", "STARTUP_SELF_DEVELOPMENT",
                "ENVIRONMENT_ENERGY", "CONTENTS", "SOCIAL_CONTRIBUTION_EXCHANGE",
                "DISTRIBUTION_LOGISTICS", "PLANNING_IDEA", "ETC")
    Assert-Test -Title "4.6.2 category 가 허용된 코드값이다" `
        -Condition ($categories -contains $draft.category) -Detail "category=$($draft.category)"
    Assert-Test -Title "4.6.2 field 가 허용된 코드값이다" `
        -Condition ($fields -contains $draft.field) -Detail "field=$($draft.field)"

    # (c) imageUrl 은 우리 객체 저장소에 올린 원본의 주소다. AI 가 이미지 안에서 읽어낸 문자열
    #     (스킴 누락/오타가 섞이기 쉽다)이 그대로 새어 나오면 프론트의 <img> 가 깨진다.
    Assert-Test -Title "4.6.2 imageUrl 이 절대 URL 이다" `
        -Condition ([bool]($draft.imageUrl -match '^https?://')) -Detail "imageUrl=$($draft.imageUrl)"

    # (d) 그 URL 이 실제로 열려야 프론트가 미리보기를 띄울 수 있다 (버킷 가시성이 public 인지 확인).
    #     인증 헤더 없이 GET 해서 200 과 image/* 를 받는지 본다.
    if ($draft.imageUrl -match '^https?://') {
        $nullDevice = "NUL"
        if ($IsWindows -eq $false) { $nullDevice = "/dev/null" }  # $IsWindows 는 PS 5.1 에 없다($null)
        $headStatus = (& $script:Curl -s -o $nullDevice -w "%{http_code} %{content_type}" $draft.imageUrl) -join ""
        Assert-Test -Title "4.6.2 업로드된 이미지가 인증 없이 열린다 (버킷 public)" `
            -Condition ([bool]($headStatus -match '^200\s+image/')) -Detail $headStatus
    }

    # (e) 날짜는 프론트 date input 이 그대로 받는 yyyy-MM-dd 여야 한다. 읽지 못했으면 null 이 정상이다
    #     (서버가 형식 깨진 값을 그대로 흘리지 않고 비운다).
    foreach ($dateKey in @("startDate", "endDate")) {
        $v = $draft.$dateKey
        Assert-Test -Title "4.6.2 $dateKey 는 yyyy-MM-dd 이거나 null 이다" `
            -Condition ([bool](-not $v -or $v -match '^\d{4}-\d{2}-\d{2}$')) -Detail "$dateKey=$v"
    }
}

# ---------------------------------------------------------------------------
# 4.6.3 잘못된 업로드 — 프론트가 안내 문구를 띄울 수 있게 4xx 로 구분되어야 한다.
#   (핸들러가 없으면 전부 500 "서버 오류"로 뭉개져 사용자가 뭘 고쳐야 할지 알 수 없다)
# ---------------------------------------------------------------------------
Write-Host "`n---------- 4.6.3 잘못된 업로드 ----------" -ForegroundColor Magenta

# (a) 허용하지 않는 확장자
$gifPath = Join-Path ([System.IO.Path]::GetTempPath()) "mateon-poster-$runTag.gif"
Copy-Item $pngPath $gifPath -Force
$tempFiles.Add($gifPath)
Invoke-ApiUpload -Path "/api/events/extract-image" -FilePath $gifPath -Mime "image/gif" `
    -Auth -Title "4.6.3 gif 업로드 (차단 기대)"

# (b) 확장자는 png 인데 파트 이름이 다르다 — 프론트가 필드명을 잘못 쓴 경우.
#     ServletException 계열이라 핸들러가 없으면 500 이 나간다.
Invoke-ApiUpload -Path "/api/events/extract-image" -FilePath $pngPath -Mime $pngMime `
    -PartName "file" -Auth -Title "4.6.3 파트 이름 오타 (차단 기대)"

# (c) 크기 초과 — 서버 멀티파트 상한(10MB)을 넘긴다. 413 으로 안내되어야 한다.
#     AI 서버도 10MB 를 넘기면 400 을 내므로, 우리 쪽에서 먼저 막아 LLM 호출 비용을 아낀다.
$hugePath = Join-Path ([System.IO.Path]::GetTempPath()) "mateon-huge-$runTag.png"
$fs = [System.IO.File]::Create($hugePath)
$fs.SetLength(11MB)   # 실제 11MB 를 채우지 않고 크기만 잡는다 (희소 파일)
$fs.Close()
$tempFiles.Add($hugePath)
Invoke-ApiUpload -Path "/api/events/extract-image" -FilePath $hugePath -Mime $pngMime `
    -Auth -Title "4.6.3 10MB 초과 업로드 (차단 기대)"

# ---------------------------------------------------------------------------
# 4.6.4 왕복 — 받은 초안을 그대로 POST /api/events 에 넣어 등록된다.
#   이 엔드포인트의 존재 이유가 "사용자가 손으로 채우던 등록 폼을 대신 채워주는 것"이라,
#   초안이 등록 API 의 본문 형태와 어긋나면 기능 자체가 성립하지 않는다.
# ---------------------------------------------------------------------------
if (-not $draft) {
    Write-Host "`n  (i) 초안이 없어 왕복(등록) 검증을 건너뜁니다." -ForegroundColor Yellow
} elseif ($SkipRegister) {
    Write-Host "`n  (i) -SkipRegister 지정 - 왕복(등록) 검증을 건너뜁니다." -ForegroundColor Yellow
} else {
    Write-Host "`n---------- 4.6.4 초안 -> 활동 등록 왕복 ----------" -ForegroundColor Magenta

    # 사용자가 화면에서 제목만 손본 상황을 흉내낸다. 꼬리표는 이번 실행분을 나중에 찾기 위한 것.
    $body = @{
        category              = $draft.category
        field                 = $draft.field
        title                 = "[$runTag] $($draft.title)"
        organizer             = $draft.organizer
        targetSchool          = $draft.targetSchool
        startDate             = $draft.startDate
        endDate               = $draft.endDate
        detailUrl             = $draft.detailUrl
        imageUrl              = $draft.imageUrl
        description           = $draft.description
        summarizedDescription = $draft.summarizedDescription
        recommendedTargets    = $draft.recommendedTargets
        externalId            = $draft.externalId
    }
    # null 값은 빼고 보낸다 - 서버가 선택 필드로 취급하는지와 무관하게, 프론트도 비어 있는 칸은
    # 보내지 않는 편이 자연스럽다.
    $payload = @{}
    foreach ($k in $body.Keys) { if ($null -ne $body[$k] -and $body[$k] -ne "") { $payload[$k] = $body[$k] } }

    $created = Invoke-Api -Method POST -Path "/api/events" -Auth -Body $payload -PassThru `
        -Title "4.6.4 추출한 초안으로 활동 등록"

    Assert-Test -Title "4.6.4 초안이 그대로 활동으로 등록된다" `
        -Condition ([bool]$created.data.id) -Detail "eventId=$($created.data.id)"

    if ($created.data.id) {
        # 등록된 활동이 초안의 이미지 URL 을 그대로 들고 있어야 목록/상세에서 포스터가 보인다.
        Assert-Test -Title "4.6.4 등록된 활동에 초안의 imageUrl 이 그대로 실린다" `
            -Condition ($created.data.imageUrl -eq $draft.imageUrl) -Detail "imageUrl=$($created.data.imageUrl)"
        Assert-Test -Title "4.6.4 등록된 활동의 분야가 초안과 같다" `
            -Condition ($created.data.field -eq $draft.field) -Detail "field=$($created.data.field)"

        # 검색으로도 잡히는지 본다 - 원격 DB 는 누적이라 건수가 아니라 이번 실행 꼬리표로 좁힌다.
        $found = Invoke-Api -Method GET -Path "/api/events/search?keyword=$runTag&size=10" -PassThru `
            -Title "4.6.4 등록한 활동 검색 (keyword=runTag)"
        $foundIds = @($found.data | ForEach-Object { $_.id })
        Assert-Test -Title "4.6.4 등록한 활동이 검색에 잡힌다" `
            -Condition ($foundIds -contains $created.data.id) -Detail "eventId=$($created.data.id)"
    }
}

} finally {
    foreach ($f in $tempFiles) { Remove-Item $f -Force -ErrorAction SilentlyContinue }
    Write-TestSummary | Out-Null
}
