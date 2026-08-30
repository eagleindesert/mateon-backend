# stub-ai-server.ps1
# 별도 FastAPI AI 서버의 스텁. 처리하는 엔드포인트:
#   POST /intents/extract                  (매칭 의도 추출)
#   POST /internal/teams/embedding:refresh (팀 임베딩 계산)
#   POST /internal/contests/embedding:refresh (공모전 임베딩 계산)
#   POST /contests/similarity-map          (공모전 유사도 지도)
#   POST /recommendations/user-to-team     (유저→팀 추천 점수 계산)
#   POST /recommendations/team-to-user     (팀→유저 역제안 추천 점수 계산)
#   POST /recommendations/reason           (선택된 한 쌍의 상세 이유)
#   POST /selection-events                 (실제로 고른 후보 + 그때 노출된 목록 기록)
#   POST /proposals/user-to-team           (최종 제안 조립 - 지원 문구 초안)
#   POST /proposals/team-to-user           (최종 역제안 조립 - 제안 문구 초안)
#   POST /contests/extract-image           (포스터 이미지에서 공모전 정보 추출, multipart)
#   POST /portfolios/summarize             (포트폴리오 PDF 요약, multipart)
#   GET  /__stub                           (신원 확인 - LiveTest 가 실서버 오조준을 막는 데 쓴다)
#
# 실제 FastAPI 를 띄울 수 없는 상황에서 백엔드 연동을 검증하기 위한 도구다.
# 실제 서버가 준비되면 이 스텁 대신 AI_BASE_URL 만 실제 주소로 바꾸면 된다.
#
# 진짜 목적: 백엔드가 보내는 요청을 콘솔에 덤프해서
#   - (intents) messages 의 id 가 1 부터 연속 증가하는지, USER 발화만 들어있는지, 누적되는지
#   - (teams)   intro_text/recruiting_roles/required_skills/contest_field 가 제대로 실려 오는지
#   - (recommendations) query_metadata 가 실려 오는지, 후보마다 1536 차원 벡터와
#     team_embeddings 의 정규화 메타데이터가 붙어 오는지, 제외 대상(내 팀/지원한 팀)이 빠졌는지
#   - X-Internal-Secret 헤더를 실어 보내는지 (실서버는 이게 없으면 401)
# 를 눈으로 확인하는 것.
#
# 사용법:
#   pwsh -File stub-ai-server.ps1                              # 기본 포트 8000
#   pwsh -File stub-ai-server.ps1 -Port 8001
#   pwsh -File stub-ai-server.ps1 -ExpectedSecret "dev-secret" # 시크릿 검증(불일치 시 401)
#
# 백엔드는 ai.base-url 이 이 주소를 가리켜야 한다 (.env 의 AI_BASE_URL).

param(
    [int]$Port = 8000,
    [int]$EmbeddingDimension = 1536,
    # 주면 X-Internal-Secret 을 검증해 불일치/누락 시 401 을 돌려준다(실서버와 같은 동작).
    # 안 주면 받은 값을 마스킹해 출력만 한다.
    [string]$ExpectedSecret = ""
)

# 시크릿을 콘솔에 그대로 찍지 않는다 — 로컬 디버그 도구라도 붙여넣기로 새어나갈 수 있다.
# 도착 여부와 길이만 보여도 검증에는 충분하다.
function Format-Secret {
    param([string]$Value)
    if (-not $Value) { return "(없음)" }
    if ($Value.Length -le 4) { return ("*" * $Value.Length) + " (len=$($Value.Length))" }
    return $Value.Substring(0, 2) + ("*" * ($Value.Length - 4)) + $Value.Substring($Value.Length - 2) + " (len=$($Value.Length))"
}

$ErrorActionPreference = "Stop"

$listener = New-Object System.Net.HttpListener
$listener.Prefixes.Add("http://localhost:$Port/")

try {
    $listener.Start()
} catch {
    Write-Host "포트 $Port 리스닝 실패: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "이미 다른 프로세스가 쓰고 있거나 권한이 없습니다." -ForegroundColor Yellow
    exit 1
}

Write-Host ("=" * 70) -ForegroundColor DarkGray
Write-Host " AI 서버 스텁 (intents, teams/embedding, recommendations+reason, selection-events, proposals, 이미지 추출, PDF 요약)" -ForegroundColor Magenta
Write-Host ("=" * 70) -ForegroundColor DarkGray
Write-Host "  리스닝: http://localhost:$Port/" -ForegroundColor Green
Write-Host "  임베딩 차원: $EmbeddingDimension" -ForegroundColor DarkGray
if ($ExpectedSecret) {
    Write-Host "  X-Internal-Secret: 검증함 (불일치/누락 시 401) - 기대값 $(Format-Secret $ExpectedSecret)" -ForegroundColor DarkGray
} else {
    Write-Host "  X-Internal-Secret: 출력만 함 (검증하려면 -ExpectedSecret 지정)" -ForegroundColor DarkGray
}
Write-Host ""
Write-Host "  동작 (/intents/extract): messages 개수로 분기" -ForegroundColor DarkGray
Write-Host "    1개      -> missing_fields=['experience_level'], 임베딩 null (재질문)" -ForegroundColor DarkGray
Write-Host "    2개 이상 -> missing_fields=[], 임베딩 $EmbeddingDimension 개 (완료)" -ForegroundColor DarkGray
Write-Host "  동작 (/internal/teams/embedding:refresh): 항상 임베딩 + metadata 반환" -ForegroundColor DarkGray
Write-Host "    (missing_fields=['activity_intensity'] — 스펙상 미추출 항목이 있어도 벡터는 온다)" -ForegroundColor DarkGray
Write-Host "  동작 (/internal/contests/embedding:refresh): event_id echo + 임베딩 $EmbeddingDimension 개" -ForegroundColor DarkGray
Write-Host "  동작 (/contests/similarity-map): 후보마다 좌표를 돌려준다. 빈 후보면 points=[]" -ForegroundColor DarkGray
Write-Host "  동작 (/recommendations/user-to-team): 역할 일치 여부로 점수 분기" -ForegroundColor DarkGray
Write-Host "    desired_roles 와 recruiting_roles 가 겹치면 0.9x, 아니면 0.1x + label 생성" -ForegroundColor DarkGray
Write-Host "    (일부러 점수 오름차순으로 돌려준다 — 백엔드가 내림차순 정렬하는지 확인용)" -ForegroundColor DarkGray
Write-Host "  동작 (/recommendations/team-to-user): 위와 같되 질의/후보가 뒤집힘 (역제안)" -ForegroundColor DarkGray
Write-Host "    query=팀(recruiting_roles), candidates=유저(desired_roles) - 겹치면 0.9x" -ForegroundColor DarkGray
Write-Host "  동작 (/recommendations/reason): 받은 세 요약을 그대로 찍고 [stub#N] 문장 반환" -ForegroundColor DarkGray
Write-Host "  동작 (/selection-events): 선택된 후보와 노출 목록을 찍고 accepted:true 반환" -ForegroundColor DarkGray
Write-Host "    component_scores 가 추천 응답과 똑같이 오는지 여기서 대조한다 (원문 보존 검증)" -ForegroundColor DarkGray
Write-Host "    N 은 호출 일련번호 — 같은 쌍을 두 번 물었는데 N 이 같으면 백엔드 캐시가 동작한 것" -ForegroundColor DarkGray
Write-Host "    candidate_summary/target_summary 가 비면 [!!] 로 표시된다 (백엔드 조립 실패)" -ForegroundColor DarkGray
Write-Host "  동작 (/proposals/*): 받은 식별자를 에코하고 [stub#N] 초안(summary+message) 반환" -ForegroundColor DarkGray
Write-Host "    sender_id/receiver_id 가 방향에 맞는지 스텁이 직접 대조해 [OK]/[!!] 로 표시" -ForegroundColor DarkGray
Write-Host "    초안은 캐시하지 않는 게 정상이라 같은 쌍을 두 번 물으면 N 이 올라가야 한다" -ForegroundColor DarkGray
Write-Host "  동작 (/portfolios/summarize): 파트명 pdf_file 과 %PDF 시그니처를 확인하고 [stub#N] 요약 반환" -ForegroundColor DarkGray
Write-Host "    같은 PDF 를 두 번 올렸는데 N 이 그대로면 백엔드 캐시가 동작한 것 (reason 과 같은 기대)" -ForegroundColor DarkGray
Write-Host "    pdf_id 는 더미 고정값이라 백엔드에 '해시 불일치' 경고가 뜨는 게 정상이다" -ForegroundColor DarkGray
Write-Host ""
Write-Host "  중지: Ctrl+C" -ForegroundColor Yellow
Write-Host ""

