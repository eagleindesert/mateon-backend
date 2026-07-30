# 03_01_user_profile_image.ps1 - 프로필 사진  POST/DELETE /api/users/me/profile-image
# 사용법:
#   powershell -ExecutionPolicy Bypass -File .\03_01_user_profile_image.ps1
#   powershell -ExecutionPolicy Bypass -File .\03_01_user_profile_image.ps1 -ImagePath .\me.jpg
#
# 이 기능은 AI 를 쓰지 않는다(과금 없음). 객체 저장소(OCI)만 필요하다.
#
# 검증의 축은 "비동기"다. 업로드 응답에는 URL 이 없고, 프론트는 유저 조회로 URL 을 확인한다.
# 그래서 이 스크립트도 프론트와 똑같이 행동한다 — POST 로 접수한 뒤 GET /api/users/me 를
# 폴링해서 URL 이 나타나는지 본다. 폴링 없이 즉시 단정하면 통과 여부가 타이밍 운에 좌우된다.
#
# 원격 서버는 데이터가 누적되므로 총 건수는 보지 않는다. 프론트가 실제로 관찰하는 것
# (상태코드, 3종 조회의 profileImageUrl 일치, URL 이 인증 없이 열리는지)만 확인한다.
param(
    # 실제 사진으로 돌리고 싶을 때 지정한다. 미지정 시 임시 PNG 를 만들어 쓴다.
    [string]$ImagePath = "",

    # 비동기 반영을 기다리는 최대 시간. 저장소가 느린 환경에서 늘린다.
    [int]$TimeoutSec = 30
)

. "$PSScriptRoot\00_common.ps1"

