# 03_02_portfolio_summarize.ps1 - 포트폴리오 PDF 요약  POST /api/portfolios/summarize
# 사용법:
#   powershell -ExecutionPolicy Bypass -File .\03_02_portfolio_summarize.ps1
#   powershell -ExecutionPolicy Bypass -File .\03_02_portfolio_summarize.ps1 -PdfPath .\portfolio.pdf
#
# 활동(04)이 아니라 유저(03) 그룹에 둔다. 파일 업로드 계열이라 한때 04 에 묶여 있었지만,
# 이건 활동이 아니라 "내 포트폴리오"를 다루는 기능이다 — 프로필의 portfolio 필드,
# 프로필 사진(03_01)과 같은 화면·같은 소유자에 속한다.
#
# [!] 이 스크립트는 캐시 미스 1건당 AI(Vision LLM) 를 1회 호출한다. 게다가 PDF 는 페이지마다
#     이미지를 렌더링해 한 번에 실어 보내므로 포스터 이미지 1장보다 비싸다. 과금을 피하려면
#     백엔드가 로컬 스텁(debug\ai-stub\stub-ai-server.ps1)을 보게 하고 돌린다.
#
# 이 엔드포인트의 핵심은 단순 중계가 아니라 **해시 캐시**다. AI 가 주는 pdf_id 는 PDF 바이트의
# SHA-256 이라 우리도 같은 값을 계산할 수 있고, 백엔드는 AI 를 부르기 전에 그 값으로 조회한다.
# 그래서 이 스크립트의 핵심 검증도 "같은 PDF 를 두 번 올리면 두 번째가 같은 요약을 즉시 준다"에 있다.
#
# 원격 서버는 데이터가 계속 쌓이므로 총 건수 같은 건 보지 않는다. 프론트가 실제로 관찰하는 것
# (상태코드, summary 존재, 재업로드 시 동일 응답)만 확인한다.
param(
    # 실제 포트폴리오로 돌리고 싶을 때 지정한다. 미지정 시 최소 구조의 PDF 를 만들어 쓴다
    # (스텁은 고정 응답이라 무관하지만, 진짜 AI 서버에 붙일 때는 읽을 내용이 있어야 의미가 있다).
    [string]$PdfPath = ""
)

. "$PSScriptRoot\00_common.ps1"

try {

Write-Host "`n########## 3-2. 포트폴리오 PDF 요약 - POST /api/portfolios/summarize ##########" -ForegroundColor Magenta

$hasToken = [bool](Get-AccessToken)
$runTag = "pdf$(Get-Random -Maximum 999999)"
$tempFiles = New-Object System.Collections.Generic.List[string]

# ---------------------------------------------------------------------------
# 테스트용 PDF 준비
#   손으로 쓴 최소 PDF 다. 텍스트 한 줄짜리라 진짜 AI 서버에 붙이면 내용이 빈약하지만,
#   업로드/검증/캐시 경로는 그대로 돌아간다. 실제 판독 품질을 보려면 -PdfPath 를 준다.
#   runTag 를 본문에 넣어 실행마다 바이트가 달라지게 한다 — 안 그러면 이전 실행이 남긴
#   캐시에 걸려 "첫 업로드"가 첫 업로드가 아니게 된다.
# ---------------------------------------------------------------------------
function New-TestPdf {
    param([string]$OutPath, [string]$Text)

    # 최소 구조의 PDF 1페이지. xref 오프셋을 정확히 계산하지 않아 엄격한 파서는 경고를 낼 수
    # 있지만, %PDF 헤더/오브젝트/트레일러가 있어 일반적인 렌더러(PyMuPDF 포함)는 읽는다.
    $content = "BT /F1 14 Tf 60 720 Td ($Text) Tj ET"
    $lines = @(
        "%PDF-1.4",
        "1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj",
        "2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj",
        "3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >> endobj",
        "4 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj",
        "5 0 obj << /Length $($content.Length) >> stream",
        $content,
        "endstream endobj",
        "trailer << /Size 6 /Root 1 0 R >>",
        "%%EOF"
    )
    # PDF 는 바이너리 규약이라 인코딩을 고정한다. BOM 이 붙으면 %PDF 시그니처가 앞에 오지 않는다.
    [System.IO.File]::WriteAllText($OutPath, ($lines -join "`n"), (New-Object System.Text.UTF8Encoding $false))
}

if ($PdfPath) {
    if (-not (Test-Path $PdfPath)) { throw "지정한 PDF 가 없습니다: $PdfPath" }
    $pdfFile = (Resolve-Path $PdfPath).Path
    Write-Host "  (i) 지정한 PDF 를 사용합니다: $pdfFile" -ForegroundColor DarkCyan
} else {
    $pdfFile = Join-Path ([System.IO.Path]::GetTempPath()) "mateon-portfolio-$runTag.pdf"
    New-TestPdf -OutPath $pdfFile -Text "Portfolio $runTag - Frontend developer, React and TypeScript"
    $tempFiles.Add($pdfFile)
}
$pdfMime = "application/pdf"

# ---------------------------------------------------------------------------
# 3.7.1 인증 — 포트폴리오는 개인 이력이라 로그인 없이는 막혀야 한다.
#   SecurityConfig 매처를 잘못 건드려 이 경로가 열리면 남의 요약을 만들거나 볼 수 있게 된다.
# ---------------------------------------------------------------------------
Write-Host "`n---------- 3.7.1 인증 ----------" -ForegroundColor Magenta

Invoke-ApiUpload -Path "/api/portfolios/summarize" -FilePath $pdfFile -PartName "pdf_file" -Mime $pdfMime `
    -Title "3.7.1 PDF 요약 (비인증 - 차단 기대)"

if (-not $hasToken) {
    Write-Host "`n[3.7 PDF 요약] 이후 항목 스킵 - 인증 필요. 먼저 .\auth\02_auth.ps1 로그인." -ForegroundColor Yellow
    return
}

