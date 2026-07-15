# stub-ai-server.ps1
# 별도 FastAPI AI 서버(POST /intents/extract)의 스텁.
#
# 실제 FastAPI 를 띄울 수 없는 상황에서 백엔드 연동을 검증하기 위한 도구다.
# 실제 서버가 준비되면 이 스텁 대신 AI_BASE_URL 만 실제 주소로 바꾸면 된다.
#
# 진짜 목적: 백엔드가 보내는 요청을 콘솔에 덤프해서
#   - messages 의 id 가 1 부터 연속 증가하는지
#   - USER 발화만 들어있는지 (assistant_message 가 섞이지 않았는지)
#   - 호출할 때마다 누적되는지
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
Write-Host " AI 서버 스텁 (POST /intents/extract)" -ForegroundColor Magenta
Write-Host ("=" * 70) -ForegroundColor DarkGray
Write-Host "  리스닝: http://localhost:$Port/" -ForegroundColor Green
Write-Host "  임베딩 차원: $EmbeddingDimension" -ForegroundColor DarkGray
if ($ExpectedSecret) {
    Write-Host "  X-Internal-Secret: 검증함 (불일치/누락 시 401) - 기대값 $(Format-Secret $ExpectedSecret)" -ForegroundColor DarkGray
} else {
    Write-Host "  X-Internal-Secret: 출력만 함 (검증하려면 -ExpectedSecret 지정)" -ForegroundColor DarkGray
}
Write-Host ""
Write-Host "  동작: messages 개수로 분기" -ForegroundColor DarkGray
Write-Host "    1개      -> missing_fields=['experience_level'], 임베딩 null (재질문)" -ForegroundColor DarkGray
Write-Host "    2개 이상 -> missing_fields=[], 임베딩 $EmbeddingDimension 개 (완료)" -ForegroundColor DarkGray
Write-Host ""
Write-Host "  중지: Ctrl+C" -ForegroundColor Yellow
Write-Host ""

$rand = New-Object System.Random

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

        if ($request.HttpMethod -ne "POST" -or $path -ne "/intents/extract") {
            Write-Host "  -> 404 (이 스텁은 POST /intents/extract 만 처리)" -ForegroundColor Yellow
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

        # --- 이 스텁의 핵심: 받은 messages 배열 덤프 ---
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
            $vector = New-Object 'double[]' $EmbeddingDimension
            for ($i = 0; $i -lt $EmbeddingDimension; $i++) {
                $vector[$i] = [Math]::Round(($rand.NextDouble() * 2 - 1), 6)
            }
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