try {

Write-Host "`n########## 3-1. 프로필 사진 - /api/users/me/profile-image ##########" -ForegroundColor Magenta

$runTag = "pf$(Get-Random -Maximum 999999)"
$tempFiles = New-Object System.Collections.Generic.List[string]

# System.Drawing 을 못 쓸 때의 대체용 1x1 PNG. 형식은 유효하지만 열어도 아무것도 보이지 않는다.
$onePixelPng = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

# ---------------------------------------------------------------------------
# 테스트용 프로필 사진 만들기
#   서버는 이미지 내용을 판독하지 않으므로(포스터 추출과 달리 AI 가 읽을 게 없다) 1x1 PNG 로도
#   모든 검증이 돌아간다. 그런데도 글자를 그려 넣는 이유는 사람이 확인할 때다 — 버킷 URL 을
#   열었을 때 "이번 실행에서 올린 그 사진"인지 알아볼 수 있어야 하고, 특히 재업로드가 실제로
#   다른 사진으로 바뀌었는지는 눈으로 봐야 판단이 된다(1x1 은 둘 다 빈 화면이라 구분 불가).
#
#   프로필 사진답게 정사각형으로, 아바타 원 + 실행 꼬리표 + 시각을 넣는다.
# ---------------------------------------------------------------------------
function New-TestProfileImage {
    param(
        [Parameter(Mandatory = $true)][string]$OutPath,
        [string]$Caption = "1차 업로드",
        [int[]]$Bg = @(79, 70, 229)   # RGB. 재업로드분은 다른 색을 줘서 한눈에 구분한다
    )
    try {
        Add-Type -AssemblyName System.Drawing -ErrorAction Stop

        $size = 512
        $bmp = New-Object System.Drawing.Bitmap $size, $size
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias

        $bgColor = [System.Drawing.Color]::FromArgb($Bg[0], $Bg[1], $Bg[2])
        $g.Clear($bgColor)

        # 아바타 자리: 흰 원 안에 이니셜. 프론트가 원형으로 크롭해도 알아볼 수 있게 가운데 위에 둔다.
        $g.FillEllipse((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)), 146, 48, 220, 220)

        $center = New-Object System.Drawing.StringFormat
        $center.Alignment = [System.Drawing.StringAlignment]::Center

        $initialFont = New-Object System.Drawing.Font("Malgun Gothic", 88, [System.Drawing.FontStyle]::Bold)
        $g.DrawString("메", $initialFont, (New-Object System.Drawing.SolidBrush $bgColor), 256, 98, $center)

        $white = [System.Drawing.Brushes]::White
        $titleFont = New-Object System.Drawing.Font("Malgun Gothic", 28, [System.Drawing.FontStyle]::Bold)
        $bodyFont  = New-Object System.Drawing.Font("Malgun Gothic", 19)
        $g.DrawString("프로필 사진 테스트", $titleFont, $white, 256, 300, $center)
        $g.DrawString($Caption,             $bodyFont,  $white, 256, 355, $center)
        $g.DrawString("run: $runTag",       $bodyFont,  $white, 256, 392, $center)
        $g.DrawString((Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $bodyFont, $white, 256, 429, $center)

        $g.Dispose()
        $bmp.Save($OutPath, [System.Drawing.Imaging.ImageFormat]::Png)
        $bmp.Dispose()
        return $true
    } catch {
        # 비-Windows 등 System.Drawing 이 없는 환경. 검증 경로는 그대로 돌지만 눈으로는 확인 못 한다.
        [System.IO.File]::WriteAllBytes($OutPath, [Convert]::FromBase64String($onePixelPng))
        Write-Host "  (i) System.Drawing 사용 불가 - 최소 PNG 로 대체합니다 (열어도 아무것도 보이지 않음)." -ForegroundColor Yellow
        return $false
    }
}

# 재업로드에 쓸 두 번째 사진. -ImagePath 를 준 경우엔 그 파일을 두 번 올린다(색을 바꿀 수 없으므로
# 3.6.5 는 URL 이 바뀌었는지로만 판단한다 — 검증 내용은 같다).
$repngPath = $null

if ($ImagePath) {
    if (-not (Test-Path $ImagePath)) { throw "지정한 이미지가 없습니다: $ImagePath" }
    $pngPath = (Resolve-Path $ImagePath).Path
    Write-Host "  (i) 지정한 이미지를 사용합니다: $pngPath" -ForegroundColor DarkCyan
} else {
    $pngPath = Join-Path ([System.IO.Path]::GetTempPath()) "mateon-profile-$runTag.png"
    New-TestProfileImage -OutPath $pngPath -Caption "1차 업로드" -Bg @(79, 70, 229) | Out-Null
    $tempFiles.Add($pngPath)

    $repngPath = Join-Path ([System.IO.Path]::GetTempPath()) "mateon-profile-$runTag-2.png"
    New-TestProfileImage -OutPath $repngPath -Caption "2차 업로드 (교체분)" -Bg @(190, 24, 93) | Out-Null
    $tempFiles.Add($repngPath)

    Write-Host "  (i) 테스트 사진을 생성했습니다 (열어보면 실행 꼬리표가 보입니다): $pngPath" -ForegroundColor DarkCyan
}
$pngMime = "image/png"
if ([System.IO.Path]::GetExtension($pngPath) -match '^\.jpe?g$') { $pngMime = "image/jpeg" }

$nullDevice = "NUL"
if ($IsWindows -eq $false) { $nullDevice = "/dev/null" }   # $IsWindows 는 PS 5.1 에 없다($null)

# ---------------------------------------------------------------------------
# 폴링 헬퍼
#   Invoke-Api 를 쓰지 않는 이유: 폴링은 수십 번 돌 수 있어 응답 본문을 매번 출력하면
#   로그가 검증 결과를 덮는다. 조용히 읽고, 판정은 아래 Assert-Test 가 한 번만 한다.
# ---------------------------------------------------------------------------
function Get-ProfileImageUrlQuiet {
    $token = Get-AccessToken
    $raw = (& $script:Curl -s -H "Authorization: Bearer $token" "$script:BaseUrl/api/users/me") -join ""
    try { return ($raw | ConvertFrom-Json).data.profileImageUrl } catch { return $null }
}

# $Until 이 $true 를 돌려줄 때까지 기다린다. 타임아웃이면 마지막으로 읽은 값을 그대로 돌려준다
# (호출부가 Assert-Test 로 실패 처리하고, 그 값이 원인 파악의 단서가 된다).
function Wait-ProfileImageUrl {
    param([Parameter(Mandatory = $true)][scriptblock]$Until, [int]$Seconds = $TimeoutSec)
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ($true) {
        $url = Get-ProfileImageUrlQuiet
        if (& $Until $url) { return $url }
        if ((Get-Date) -ge $deadline) { return $url }
        Start-Sleep -Milliseconds 700
    }
}

function Test-UrlOpensPublicly {
    param([string]$Url)
    $probe = (& $script:Curl -s -o $nullDevice -w "%{http_code} %{content_type}" $Url) -join ""
    return $probe
}

if (-not (Get-AccessToken)) {
    Write-Host "(!) accessToken 이 없습니다. 먼저 .\auth\02_auth.ps1 을 실행하세요." -ForegroundColor Red
    return
}
$myId = Get-JwtSubject -Token (Get-AccessToken)

# ---------------------------------------------------------------------------
# 3.6.1 인증 — 남의 프로필 사진을 바꿀 수 없어야 한다.
# ---------------------------------------------------------------------------
Write-Host "`n---------- 3.6.1 인증 ----------" -ForegroundColor Magenta

Invoke-ApiUpload -Path "/api/users/me/profile-image" -FilePath $pngPath -Mime $pngMime `
    -Title "3.6.1 사진 업로드 (비인증 - 차단 기대)" | Out-Null
Invoke-Api -Method DELETE -Path "/api/users/me/profile-image" `
    -Title "3.6.1 사진 삭제 (비인증 - 차단 기대)" | Out-Null

# ---------------------------------------------------------------------------
# 3.6.2 잘못된 업로드 — 비동기 실패는 사용자에게 전할 길이 없으므로, 걸러낼 수 있는 것은
#   전부 접수 단계에서 4xx 로 나가야 한다. 여기가 느슨하면 사용자는 200 을 받고도
#   사진이 안 바뀌는 이유를 알 수 없다.
# ---------------------------------------------------------------------------
Write-Host "`n---------- 3.6.2 잘못된 업로드 ----------" -ForegroundColor Magenta

$gifPath = Join-Path ([System.IO.Path]::GetTempPath()) "mateon-profile-$runTag.gif"
Copy-Item $pngPath $gifPath -Force
$tempFiles.Add($gifPath)
Invoke-ApiUpload -Path "/api/users/me/profile-image" -FilePath $gifPath -Mime "image/gif" `
    -Auth -Title "3.6.2 gif 업로드 (차단 기대)" | Out-Null

Invoke-ApiUpload -Path "/api/users/me/profile-image" -FilePath $pngPath -Mime $pngMime `
    -PartName "file" -Auth -Title "3.6.2 파트 이름 오타 (차단 기대)" | Out-Null

# 10MB 초과. 멀티파트 상한(20MB)보다 작으므로 컨테이너가 아니라 우리 검증이 잡아야 한다
# (413 IMAGE_TOO_LARGE). 실제로 채우지 않고 크기만 잡는다.
$hugePath = Join-Path ([System.IO.Path]::GetTempPath()) "mateon-profile-huge-$runTag.png"
$fs = [System.IO.File]::Create($hugePath)
$fs.SetLength(11MB)
$fs.Close()
$tempFiles.Add($hugePath)
Invoke-ApiUpload -Path "/api/users/me/profile-image" -FilePath $hugePath -Mime $pngMime `
    -Auth -Title "3.6.2 10MB 초과 업로드 (차단 기대)" | Out-Null

# ---------------------------------------------------------------------------
# 3.6.3 업로드 — 응답에는 URL 이 없고, 잠시 뒤 조회에 나타난다.
# ---------------------------------------------------------------------------
Write-Host "`n---------- 3.6.3 업로드 (비동기 접수) ----------" -ForegroundColor Magenta

$before = Get-ProfileImageUrlQuiet
Write-Host "  (i) 업로드 전 profileImageUrl = $(if ($before) { $before } else { '(없음)' })" -ForegroundColor DarkGray

$accepted = Invoke-ApiUpload -Path "/api/users/me/profile-image" -FilePath $pngPath -Mime $pngMime `
    -Auth -PassThru -Title "3.6.3 사진 업로드"

# 접수 응답에 URL 이 없어야 한다. 있으면 프론트가 그 값을 믿고 <img> 를 그리는데, 그 시점엔
# 아직 버킷에 올라가지 않았을 수 있다.
Assert-Test -Title "3.6.3a 접수 응답에는 이미지 URL 이 없다 (data 비어 있음)" `
    -Condition ([bool](-not $accepted.data)) -Detail "data=$($accepted.data)" | Out-Null

$uploaded = Wait-ProfileImageUrl -Until { param($u) $u -and $u -ne $before }
Assert-Test -Title "3.6.3b 잠시 뒤 GET /me 의 profileImageUrl 에 새 URL 이 나타난다" `
    -Condition ([bool]($uploaded -and $uploaded -ne $before)) `
    -Detail "profileImageUrl=$uploaded" | Out-Null

if (-not $uploaded) {
    Write-Host "  (!) URL 이 반영되지 않았습니다. 이후 검증을 건너뜁니다." -ForegroundColor Red
    Write-Host "      비동기 작업이라 응답은 200 이어도 서버 로그에 저장소 실패가 남습니다 —" -ForegroundColor DarkGray
    Write-Host "      .env 의 OCI_* 가 자리표시자이거나 키가 틀린 경우가 대부분입니다." -ForegroundColor DarkGray
    return
}

Assert-Test -Title "3.6.3c profileImageUrl 이 절대 URL 이다" `
    -Condition ([bool]($uploaded -match '^https?://')) -Detail "profileImageUrl=$uploaded" | Out-Null

# 프론트가 <img src> 로 그대로 쓰므로 인증 없이 열려야 한다 (버킷 가시성이 public 인지 확인).
$probe = Test-UrlOpensPublicly -Url $uploaded
Assert-Test -Title "3.6.3d 업로드된 사진이 인증 없이 열린다 (버킷 public)" `
    -Condition ([bool]($probe -match '^200\s+image/')) -Detail $probe | Out-Null

# ---------------------------------------------------------------------------
# 3.6.4 노출 위치 — 마이페이지와 남의 프로필 조회에도 같은 URL 이 실린다.
#   세 응답이 서로 다른 조립 경로를 타므로(UserResponse / MyPageResponseDTO /
#   UserProfileResponse) 한 곳만 넣고 끝내기 쉽다. 프론트는 화면마다 다른 걸 쓴다.
# ---------------------------------------------------------------------------
Write-Host "`n---------- 3.6.4 조회 3종에 실린다 ----------" -ForegroundColor Magenta

$mypage = Invoke-Api -Method GET -Path "/api/users/mypage" -Auth -PassThru -Title "3.6.4 마이페이지 조회"
Assert-Test -Title "3.6.4a 마이페이지에 같은 profileImageUrl 이 실린다" `
    -Condition ($mypage.data.profileImageUrl -eq $uploaded) `
    -Detail "mypage=$($mypage.data.profileImageUrl)" | Out-Null

$publicProfile = Invoke-Api -Method GET -Path "/api/users/$myId" -Auth -PassThru -Title "3.6.4 공개 프로필 조회"
Assert-Test -Title "3.6.4b 공개 프로필에 같은 profileImageUrl 이 실린다" `
    -Condition ($publicProfile.data.profileImageUrl -eq $uploaded) `
    -Detail "public=$($publicProfile.data.profileImageUrl)" | Out-Null

# 사진을 넣었다고 연락처 비공개 원칙이 흔들리면 안 된다 (03_user.ps1 3.5b 와 같은 계약).
$publicKeys = $publicProfile.data.PSObject.Properties.Name
Assert-Test -Title "3.6.4c 공개 프로필에 여전히 이메일이 없다" `
    -Condition (-not ($publicKeys -contains 'email' -or $publicKeys -contains 'schoolEmail')) `
    -Detail "keys = $($publicKeys -join ', ')" | Out-Null

# ---------------------------------------------------------------------------
# 3.6.5 재업로드 — 새 URL 로 바뀌고, 이전 객체는 버킷에서 사라진다.
#   교체는 "이전 객체 삭제 → 새 객체 업로드"가 한 작업이다. 이전 URL 이 계속 열리면
#   삭제가 빠진 것이고, 버킷에 아무도 안 쓰는 객체가 사용자마다 쌓인다.
# ---------------------------------------------------------------------------
Write-Host "`n---------- 3.6.5 재업로드 (이전 객체 정리) ----------" -ForegroundColor Magenta

# 색과 문구가 다른 두 번째 사진을 올린다. URL 비교만으로도 교체는 확인되지만, 버킷을 열어
# 확인할 때 "정말 새 사진으로 바뀌었나"를 눈으로도 판단할 수 있어야 한다.
$replacePath = if ($repngPath) { $repngPath } else { $pngPath }
Invoke-ApiUpload -Path "/api/users/me/profile-image" -FilePath $replacePath -Mime $pngMime `
    -Auth -Title "3.6.5 사진 재업로드" | Out-Null

$replaced = Wait-ProfileImageUrl -Until { param($u) $u -and $u -ne $uploaded }
Assert-Test -Title "3.6.5a 재업로드 후 다른 URL 로 바뀐다" `
    -Condition ([bool]($replaced -and $replaced -ne $uploaded)) `
    -Detail "이전=$uploaded, 지금=$replaced" | Out-Null

if ($replaced -and $replaced -ne $uploaded) {
    $newProbe = Test-UrlOpensPublicly -Url $replaced
    Assert-Test -Title "3.6.5b 새 사진이 열린다" `
        -Condition ([bool]($newProbe -match '^200\s+image/')) -Detail $newProbe | Out-Null

    $oldProbe = Test-UrlOpensPublicly -Url $uploaded
    Assert-Test -Title "3.6.5c 이전 객체는 버킷에서 지워졌다 (200 이 아니다)" `
        -Condition ([bool]($oldProbe -notmatch '^200\s')) -Detail $oldProbe | Out-Null
}

# ---------------------------------------------------------------------------
# 3.6.6 삭제 — 객체를 지운 뒤 URL 이 null 로 돌아간다. 두 번 불러도 성공한다(멱등).
# ---------------------------------------------------------------------------
Write-Host "`n---------- 3.6.6 삭제 ----------" -ForegroundColor Magenta

#Invoke-Api -Method DELETE -Path "/api/users/me/profile-image" -Auth -Title "3.6.6 사진 삭제" | Out-Null

$cleared = Wait-ProfileImageUrl -Until { param($u) -not $u }
Assert-Test -Title "3.6.6a 삭제 후 profileImageUrl 이 null 로 돌아간다" `
    -Condition ([bool](-not $cleared)) -Detail "profileImageUrl=$cleared" | Out-Null

if ($replaced) {
    $goneProbe = Test-UrlOpensPublicly -Url $replaced
    Assert-Test -Title "3.6.6b 삭제된 객체는 열리지 않는다" `
        -Condition ([bool]($goneProbe -notmatch '^200\s')) -Detail $goneProbe | Out-Null
}

# 사진이 없는 상태에서 다시 삭제해도 200 이다 — 프론트가 상태를 모른 채 눌러도 에러를
# 띄우지 않아야 한다(요청이 원한 결과는 이미 그 상태다).
#Invoke-Api -Method DELETE -Path "/api/users/me/profile-image" -Auth `
    -Title "3.6.6 사진 없는 상태에서 다시 삭제 (멱등)" | Out-Null

} finally {
    foreach ($f in $tempFiles) { Remove-Item $f -Force -ErrorAction SilentlyContinue }
    Write-TestSummary | Out-Null
}