$rand = New-Object System.Random

# /recommendations/reason 호출 일련번호. 생성된 문장에 박아 넣어 백엔드의 이유 캐시가
# 동작하는지 밖에서 확인할 수 있게 한다 (같은 쌍을 두 번 물었는데 번호가 같으면 캐시 hit).
$script:ReasonCallCount = 0

# /proposals/* 호출 일련번호. 위와 같은 장치인데 기대하는 결론이 반대다 — 제안 초안은 캐시하지
# 않기로 했으므로, 같은 쌍을 두 번 물으면 번호가 반드시 올라가야 한다.
$script:ProposalCallCount = 0

# /portfolios/summarize 호출 일련번호. 요약 문구에 박아 넣어 백엔드의 PDF 요약 캐시가
# 동작하는지 밖에서 확인한다 (같은 PDF 를 두 번 올렸는데 번호가 같으면 캐시 hit).
$script:PortfolioCallCount = 0

# 난수 임베딩 벡터 생성 (두 엔드포인트가 공유)
function New-StubVector {
    param([int]$Dimension)
    $vector = New-Object 'double[]' $Dimension
    for ($i = 0; $i -lt $Dimension; $i++) {
        $vector[$i] = [Math]::Round(($rand.NextDouble() * 2 - 1), 6)
    }
    return ,$vector   # 콤마: 배열이 파이프라인에서 풀리지 않게
}

function Show-Nullable {
    # "키가 없다"(스키마 어긋남)와 "키는 있고 값이 null 이다"(정상)를 구분해 보여 준다.
    # activity_time 처럼 당분간 항상 null 인 필드는 이 구분이 있어야 검증이 된다.
    param($Object, [string]$Name)
    if ($null -eq $Object -or -not ($Object.PSObject.Properties.Name -contains $Name)) {
        return "<키 없음!>"
    }
    $value = $Object.$Name
    if ($null -eq $value) { return "null" }
    return $value
}

function New-ComponentScores {
    # 선택 피드백으로 되돌아올 값이다. 스텁이 굳이 6키를 다 채우는 이유는,
    # 백엔드가 이걸 **원문 그대로** 보관했다가 /selection-events 로 되보내는지
    # 눈으로 대조하기 위해서다 (키 이름/순서/값이 하나라도 바뀌면 계약 위반이다).
    param([bool]$Matched, [double]$Score)
    return [ordered]@{
        similarity           = [Math]::Round($Score * 0.85, 4)
        role_match           = if ($Matched) { 1.0 } else { 0.0 }
        deficit_fit          = if ($Matched) { 1.0 } else { 0.0 }
        activity_style_match = 0.5
        beginner_fit         = 1.0
        activity_time_match  = 0.0
    }
}

function Write-Json {
    param($Response, $Object, [int]$StatusCode = 200)
    $json  = $Object | ConvertTo-Json -Depth 10 -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    $Response.StatusCode = $StatusCode
    $Response.ContentType = "application/json; charset=utf-8"
    $Response.ContentLength64 = $bytes.Length
    $Response.OutputStream.Write($bytes, 0, $bytes.Length)
    $Response.OutputStream.Close()
}