# ---------------------------------------------------------------------------
# 3.7.2 정상 요약 — 프론트가 요약 화면을 그리는 데 필요한 계약을 확인한다.
# ---------------------------------------------------------------------------
Write-Host "`n---------- 3.7.2 정상 요약 ----------" -ForegroundColor Magenta

$firstResult = Invoke-ApiUpload -Path "/api/portfolios/summarize" -FilePath $pdfFile -PartName "pdf_file" -Mime $pdfMime `
    -Auth -PassThru -Title "3.7.2 PDF 요약 (첫 업로드)"
$first = $firstResult.data

# 요약을 못 받아도 아래 3.7.4(잘못된 업로드)는 그대로 돌린다 — 그 경로들은 AI 에 닿기 전에
# 거절되므로, AI 가 준비되지 않은 환경에서도 검증할 수 있어야 한다.
if (-not $first) {
    Write-Host "  (!) 요약을 받지 못했습니다. 요약 계약 검증과 캐시(3.7.3)는 건너뜁니다." -ForegroundColor Red
    Write-Host "      503 = AI 서버(ai.base-url)에 닿지 않음 / 502 = AI 응답 처리 실패" -ForegroundColor DarkGray
} else {
    # (a) summary 키가 있어야 한다. 값이 비어 있으면 백엔드가 502 를 냈어야 하므로 여기선 내용도 본다.
    Assert-Test -Title "3.7.2 응답에 summary 필드가 있다" `
        -Condition ($first.PSObject.Properties.Name -contains "summary")
    Assert-Test -Title "3.7.2 summary 가 비어 있지 않다" `
        -Condition ([bool]($first.summary -and $first.summary.Trim().Length -gt 0)) `
        -Detail "길이=$($first.summary.Length)자"

    # (b) pdf_id 는 백엔드 내부 식별자라 응답에 실리면 안 된다 (흐름도상 FE 로 가는 건 요약뿐).
    Assert-Test -Title "3.7.2 응답에 pdfId 가 실리지 않는다 (내부 식별자)" `
        -Condition (-not ($first.PSObject.Properties.Name -contains "pdfId"))

    # (c) 마크다운 자유 형식이라 구조를 강제하지 않는다. 다만 명세가 정한 형태(불릿 + '요약' 문단)를
    #     크게 벗어나면 프론트 렌더링 가정이 깨지므로 경고 수준으로 본다.
    if ($first.summary -notmatch '(?m)^\s*[-*]') {
        Write-Host "  (i) summary 에 불릿(-)이 없습니다. 명세는 '불릿 목록 + 요약 문단' 형태입니다." -ForegroundColor Yellow
    }
}

# ---------------------------------------------------------------------------
# 3.7.3 캐시 — 같은 PDF 를 다시 올리면 AI 를 부르지 않고 저장된 요약을 그대로 준다.
#   이 기능이 테이블을 두는 유일한 이유다. 여기가 깨지면 재업로드마다 Vision 비용이 다시 나간다.
#
#   스텁은 호출마다 [stub#N] 의 N 을 올리므로, 두 응답이 완전히 같다는 것은 두 번째가 AI 를
#   부르지 않았다는 뜻이 된다. 실제 AI 서버라면 LLM 출력이 매번 달라지므로 마찬가지로 유효하다.
# ---------------------------------------------------------------------------
if (-not $first) {
    Write-Host "`n  (i) 첫 요약이 없어 캐시 검증을 건너뜁니다." -ForegroundColor Yellow
} else {
    Write-Host "`n---------- 3.7.3 재업로드 (캐시) ----------" -ForegroundColor Magenta

    $secondResult = Invoke-ApiUpload -Path "/api/portfolios/summarize" -FilePath $pdfFile -PartName "pdf_file" -Mime $pdfMime `
        -Auth -PassThru -Title "3.7.3 같은 PDF 재업로드"
    $second = $secondResult.data

    Assert-Test -Title "3.7.3 같은 PDF 는 첫 번째와 완전히 같은 요약을 준다 (AI 재호출 없음)" `
        -Condition ($second.summary -eq $first.summary) `
        -Detail $(if ($second.summary -eq $first.summary) { "동일" } else { "달라짐 - 캐시가 동작하지 않았다" })

    # 내용이 다른 PDF 는 해시가 달라 새로 요약되어야 한다. 캐시 키가 사용자 단위로만 잡혀
    # "한 사람당 하나"가 되어버리는 사고를 여기서 잡는다.
    $otherPdf = Join-Path ([System.IO.Path]::GetTempPath()) "mateon-portfolio-$runTag-other.pdf"
    New-TestPdf -OutPath $otherPdf -Text "Completely different portfolio $runTag - Backend engineer, Kotlin"
    $tempFiles.Add($otherPdf)

    $otherResult = Invoke-ApiUpload -Path "/api/portfolios/summarize" -FilePath $otherPdf -PartName "pdf_file" -Mime $pdfMime `
        -Auth -PassThru -Title "3.7.3 내용이 다른 PDF 업로드"
    if ($otherResult.data) {
        Assert-Test -Title "3.7.3 내용이 다른 PDF 는 새로 요약된다 (캐시 키는 파일 해시다)" `
            -Condition ($otherResult.data.summary -ne $first.summary) `
            -Detail $(if ($otherResult.data.summary -ne $first.summary) { "새 요약" } else { "첫 요약과 같다 - 해시가 파일 내용을 반영하지 않는다" })
    }
}