try {
    while ($listener.IsListening) {
        $context  = $listener.GetContext()
        $request  = $context.Request
        $response = $context.Response

        $path = $request.Url.AbsolutePath
        Write-Host ("-" * 70) -ForegroundColor DarkGray
        Write-Host ("[{0}] {1} {2}" -f (Get-Date -Format "HH:mm:ss"), $request.HttpMethod, $path) -ForegroundColor Cyan

        # --- GET /__stub (신원 확인) ---
        # LiveTest 가 "지금 붙은 게 정말 이 스텁인가"를 확인하는 자리다. 실수로 실서버 주소를
        # AI_STUB_BASE_URL 에 넣어도 이 응답이 안 오므로 테스트가 호출을 시작하지 않는다.
        # 아래 knownPaths 검사보다 앞에 둬야 한다 - POST 가 아니면 거기서 404 로 떨어진다.
        if ($request.HttpMethod -eq "GET" -and $path -eq "/__stub") {
            Write-Host "  -> 200 (신원 확인 - LiveTest 가 붙었다)" -ForegroundColor Green
            Write-Json -Response $response -Object ([ordered]@{
                stub = "mateon-ai-stub"
                embedding_dimension = $EmbeddingDimension
            })
            continue
        }

        $knownPaths = @("/intents/extract", "/internal/teams/embedding:refresh",
                        "/internal/contests/embedding:refresh",
                        "/contests/similarity-map",
                        "/recommendations/user-to-team", "/recommendations/team-to-user",
                        "/recommendations/reason",
                        "/selection-events",
                        "/proposals/user-to-team", "/proposals/team-to-user",
                        "/contests/extract-image", "/portfolios/summarize")
        if ($request.HttpMethod -ne "POST" -or $knownPaths -notcontains $path) {
            Write-Host "  -> 404 (이 스텁은 POST $($knownPaths -join ', ') 만 처리)" -ForegroundColor Yellow
            Write-Json -Response $response -Object @{ detail = "Not Found" } -StatusCode 404
            continue
        }

        # --- X-Internal-Secret 검증 (실서버가 요구하는 내부 인증 헤더) ---
        $secret = $request.Headers["X-Internal-Secret"]
        if ($secret) {
            Write-Host "  X-Internal-Secret: $(Format-Secret $secret)" -ForegroundColor Gray
        } else {
            Write-Host "  [!!] X-Internal-Secret 헤더 없음 - 실서버라면 401 로 거절된다" -ForegroundColor Red
        }

        if ($ExpectedSecret) {
            if (-not $secret) {
                Write-Host "  -> 401 (헤더 누락)" -ForegroundColor Red
                Write-Json -Response $response -Object @{ detail = "Missing X-Internal-Secret" } -StatusCode 401
                continue
            }
            if ($secret -cne $ExpectedSecret) {
                Write-Host "  -> 401 (시크릿 불일치)" -ForegroundColor Red
                Write-Json -Response $response -Object @{ detail = "Invalid X-Internal-Secret" } -StatusCode 401
                continue
            }
            Write-Host "  [OK] 시크릿 일치" -ForegroundColor Green
        }

        # --- POST /contests/extract-image (포스터 이미지에서 공모전 정보 추출) ---
        # 이 엔드포인트만 multipart/form-data 다. 아래 JSON 파싱 블록보다 먼저 처리해야 한다
        # (이미지 바이트를 JSON 으로 읽으려 하면 400 이 나간다).
        if ($path -eq "/contests/extract-image") {
            $buffer = New-Object System.IO.MemoryStream
            $request.InputStream.CopyTo($buffer)
            $raw = $buffer.ToArray()
            $buffer.Close()

            Write-Host "  Content-Type: $($request.ContentType)" -ForegroundColor Gray
            Write-Host ("  본문 {0} bytes" -f $raw.Length) -ForegroundColor Gray
            if ($request.ContentType -notmatch "multipart/form-data") {
                Write-Host "  [!!] multipart/form-data 가 아님 - 실서버라면 422" -ForegroundColor Red
            }
            # 파일 파트 이름과 filename 이 실려 있는지 확인한다. 파일명이 빠지면 FastAPI 가
            # UploadFile 로 인식하지 못해 422 를 낸다.
            $head = [System.Text.Encoding]::UTF8.GetString($raw, 0, [Math]::Min($raw.Length, 512))
            if ($head -match 'name="img_file"') {
                Write-Host "  [OK] 파트 이름 img_file" -ForegroundColor Green
            } else {
                Write-Host "  [!!] 파트 이름이 img_file 이 아님 - 실서버라면 422" -ForegroundColor Red
            }
            if ($head -match 'filename="([^"]*)"') {
                Write-Host "  [OK] filename=$($Matches[1])" -ForegroundColor Green
            } else {
                Write-Host "  [!!] filename 없음 - 실서버라면 422" -ForegroundColor Red
            }

            # 응답은 AI 명세의 예시 그대로. image_url/detail_url 은 null 로 두어
            # 백엔드가 자기 버킷 URL 로 채우는지 확인할 수 있게 한다.
            $payload = [ordered]@{
                external_id             = $null
                category                = "CONTEST"
                field                   = "PLANNING_IDEA"
                title                   = "2026 제10회 <051영화제> 51초 영화 공모전"
                organizer               = "부산시사회복지협의회"
                target_school           = $null
                start_date              = "2026-07-01"
                end_date                = "2026-07-31"
                detail_url              = $null
                image_url               = $null
                description             = "주제`n'연결'`n`n(스텁 응답)"
                summarized_description  = "부산시사회복지협의회에서 주관하는 '51초 영화 공모전' 공고입니다."
                recommended_targets     = "대상 제한 없음, 역량 강화 및 포트폴리오 구축 희망자"
            }
            Write-Host "  -> 200 (공모전 추출 결과)" -ForegroundColor Green
            Write-Json -Response $response -Object $payload
            continue
        }

        # --- POST /portfolios/summarize (포트폴리오 PDF 요약) ---
        # /contests/extract-image 와 같은 이유로 JSON 파싱 블록보다 먼저 처리한다 (multipart).
        if ($path -eq "/portfolios/summarize") {
            $buffer = New-Object System.IO.MemoryStream
            $request.InputStream.CopyTo($buffer)
            $raw = $buffer.ToArray()
            $buffer.Close()

            Write-Host "  Content-Type: $($request.ContentType)" -ForegroundColor Gray
            Write-Host ("  본문 {0} bytes" -f $raw.Length) -ForegroundColor Gray
            if ($request.ContentType -notmatch "multipart/form-data") {
                Write-Host "  [!!] multipart/form-data 가 아님 - 실서버라면 422" -ForegroundColor Red
            }

            $head = [System.Text.Encoding]::UTF8.GetString($raw, 0, [Math]::Min($raw.Length, 512))
            if ($head -match 'name="pdf_file"') {
                Write-Host "  [OK] 파트 이름 pdf_file" -ForegroundColor Green
            } else {
                Write-Host "  [!!] 파트 이름이 pdf_file 이 아님 - 실서버라면 422" -ForegroundColor Red
            }
            if ($head -match 'filename="([^"]*)"') {
                Write-Host "  [OK] filename=$($Matches[1])" -ForegroundColor Green
            } else {
                Write-Host "  [!!] filename 없음 - 실서버라면 422" -ForegroundColor Red
            }
            # 파트 본문이 %PDF 로 시작하는지 — 백엔드가 시그니처 검사를 통과시킨 파일만 보내는지 본다.
            if ($head -match '%PDF') {
                Write-Host "  [OK] 파트 본문에 %PDF 시그니처가 있다" -ForegroundColor Green
            } else {
                Write-Host "  [!] %PDF 시그니처가 앞부분에 안 보인다 (백엔드 검사를 확인하세요)" -ForegroundColor Yellow
            }

            # 호출마다 다른 요약을 준다. 백엔드가 같은 PDF 를 캐시하는지 밖에서 확인할 방법이
            # 이것뿐이다 — 같은 파일을 두 번 올렸는데 번호가 그대로면 두 번째는 AI 를 부르지 않은 것이다.
            $script:PortfolioCallCount++

            # pdf_id 는 일부러 고정 더미값을 준다. 실서버는 PDF 바이트의 SHA-256 을 주지만, 백엔드는
            # 자기가 계산한 해시를 캐시 키로 써야 한다(AI 값에 의존하면 캐시가 AI 구현에 묶인다).
            # 그래서 백엔드 로그에 "AI 가 보낸 pdf_id 가 우리 SHA-256 과 다릅니다" 경고가 뜨는 게 정상이다.
            $payload = [ordered]@{
                pdf_id   = "0000000000000000000000000000000000000000000000000000000000000000"
                response = "- [stub#$($script:PortfolioCallCount)] OO 서비스 프론트엔드 개발, React/TypeScript 로 대시보드 UI 구현`n" +
                           "- OO 해커톤 팀 프로젝트, 백엔드 API 설계 및 배포`n`n" +
                           "요약`n이 사용자는 프론트엔드를 중심으로 실무 프로젝트 경험을 쌓아왔으며, 백엔드 협업 경험도 일부 있습니다."
            }
            Write-Host "  -> 200 (포트폴리오 요약 #$($script:PortfolioCallCount), pdf_id 는 더미)" -ForegroundColor Green
            Write-Json -Response $response -Object $payload
            continue
        }

        # --- 요청 본문 읽기 ---
        $reader = New-Object System.IO.StreamReader($request.InputStream, [System.Text.Encoding]::UTF8)
        $bodyText = $reader.ReadToEnd()
        $reader.Close()

        try {
            $body = $bodyText | ConvertFrom-Json
        } catch {
            Write-Host "  -> 400 (JSON 파싱 실패): $bodyText" -ForegroundColor Red
            Write-Json -Response $response -Object @{ detail = "Invalid JSON" } -StatusCode 400
            continue
        }

        # --- POST /internal/teams/embedding:refresh (팀 임베딩 계산) ---
        if ($path -eq "/internal/teams/embedding:refresh") {
            # 백엔드가 보내는 값을 눈으로 검증할 수 있게 전부 덤프
            Write-Host "  받은 요청:" -ForegroundColor White
            Write-Host ("    intro_text       = {0}" -f $body.intro_text) -ForegroundColor Gray
            Write-Host ("    recruiting_roles = [{0}]" -f (@($body.recruiting_roles) -join ", ")) -ForegroundColor Gray
            Write-Host ("    required_skills  = [{0}]" -f (@($body.required_skills) -join ", ")) -ForegroundColor Gray
            Write-Host ("    contest_field    = {0}" -f $body.contest_field) -ForegroundColor Gray

            # 스펙: 미추출 항목(missing_fields)이 있어도 임베딩과 metadata 는 항상 반환된다.
            $payload = [ordered]@{
                missing_fields   = @("activity_intensity")
                embedding_text   = "팀 소개: $($body.intro_text)`n모집 역할: $(@($body.recruiting_roles) -join ', ')`n요구 스킬: $(@($body.required_skills) -join ', ')"
                embedding_vector = (New-StubVector -Dimension $EmbeddingDimension)
                metadata = [ordered]@{
                    recruiting_roles  = @($body.recruiting_roles)
                    required_skills   = @($body.required_skills)
                    activity_goal     = "교내 공모전 수상"
                    activity_style    = "오프라인 모임"
                    beginner_friendly = $true
                }
            }
            Write-Host "  -> 200 (missing_fields=['activity_intensity'], 임베딩 $EmbeddingDimension 개)" -ForegroundColor Green
            Write-Json -Response $response -Object $payload
            continue
        }

        # --- POST /internal/contests/embedding:refresh (공모전 임베딩 계산) ---
        if ($path -eq "/internal/contests/embedding:refresh") {
            Write-Host "  받은 요청:" -ForegroundColor White
            Write-Host ("    event_id    = {0}" -f $body.event_id) -ForegroundColor Gray
            Write-Host ("    title       = {0}" -f $body.title) -ForegroundColor Gray
            $desc = $body.description
            if ($null -eq $desc) {
                Write-Host "    description = <키 없음 또는 null> - 실서버라면 422" -ForegroundColor Red
            } else {
                Write-Host ("    description = {0} chars" -f $desc.ToString().Length) -ForegroundColor Gray
            }

            $payload = [ordered]@{
                event_id         = $body.event_id
                embedding_vector = (New-StubVector -Dimension $EmbeddingDimension)
            }
            Write-Host "  -> 200 (event_id echo, 임베딩 $EmbeddingDimension 개)" -ForegroundColor Green
            Write-Json -Response $response -Object $payload
            continue
        }

        # --- POST /contests/similarity-map (공모전 유사도 지도) ---
        if ($path -eq "/contests/similarity-map") {
            $query = $body.query
            $candidates = @($body.candidates)
            if ($null -eq $body.candidates) { $candidates = @() }

            Write-Host ("  query.id={0} title={1} field={2} vector={3}차원 top_n={4}" -f `
              $query.id, $query.title, $query.field, @($query.embedding_vector).Count, $body.top_n) -ForegroundColor Gray
            Write-Host ("  후보 {0}개" -f $candidates.Count) -ForegroundColor White

            function Field-Label {
                param($Field)
                if ($null -eq $Field -or $Field -eq "") { return $null }
                switch ($Field) {
                    "EDUCATION" { return "교육" }
                    "PLANNING_IDEA" { return "기획/아이디어" }
                    default { return $Field }
                }
            }

            $queryOut = [ordered]@{
                id          = $query.id
                title       = $query.title
                organizer   = $query.organizer
                category    = $query.category
                field       = $query.field
                field_label = (Field-Label $query.field)
                detail_url  = $query.detail_url
            }

            if ($candidates.Count -eq 0) {
                $payload = [ordered]@{
                    query                 = $queryOut
                    points                = @()
                    max_radius            = 12.0
                    min_radius            = 2.6
                    radial_jitter         = 0.5
                    reference_rings       = @()
                    candidate_pool_total  = 0
                }
                Write-Host "  -> 200 (빈 후보)" -ForegroundColor Green
                Write-Json -Response $response -Object $payload
                continue
            }

            $points = [System.Collections.Generic.List[object]]::new()
            $i = 0
            $n = $candidates.Count
            foreach ($c in $candidates) {
                $pct = if ($n -le 1) { 0.0 } else { [Math]::Round($i / ($n - 1), 4) }
                $radius = [Math]::Round(2.6 + $pct * (12.0 - 2.6), 3)
                [void]$points.Add([ordered]@{
                    id              = $c.id
                    title           = $c.title
                    organizer       = $c.organizer
                    category        = $c.category
                    field           = $c.field
                    field_label     = (Field-Label $c.field)
                    detail_url      = $c.detail_url
                    similarity      = [Math]::Round(0.9 - ($i * 0.05), 3)
                    rank_percentile = $pct
                    radius          = $radius
                    x               = [Math]::Round(-$radius, 3)
                    y               = [Math]::Round(0.025 * ($i + 1), 3)
                })
                $i++
            }
            $pointList = @($points)

            $rings = @(
                [ordered]@{ percentile = 0.1; similarity_at_percentile = $pointList[0].similarity; radius = 3.54 },
                [ordered]@{ percentile = 0.3; similarity_at_percentile = $pointList[0].similarity; radius = 5.42 },
                [ordered]@{ percentile = 0.6; similarity_at_percentile = $pointList[0].similarity; radius = 8.24 },
                [ordered]@{ percentile = 0.9; similarity_at_percentile = $pointList[0].similarity; radius = 11.06 }
            )

            $payload = [ordered]@{
                query                 = $queryOut
                points                = $pointList
                max_radius            = 12.0
                min_radius            = 2.6
                radial_jitter         = 0.5
                reference_rings       = $rings
                candidate_pool_total  = $candidates.Count
            }
            Write-Host "  -> 200 (points=$($points.Count))" -ForegroundColor Green
            Write-Json -Response $response -Object $payload
            continue
        }

        # --- POST /recommendations/user-to-team (유저→팀 추천 점수 계산) ---
        if ($path -eq "/recommendations/user-to-team") {
            $queryVector = @($body.query_embedding_vector)
            $queryMeta   = $body.query_metadata
            $candidates  = @($body.candidates)

            Write-Host "  query_embedding_vector: $($queryVector.Count) 차원" -ForegroundColor Gray
            if ($queryVector.Count -ne $EmbeddingDimension) {
                Write-Host "  [!!] 질의 벡터 차원이 $EmbeddingDimension 이 아님" -ForegroundColor Red
            }

            if ($null -eq $queryMeta) {
                # 이게 없으면 실서버는 임베딩 유사도만 계산할 수 있고 룰 점수를 못 낸다.
                Write-Host "  [!!] query_metadata 없음 - 역할 일치 등 룰 점수를 계산할 수 없다" -ForegroundColor Red
            } else {
                Write-Host "  query_metadata:" -ForegroundColor White
                Write-Host ("    desired_roles    = [{0}]" -f (@($queryMeta.desired_roles) -join ", ")) -ForegroundColor Gray
                Write-Host ("    skills           = [{0}]" -f (@($queryMeta.skills) -join ", ")) -ForegroundColor Gray
                Write-Host ("    activity_style   = {0}" -f $queryMeta.activity_style) -ForegroundColor Gray
                Write-Host ("    experience_level = {0}" -f $queryMeta.experience_level) -ForegroundColor Gray
                # activity_time 은 FE 입력값이라 당분간 항상 null 이다. 키 자체가 빠지면
                # AI 스키마와 어긋난 것이므로 있음/없음을 구분해 찍는다.
                Write-Host ("    activity_time    = {0}" -f (Show-Nullable $queryMeta 'activity_time')) -ForegroundColor Gray
            }

            Write-Host "  후보 $($candidates.Count)개:" -ForegroundColor White
            $desiredRoles = @($queryMeta.desired_roles)
            $recommendations = @()
            $i = 0

            foreach ($c in $candidates) {
                $vector = @($c.embedding_vector)
                $meta   = $c.metadata
                $roles  = @($meta.recruiting_roles)
                $dimOk  = if ($vector.Count -eq $EmbeddingDimension) { "OK" } else { "!! $($vector.Count)차원" }

                Write-Host ("    candidate_id={0}  vector={1}  recruiting_roles=[{2}]  required_skills=[{3}]  activity_style={4}  beginner_friendly={5}  activity_time={6}" -f `
                    $c.candidate_id, $dimOk, ($roles -join ", "), (@($meta.required_skills) -join ", "), $meta.activity_style, $meta.beginner_friendly, (Show-Nullable $meta 'activity_time')) -ForegroundColor Gray

                # 역할이 겹치면 높은 점수 + 역할 근거 문구, 아니면 낮은 점수 + 유사도 문구.
                # 같은 점수가 안 나오게 후보 순번으로 미세하게 흔든다 (정렬 검증용).
                $matched = @($roles | Where-Object { $desiredRoles -contains $_ })
                if ($matched.Count -gt 0) {
                    $score = [Math]::Round(0.90 + ($i * 0.001), 4)
                    $label = "$($matched[0]) 역할을 모집하고 있어요"
                } elseif ($meta.beginner_friendly -eq $true) {
                    $score = [Math]::Round(0.30 + ($i * 0.001), 4)
                    $label = "초보자도 편하게 참여할 수 있는 팀이에요"
                } else {
                    $score = [Math]::Round(0.10 + ($i * 0.001), 4)
                    $label = "의미적으로 관심사가 잘 맞아요"
                }

                $recommendations += [ordered]@{
                    candidate_id     = $c.candidate_id
                    score            = $score
                    label            = $label
                    component_scores = New-ComponentScores -Matched ($matched.Count -gt 0) -Score $score
                }
                $i++
            }

            # 일부러 점수 오름차순으로 돌려준다 — 백엔드가 스스로 내림차순 정렬하는지 확인하려면
            # 이미 정렬된 응답을 주면 안 된다.
            $recommendations = @($recommendations | Sort-Object { $_.score })

            Write-Host "  -> 200 (recommendations $($recommendations.Count)건, 점수 오름차순으로 반환)" -ForegroundColor Green
            Write-Json -Response $response -Object ([ordered]@{ recommendations = $recommendations })
            continue
        }

        # --- POST /recommendations/team-to-user (팀→유저 역제안 추천 점수 계산) ---
        # user-to-team 과 스키마가 같고 질의/후보의 자리만 뒤집힌다:
        #   query_metadata      = 팀   (recruiting_roles / required_skills / activity_style / beginner_friendly)
        #   candidates[].metadata = 유저 (desired_roles / skills / experience_level / activity_style)
        if ($path -eq "/recommendations/team-to-user") {
            $queryVector = @($body.query_embedding_vector)
            $queryMeta   = $body.query_metadata
            $candidates  = @($body.candidates)

            Write-Host "  query_embedding_vector: $($queryVector.Count) 차원 (팀 임베딩 재사용)" -ForegroundColor Gray
            if ($queryVector.Count -ne $EmbeddingDimension) {
                Write-Host "  [!!] 질의 벡터 차원이 $EmbeddingDimension 이 아님" -ForegroundColor Red
            }

            if ($null -eq $queryMeta) {
                Write-Host "  [!!] query_metadata 없음 - 역할 일치 등 룰 점수를 계산할 수 없다" -ForegroundColor Red
            } else {
                Write-Host "  query_metadata (팀):" -ForegroundColor White
                Write-Host ("    recruiting_roles  = [{0}]" -f (@($queryMeta.recruiting_roles) -join ", ")) -ForegroundColor Gray
                Write-Host ("    required_skills   = [{0}]" -f (@($queryMeta.required_skills) -join ", ")) -ForegroundColor Gray
                Write-Host ("    activity_style    = {0}" -f $queryMeta.activity_style) -ForegroundColor Gray
                Write-Host ("    beginner_friendly = {0}" -f $queryMeta.beginner_friendly) -ForegroundColor Gray
                Write-Host ("    activity_time     = {0}" -f (Show-Nullable $queryMeta 'activity_time')) -ForegroundColor Gray
                # contest_field 는 events 에서 오는 값이라 실제로 채워져 나가야 한다
                # (자율 프로젝트 팀이면 null 이 맞다).
                Write-Host ("    contest_field     = {0}" -f (Show-Nullable $queryMeta 'contest_field')) -ForegroundColor Gray
            }

            Write-Host "  후보 유저 $($candidates.Count)명:" -ForegroundColor White
            $recruitingRoles = @($queryMeta.recruiting_roles)
            $recommendations = @()
            $i = 0

            foreach ($c in $candidates) {
                $vector = @($c.embedding_vector)
                $meta   = $c.metadata
                $roles  = @($meta.desired_roles)
                $dimOk  = if ($vector.Count -eq $EmbeddingDimension) { "OK" } else { "!! $($vector.Count)차원" }

                Write-Host ("    candidate_id={0}  vector={1}  desired_roles=[{2}]  skills=[{3}]  experience_level={4}  activity_style={5}  activity_time={6}" -f `
                    $c.candidate_id, $dimOk, ($roles -join ", "), (@($meta.skills) -join ", "), $meta.experience_level, $meta.activity_style, (Show-Nullable $meta 'activity_time')) -ForegroundColor Gray

                # 유저의 희망 역할이 팀의 모집 역할과 겹치면 높은 점수.
                # 같은 점수가 안 나오게 후보 순번으로 미세하게 흔든다 (정렬 검증용).
                $matched = @($roles | Where-Object { $recruitingRoles -contains $_ })
                if ($matched.Count -gt 0) {
                    $score = [Math]::Round(0.90 + ($i * 0.001), 4)
                    $label = "$($matched[0]) 역할을 희망하고 있어요"
                } elseif ($queryMeta.beginner_friendly -eq $true -and $meta.experience_level -eq "beginner") {
                    $score = [Math]::Round(0.30 + ($i * 0.001), 4)
                    $label = "초보자를 환영하는 팀 분위기와 잘 맞아요"
                } else {
                    $score = [Math]::Round(0.10 + ($i * 0.001), 4)
                    $label = "의미적으로 관심사가 잘 맞아요"
                }

                $recommendations += [ordered]@{
                    candidate_id     = $c.candidate_id
                    score            = $score
                    label            = $label
                    component_scores = New-ComponentScores -Matched ($matched.Count -gt 0) -Score $score
                }
                $i++
            }

            # user-to-team 과 마찬가지로 일부러 점수 오름차순으로 돌려준다.
            $recommendations = @($recommendations | Sort-Object { $_.score })

            Write-Host "  -> 200 (recommendations $($recommendations.Count)건, 점수 오름차순으로 반환)" -ForegroundColor Green
            Write-Json -Response $response -Object ([ordered]@{ recommendations = $recommendations })
            continue
        }

        # --- POST /selection-events (선택 피드백 기록) ---
        # 이 핸들러의 목적은 응답을 흉내내는 게 아니라 **백엔드가 무엇을 보내는지**를 눈으로
        # 확인하는 것이다. 응답은 항상 { accepted = true } 하나뿐이라 볼 게 없다.
        #
        # 눈여겨볼 것 세 가지:
        #   1. shown_candidates 건수가 화면에 내려간 상위 N 과 같은가?
        #      (점수화된 후보 전체가 오면 shown_count 로 자르는 로직이 깨진 것이다)
        #   2. component_scores 가 추천 응답에서 준 것과 키/값이 똑같은가?
        #      (따옴표로 감싼 문자열로 오면 @JsonRawValue 가 빠진 것이다)
        #   3. selected_candidate_id 가 shown_candidates 안에 있는가?
        if ($path -eq "/selection-events") {
            $ctx   = $body.selection_context
            $shown = @($ctx.shown_candidates)

            Write-Host ("  direction             = {0}" -f $body.direction) -ForegroundColor White
            Write-Host ("  selected_candidate_id = {0}" -f $body.selected_candidate_id) -ForegroundColor White

            if ($null -eq $ctx) {
                Write-Host "  [!!] selection_context 없음 - 기록할 컨텍스트가 통째로 빠졌다" -ForegroundColor Red
            } else {
                Write-Host ("  idempotency_key       = {0}" -f $ctx.idempotency_key) -ForegroundColor Gray
                Write-Host "  chooser_fields:" -ForegroundColor White
                if ($null -eq $ctx.chooser_fields -or $ctx.chooser_fields.PSObject.Properties.Count -eq 0) {
                    Write-Host "    (비어 있음 - 클러스터 분석이 안 된다)" -ForegroundColor Yellow
                } else {
                    foreach ($field in $ctx.chooser_fields.PSObject.Properties) {
                        $value = if ($field.Value -is [Array]) { "[" + ($field.Value -join ", ") + "]" } else { $field.Value }
                        Write-Host ("    {0} = {1}" -f $field.Name, $value) -ForegroundColor Gray
                    }
                }

                Write-Host ("  shown_candidates {0}건:" -f $shown.Count) -ForegroundColor White
                $selectedFound = $false
                foreach ($sc in $shown) {
                    if ($sc.candidate_id -eq $body.selected_candidate_id) { $selectedFound = $true }

                    # component_scores 가 객체가 아니라 문자열로 왔으면 원문 보존이 깨진 것이다.
                    if ($null -eq $sc.component_scores) {
                        $scores = "null (추천 당시 AI 가 주지 않았음)"
                    } elseif ($sc.component_scores -is [string]) {
                        $scores = "[!!] 문자열로 옴 - @JsonRawValue 누락: $($sc.component_scores)"
                    } else {
                        $scores = ($sc.component_scores.PSObject.Properties |
                            ForEach-Object { "$($_.Name)=$($_.Value)" }) -join " "
                    }

                    $mark = if ($sc.candidate_id -eq $body.selected_candidate_id) { "*" } else { " " }
                    Write-Host ("   {0} candidate_id={1}  total_score={2}  component_scores: {3}" -f `
                        $mark, $sc.candidate_id, $sc.total_score, $scores) -ForegroundColor Gray
                }

                if ($shown.Count -eq 0) {
                    Write-Host "  [!] shown_candidates 가 비었다 - 선택 대비 분석을 할 수 없다" -ForegroundColor Yellow
                } elseif (-not $selectedFound) {
                    Write-Host "  [!!] selected_candidate_id 가 shown_candidates 안에 없다" -ForegroundColor Red
                }
            }

            # 명세대로 저장을 기다리지 않고 즉시 접수 확인만 돌려준다.
            Write-Host "  -> 200 { accepted: true } (저장 확인이 아니라 접수 확인이다)" -ForegroundColor Green
            Write-Json -Response $response -Object ([ordered]@{ accepted = $true })
            continue
        }

        # --- POST /recommendations/reason (추천 상세 이유, lazy) ---
        # 이 핸들러의 목적은 응답을 흉내내는 게 아니라 **백엔드가 세 값을 제대로 채워 보내는지**를
        # 눈으로 확인하는 것이다. 그 세 값은 DB 컬럼이 아니라 백엔드가 조립한 것이라
        # (RecommendationSummaryFactory) 조용히 비어 나가도 AI 는 그럴듯한 문장을 지어내
        # 아무도 눈치채지 못한다. 그래서 여기서 크게 찍는다.
        #
        # 요청에 direction 이 없는 게 정상이다 — 두 요약 텍스트만으로 LLM 이 판단한다.
        if ($path -eq "/recommendations/reason") {
            $candidateSummary = $body.candidate_summary
            $targetSummary    = $body.target_summary
            $scoreContext     = $body.score_context

            Write-Host "  candidate_summary:" -ForegroundColor White
            if ([string]::IsNullOrWhiteSpace($candidateSummary)) {
                Write-Host "    [!!] 비어 있음 - 백엔드가 요약을 조립하지 못했다" -ForegroundColor Red
            } else {
                Write-Host "    $candidateSummary" -ForegroundColor Gray
            }

            Write-Host "  target_summary:" -ForegroundColor White
            if ([string]::IsNullOrWhiteSpace($targetSummary)) {
                Write-Host "    [!!] 비어 있음 - 백엔드가 요약을 조립하지 못했다" -ForegroundColor Red
            } else {
                Write-Host "    $targetSummary" -ForegroundColor Gray
            }

            # score_context 는 빈 값도 명세상 허용이라 경고 수준을 낮춘다.
            Write-Host "  score_context:" -ForegroundColor White
            if ([string]::IsNullOrWhiteSpace($scoreContext)) {
                Write-Host "    [!] 비어 있음 (명세상 허용이지만 이유 품질이 떨어진다)" -ForegroundColor Yellow
            } else {
                Write-Host "    $scoreContext" -ForegroundColor Gray
            }

            # 호출 일련번호를 문장에 박는다. 백엔드가 이유를 캐시하는지 밖에서 확인할 방법이
            # 이것뿐이다 — 같은 쌍을 두 번 요청했을 때 번호까지 같으면 두 번째는 AI 를 부르지
            # 않았다는 뜻이다. 번호가 올라갔으면 캐시가 동작하지 않은 것이다.
            $script:ReasonCallCount++
            $payload = [ordered]@{
                reason = "[stub#$($script:ReasonCallCount)] 후보($candidateSummary)와 " +
                         "대상($targetSummary)은 $scoreContext 기준으로 잘 맞습니다."
            }

            Write-Host "  -> 200 (reason 생성 #$($script:ReasonCallCount), $($payload.reason.Length)자)" -ForegroundColor Green
            Write-Json -Response $response -Object $payload
            continue
        }

        # --- POST /proposals/user-to-team, /proposals/team-to-user (최종 제안 조립) ---
        #
        # 두 방향이 같은 스키마(ProposalAssemblyRequest)를 쓰고 경로만 다르다. 응답 ProposalSchema
        # 는 받은 식별자를 그대로 에코하고 direction/summary/message 를 얹는다.
        #
        # 여기서 눈으로 볼 것:
        #   - sender_id/receiver_id 가 방향에 맞게 뒤집혔는가 (user-to-team 이면 sender=user_id)
        #   - synergy_score 가 추천 목록에서 본 점수와 같은가 (조립 단계에서 재계산하지 않는다)
        #   - candidate_summary/target_summary 가 채워져 나가는가 (reason 과 같은 리스크)
        #   - contest_id 가 null 인가 (자율 프로젝트 팀. 실서버는 이걸 거부할 수 있다)
        if ($path -eq "/proposals/user-to-team" -or $path -eq "/proposals/team-to-user") {
            $direction = if ($path -eq "/proposals/user-to-team") { "USER_TO_TEAM" } else { "TEAM_TO_USER" }
            Write-Host "  direction(경로에서): $direction" -ForegroundColor White

            Write-Host ("  user_id={0}  team_id={1}  contest_id={2}  intent_id={3}" -f `
                $body.user_id, $body.team_id,
                $(if ($null -eq $body.contest_id) { "null(자율 프로젝트)" } else { $body.contest_id }),
                $body.intent_id) -ForegroundColor Gray

            # 방향에 맞는 sender/receiver 를 스텁이 직접 계산해 비교한다. 자리를 바꿔 보내는
            # 실수는 응답만 봐서는 절대 드러나지 않는다 (AI 가 되돌려 주기만 하므로).
            $expectedSender   = if ($direction -eq "USER_TO_TEAM") { $body.user_id } else { $body.team_id }
            $expectedReceiver = if ($direction -eq "USER_TO_TEAM") { $body.team_id } else { $body.user_id }
            if ($body.sender_id -eq $expectedSender -and $body.receiver_id -eq $expectedReceiver) {
                Write-Host ("  [OK] sender_id={0} receiver_id={1} - 방향에 맞다" -f `
                    $body.sender_id, $body.receiver_id) -ForegroundColor Green
            } else {
                Write-Host ("  [!!] sender/receiver 가 뒤집혔다. 받은 sender={0} receiver={1}, 기대 sender={2} receiver={3}" -f `
                    $body.sender_id, $body.receiver_id, $expectedSender, $expectedReceiver) -ForegroundColor Red
            }

            if ($null -eq $body.synergy_score) {
                Write-Host "  [!!] synergy_score 가 없다 - 추천 이력에서 못 가져온 것" -ForegroundColor Red
            } else {
                Write-Host "  synergy_score: $($body.synergy_score)" -ForegroundColor Gray
            }

            foreach ($field in @("candidate_summary", "target_summary")) {
                $value = $body.$field
                Write-Host "  ${field}:" -ForegroundColor White
                if ([string]::IsNullOrWhiteSpace($value)) {
                    Write-Host "    [!!] 비어 있음 - 백엔드가 요약을 조립하지 못했다" -ForegroundColor Red
                } else {
                    Write-Host "    $value" -ForegroundColor Gray
                }
            }

            # 일련번호를 문장에 박는다. 제안 초안은 캐시하지 않기로 했으므로 같은 쌍을 두 번
            # 요청하면 번호가 반드시 증가해야 한다 (reason 과 기대가 반대다).
            $script:ProposalCallCount++
            $payload = [ordered]@{
                user_id                   = $body.user_id
                team_id                   = $body.team_id
                contest_id                = $body.contest_id
                sender_id                 = $body.sender_id
                receiver_id               = $body.receiver_id
                intent_id                 = $body.intent_id
                direction                 = $direction
                synergy_score             = $body.synergy_score
                # 명세상 항상 null 인 예약 필드. 백엔드가 이걸 읽지 않는지 확인하려고 실어 보낸다.
                portfolio_role_fit_score  = $null
                summary                   = "[stub#$($script:ProposalCallCount)] $($body.candidate_summary) 를 바탕으로 함께하고 싶습니다."
                message                   = "[stub#$($script:ProposalCallCount)] 안녕하세요. $($body.target_summary) 에 관심이 있어 연락드립니다."
            }

            Write-Host "  -> 200 (제안 조립 #$($script:ProposalCallCount), direction=$direction)" -ForegroundColor Green
            Write-Json -Response $response -Object $payload
            continue
        }

        # --- POST /intents/extract: 받은 messages 배열 덤프 ---
        $messages = @($body.messages)
        Write-Host "  받은 messages ($($messages.Count)개):" -ForegroundColor White
        foreach ($m in $messages) {
            Write-Host ("    id={0}  message={1}" -f $m.id, $m.message) -ForegroundColor Gray
        }

        # id 가 1..N 연속인지 자체 검증
        $expected = 1
        $idsOk = $true
        foreach ($m in $messages) {
            if ($m.id -ne $expected) { $idsOk = $false; break }
            $expected++
        }
        if ($idsOk) {
            Write-Host "  [OK] id 가 1 부터 연속 증가" -ForegroundColor Green
        } else {
            Write-Host "  [!!] id 가 1..N 연속이 아님 - 백엔드 재채번 로직 확인 필요" -ForegroundColor Red
        }

        # --- 응답 생성: messages 개수로 분기 ---
        if ($messages.Count -le 1) {
            # 재질문 단계 — 임베딩 없음
            $payload = [ordered]@{
                missing_fields = @("experience_level")
                extracted = [ordered]@{
                    desired_roles    = @("BE")
                    skills           = @("React", "TypeScript")
                    interests        = @()
                    activity_goal    = "포트폴리오용 프로젝트"
                    activity_style   = $null
                    experience_level = $null
                }
                embedding_text    = $null
                embedding_vector  = $null
                assistant_message = "포트폴리오용 프로젝트를 찾고 있구나! 혹시 경험 수준이 어느 정도인지 알려줄 수 있어? (입문/중급/고급)"
            }
            Write-Host "  -> 200 재질문 (missing_fields=['experience_level'])" -ForegroundColor Yellow
        } else {
            # 완료 단계 — 임베딩 포함
            $vector = New-StubVector -Dimension $EmbeddingDimension
            $payload = [ordered]@{
                missing_fields = @()
                extracted = [ordered]@{
                    desired_roles    = @("BE")
                    skills           = @("React", "TypeScript")
                    interests        = @("커머스")
                    activity_goal    = "포트폴리오용 프로젝트"
                    activity_style   = "주 2회 오프라인"
                    experience_level = "beginner"
                }
                embedding_text    = "백엔드 / React, TypeScript / 커머스 / 포트폴리오용 프로젝트 / 주 2회 오프라인 / beginner"
                embedding_vector  = $vector
                assistant_message = "너의 관심사는 백엔드구나! 너의 취향을 조금 알 것 같아. 이건 내가 추천해주는 팀 후보야."
            }
            Write-Host "  -> 200 완료 (missing_fields=[], 임베딩 $EmbeddingDimension 개)" -ForegroundColor Green
        }

        Write-Json -Response $response -Object $payload
    }
} finally {
    $listener.Stop()
    $listener.Close()
    Write-Host ""
    Write-Host "스텁 서버를 중지했습니다." -ForegroundColor DarkGray
}