# ---------------------------------------------------------------------------
# 3.7.4 잘못된 업로드 — 프론트가 안내 문구를 띄울 수 있게 4xx 로 구분되어야 한다.
#   (핸들러가 없으면 전부 500 "서버 오류"로 뭉개져 사용자가 뭘 고쳐야 할지 알 수 없다)
#   이 검사들은 전부 AI 호출 전에 끝나야 한다 — 그러지 않으면 잘못된 요청이 LLM 비용이 된다.
# ---------------------------------------------------------------------------
Write-Host "`n---------- 3.7.4 잘못된 업로드 ----------" -ForegroundColor Magenta

# (a) 허용하지 않는 확장자
$txtPath = Join-Path ([System.IO.Path]::GetTempPath()) "mateon-portfolio-$runTag.txt"
Copy-Item $pdfFile $txtPath -Force
$tempFiles.Add($txtPath)
Invoke-ApiUpload -Path "/api/portfolios/summarize" -FilePath $txtPath -PartName "pdf_file" -Mime "text/plain" `
    -Auth -Title "3.7.4 txt 업로드 (차단 기대)"

# (b) 확장자만 .pdf 이고 내용은 PDF 가 아니다. 시그니처(%PDF) 검사가 없으면 이게 AI 까지 가서
#     왕복 비용을 쓴 뒤 거절된다.
$fakePdf = Join-Path ([System.IO.Path]::GetTempPath()) "mateon-fake-$runTag.pdf"
Set-Content -Path $fakePdf -Value "이건 PDF 가 아니라 그냥 텍스트다." -Encoding UTF8
$tempFiles.Add($fakePdf)
Invoke-ApiUpload -Path "/api/portfolios/summarize" -FilePath $fakePdf -PartName "pdf_file" -Mime $pdfMime `
    -Auth -Title "3.7.4 확장자만 .pdf 인 파일 (차단 기대)"

# (c) 파트 이름이 다르다 — 프론트가 필드명을 잘못 쓴 경우.
#     ServletException 계열이라 핸들러가 없으면 500 이 나간다.
Invoke-ApiUpload -Path "/api/portfolios/summarize" -FilePath $pdfFile -PartName "file" -Mime $pdfMime `
    -Auth -Title "3.7.4 파트 이름 오타 (차단 기대)"

# (d) 크기 초과 — 서버 멀티파트 상한(20MB)을 넘긴다. 413 으로 안내되어야 한다.
$hugePath = Join-Path ([System.IO.Path]::GetTempPath()) "mateon-huge-$runTag.pdf"
$fs = [System.IO.File]::Create($hugePath)
$fs.SetLength(21MB)   # 실제 21MB 를 채우지 않고 크기만 잡는다 (희소 파일)
$fs.Close()
$tempFiles.Add($hugePath)
Invoke-ApiUpload -Path "/api/portfolios/summarize" -FilePath $hugePath -PartName "pdf_file" -Mime $pdfMime `
    -Auth -Title "3.7.4 20MB 초과 업로드 (차단 기대)"

} finally {
    foreach ($f in $tempFiles) { Remove-Item $f -Force -ErrorAction SilentlyContinue }
    Write-TestSummary | Out-Null
}
