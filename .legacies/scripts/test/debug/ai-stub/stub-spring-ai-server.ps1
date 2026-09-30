# stub-spring-ai-server.ps1
# AI 게이트웨이의 라우터(AiRouterClient)가 부르는 OpenAI Chat Completions API 의 스텁.
#   POST /v1/chat/completions   (도메인 분류)
#   POST /chat/completions      (base-url 에 /v1 을 안 붙였을 때도 받아준다)
#
# stub-ai-server.ps1 과 왜 별개인가 —
# 저쪽은 우리가 정한 FastAPI 규약(POST /intents/extract, X-Internal-Secret, snake_case 본문)을
# 말하고 이쪽은 OpenAI 와이어 포맷(choices[].message.content, Bearer 인증)을 말한다. 한 프로세스에
# 두 규약을 섞으면 요청 덤프가 뒤엉켜서 "백엔드가 뭘 보내는지 눈으로 본다"는 목적 자체가 무너진다.
# 포트도 달라야 백엔드의 두 프로퍼티(ai.base-url / spring.ai.openai.base-url)를 각각 겨눌 수 있다.
#
# 진짜 목적: 키 없이도 라우터의 세 분기를 전부 재현하는 것.
# OPENAI_API_KEY 가 비어 있으면 Spring AI 는 no-auth 모드로 붙어 401 을 받고 매번 폴백하므로,
# 실제로는 모든 발화가 MATCHING_INTENT 로만 간다. UNCLEAR/OUT_OF_SCOPE 는 한 번도 안 타진다.
#
# 그리고 눈으로 확인하는 것:
#   - 시스템 프롬프트에 RoutableDomain 카탈로그 3개가 전부 실려 오는지
#     (도메인만 늘리고 프롬프트를 잊는 사고를 막는 장치가 실제로 도는지)
#   - BeanOutputConverter 가 만든 JSON 스키마가 사용자 메시지에 붙어 오는지
#   - temperature 가 0 인지 (application.yml 이 분류의 결정성을 위해 박아 둔 값)
#   - Authorization 헤더가 실제로 제거되는지 (no-auth 모드의 관찰 가능한 증거)
#
# 사용법 (pwsh 7 이상):
#   pwsh -File stub-spring-ai-server.ps1                              # 기본 포트 8001
#   pwsh -File stub-spring-ai-server.ps1 -Port 8002
#   pwsh -File stub-spring-ai-server.ps1 -ForceDomain OUT_OF_SCOPE    # 발화 무시하고 고정
#   pwsh -File stub-spring-ai-server.ps1 -FailureMode broken-json     # 폴백 규약 확인
#
# 백엔드는 spring.ai.openai.base-url 이 이 주소를 가리켜야 한다.
# start-all.ps1 이 커맨드라인 인자로 넘겨주므로 보통 직접 띄울 일은 없다.

[CmdletBinding()]
param(
    # stub-ai-server.ps1 이 8000 을 쓰므로 겹치지 않게 8001.
    [int]$Port = 8001,

    # 분류 결과 고정. 주면 발화 내용과 무관하게 이 도메인으로 답한다.
    # e2e 에서 "이 분기를 반드시 태우고 싶다" 할 때 쓴다.
    [ValidateSet("", "MATCHING_INTENT", "UNCLEAR", "OUT_OF_SCOPE")]
    [string]$ForceDomain = "",

    # 고장 주입. 라우터의 핵심 규약은 "어떤 실패든 매칭으로 통과시킨다" 인데,
    # 그게 진짜인지는 실패를 만들어 봐야 안다. 값은 AiRouterClientTest 의 폴백 케이스와 같다.
    #   broken-json     : JSON 이 아닌 답
    #   unknown-domain  : enum 밖의 도메인
    #   empty-domain    : domain 필드 누락
    #   http-500        : 서버 오류 (SDK 가 재시도하는 부류)
    #   temperature-400 : gpt-5 계열이 temperature 를 거부하는 상황 재현 (아래 주석 참고)
    [ValidateSet("none", "broken-json", "unknown-domain", "empty-domain", "http-500", "temperature-400")]
    [string]$FailureMode = "none"
)

$ErrorActionPreference = "Stop"

# HttpListener 기반 스텁이 pwsh 7 을 요구한다 (stub-ai-server.ps1 과 같은 제약).
if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw "pwsh 7 이상에서 실행하세요. (현재: $($PSVersionTable.PSVersion))"
}

$listener = New-Object System.Net.HttpListener
$listener.Prefixes.Add("http://localhost:$Port/")

try {
    $listener.Start()
} catch {
    Write-Host "포트 $Port 리스닝 실패: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "이미 다른 프로세스가 쓰고 있거나 권한이 없습니다." -ForegroundColor Yellow
    exit 1
}

# 분류 호출 일련번호. 응답 문구에 [stub#N] 으로 박아 넣는다.
# 프론트/e2e 가 받은 assistantMessage 에 이 표시가 없으면 스텁까지 오지 못하고
# AiGatewayService 의 DEFAULT_* 상수가 대신 나간 것이다 — 둘을 밖에서 구분하는 유일한 방법이다.
$script:CallCount = 0

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

# OpenAI ChatCompletion 응답 껍데기. SDK(openai-java)가 필수로 읽는 필드가 다 있어야 한다 —
# 하나라도 빠지면 역직렬화에서 터지고, 그건 우리 코드 입장에선 그냥 "라우터 실패" 로 보여서
# 스텁이 잘못된 건지 백엔드가 잘못된 건지 구분이 안 된다.
function New-ChatCompletion {
    param([string]$Model, [string]$Content)
    return [ordered]@{
        id      = "chatcmpl-stub-" + [guid]::NewGuid().ToString('N').Substring(0, 12)
        object  = "chat.completion"
        created = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
        model   = $Model
        choices = @(
            [ordered]@{
                index         = 0
                message       = [ordered]@{
                    role    = "assistant"
                    content = $Content
                    refusal = $null
                }
                logprobs      = $null
                finish_reason = "stop"
            }
        )
        usage = [ordered]@{
            prompt_tokens     = 0
            completion_tokens = 0
            total_tokens      = 0
        }
    }
}

# 사용자 메시지에서 실제 발화만 떼어낸다.
# ChatClient.entity() 를 쓰면 BeanOutputConverter 가 JSON 스키마 지시문을 사용자 메시지 뒤에
# 통째로 붙이므로, 그대로 두면 덤프가 스키마로 도배되고 키워드 분류도 스키마 글자에 걸린다.
function Get-Utterance {
    param([string]$Content)
    if (-not $Content) { return "" }
    # 세 번째 마커는 코드펜스. PowerShell 에서 백틱은 이스케이프 문자라 작은따옴표로 써야 한다.
    $markers = @("Your response should be in JSON", "Here is the JSON Schema", '```')
    $cut = $Content.Length
    foreach ($m in $markers) {
        $i = $Content.IndexOf($m)
        if ($i -ge 0 -and $i -lt $cut) { $cut = $i }
    }
    return $Content.Substring(0, $cut).Trim()
}

# 발화 → 도메인. LLM 이 아니라 키워드 표라서 같은 문장은 항상 같은 답이 나온다.
# e2e 가 분기를 assert 하려면 결정적이어야 한다 (application.yml 이 temperature 0 을 준 것과 같은 이유).
$OutOfScopeWords = @("날씨", "주식", "로또", "뉴스", "맛집", "요리", "레시피", "영화", "환율", "연예인", "문법")
$UnclearWords    = @("안녕", "하이", "헬로", "ㅎㅇ", "도와줘", "도와주세요", "뭐해", "뭐하는")

function Resolve-Domain {
    param([string]$Utterance)
    foreach ($w in $OutOfScopeWords) {
        if ($Utterance -like "*$w*") { return @{ Domain = "OUT_OF_SCOPE"; Rule = "OUT_OF_SCOPE 키워드 '$w'" } }
    }
    foreach ($w in $UnclearWords) {
        if ($Utterance -like "*$w*") { return @{ Domain = "UNCLEAR"; Rule = "UNCLEAR 키워드 '$w'" } }
    }
    return @{ Domain = "MATCHING_INTENT"; Rule = "해당 키워드 없음 -> 기본값" }
}

Write-Host ("=" * 78) -ForegroundColor DarkGray
Write-Host " Spring AI 라우터 스텁 (OpenAI Chat Completions 호환)" -ForegroundColor Magenta
Write-Host ("=" * 78) -ForegroundColor DarkGray
Write-Host "  리스닝: http://localhost:$Port/  (POST /v1/chat/completions | /chat/completions)" -ForegroundColor Green
Write-Host ""
if ($ForceDomain) {
    Write-Host "  분류: 고정 -> $ForceDomain (발화 내용 무시)" -ForegroundColor Yellow
} else {
    Write-Host "  분류: 키워드 표 (결정적)" -ForegroundColor DarkGray
    Write-Host "    OUT_OF_SCOPE    <- $($OutOfScopeWords -join ', ')" -ForegroundColor DarkGray
    Write-Host "    UNCLEAR         <- $($UnclearWords -join ', ')" -ForegroundColor DarkGray
    Write-Host "    MATCHING_INTENT <- 그 외 전부" -ForegroundColor DarkGray
}
if ($FailureMode -ne "none") {
    Write-Host "  고장 주입: $FailureMode -> 백엔드는 매칭으로 통과시켜야 정상이다" -ForegroundColor Yellow
}
Write-Host ""
Write-Host "  요청마다 확인하는 것:" -ForegroundColor DarkGray
Write-Host "    - 시스템 프롬프트에 도메인 카탈로그 3개가 다 실렸는지" -ForegroundColor DarkGray
Write-Host "    - 사용자 메시지에 BeanOutputConverter 스키마가 붙었는지" -ForegroundColor DarkGray
Write-Host "    - temperature 가 0 인지" -ForegroundColor DarkGray
Write-Host "    - Authorization 헤더가 제거됐는지 (no-auth 모드의 증거)" -ForegroundColor DarkGray
Write-Host ""
Write-Host "  응답 문구에는 [stub#N] 이 박힌다. 프론트가 받은 문구에 그게 없으면" -ForegroundColor DarkGray
Write-Host "  스텁까지 오지 못하고 게이트웨이의 기본 문구가 대신 나간 것이다." -ForegroundColor DarkGray
Write-Host ""
Write-Host "  중지: Ctrl+C" -ForegroundColor Yellow
Write-Host ""

try {
    while ($listener.IsListening) {
        $context  = $listener.GetContext()
        $request  = $context.Request
        $response = $context.Response

        $path = $request.Url.AbsolutePath
        Write-Host ("-" * 78) -ForegroundColor DarkGray
        Write-Host ("[{0}] {1} {2}" -f (Get-Date -Format "HH:mm:ss"), $request.HttpMethod, $path) -ForegroundColor Cyan

        $knownPaths = @("/v1/chat/completions", "/chat/completions")
        if ($request.HttpMethod -ne "POST" -or $knownPaths -notcontains $path) {
            Write-Host "  -> 404 (이 스텁은 POST $($knownPaths -join ' | ') 만 처리)" -ForegroundColor Yellow
            Write-Json -Response $response -Object @{ error = @{ message = "Unknown path"; type = "invalid_request_error" } } -StatusCode 404
            continue
        }

        # --- 인증 헤더 확인 ---
        # api-key 가 빈 문자열이면 Spring AI 는 no-auth 모드로 붙어 Authorization 을 떼고 보낸다.
        # 헤더가 실려 오면 실제 키가 설정돼 있다는 뜻 — 스텁을 보는 상황에선 그럴 이유가 없다.
        $auth = $request.Headers["Authorization"]
        if ($auth) {
            Write-Host "  [!!] Authorization 헤더가 실려 왔다 - 실제 키가 설정된 채로 스텁을 보고 있다" -ForegroundColor Yellow
        } else {
            Write-Host "  [OK] Authorization 없음 (no-auth 모드)" -ForegroundColor Green
        }

        # --- 본문 파싱 ---
        $reader = New-Object System.IO.StreamReader($request.InputStream, [System.Text.Encoding]::UTF8)
        $bodyText = $reader.ReadToEnd()
        $reader.Close()

        try {
            $body = $bodyText | ConvertFrom-Json
        } catch {
            Write-Host "  -> 400 (JSON 파싱 실패): $bodyText" -ForegroundColor Red
            Write-Json -Response $response -Object @{ error = @{ message = "Invalid JSON"; type = "invalid_request_error" } } -StatusCode 400
            continue
        }

        $model = if ($body.model) { $body.model } else { "unknown" }
        Write-Host ("  model={0}  temperature={1}" -f $model, $body.temperature) -ForegroundColor Gray

        # application.yml 이 분류의 결정성을 위해 temperature 0 을 박아 뒀다. 실려 오는지 확인한다.
        if ($null -eq $body.temperature) {
            Write-Host "  [!!] temperature 가 안 실렸다 - 분류가 날마다 갈릴 수 있다" -ForegroundColor Yellow
        } elseif ([double]$body.temperature -ne 0) {
            Write-Host "  [!!] temperature 가 0 이 아니다 ($($body.temperature))" -ForegroundColor Yellow
        } else {
            Write-Host "  [OK] temperature=0" -ForegroundColor Green
        }

        # gpt-5 계열은 Chat Completions 에서 temperature 기본값(1) 외의 값을 거부한다.
        # 실제 응답으로 확인된 사실이다:
        #   400 Unsupported value: 'temperature' does not support 0 with this model.
        # 스텁은 아무 값이나 받아주므로 이 조합이 여기서는 안 터진다. 실제 키를 넣는 순간에만
        # 400 으로 터지고 라우터는 조용히 폴백만 하므로, 스텁을 보다가 실서버로 옮겼을 때
        # 갑자기 분류가 죽는 걸 막으려면 여기서 미리 알려 줘야 한다.
        if ($model -like "gpt-5*" -and $null -ne $body.temperature -and [double]$body.temperature -ne 1) {
            Write-Host "  [!!] $model 은 temperature=$($body.temperature) 를 거부한다 - 실서버에선 400 이다" -ForegroundColor Red
            Write-Host "       application.yml 에서 temperature 를 빼야 한다. 재현: -FailureMode temperature-400" -ForegroundColor DarkGray
        }

        # --- 메시지 덤프 + 프롬프트 조립 검증 ---
        $messages   = @($body.messages)
        $systemText = ($messages | Where-Object { $_.role -eq "system" } | ForEach-Object { $_.content }) -join "`n"
        $userRaw    = ($messages | Where-Object { $_.role -eq "user" } | Select-Object -Last 1).content
        $utterance  = Get-Utterance -Content $userRaw

        Write-Host "  받은 messages ($($messages.Count)개): $(($messages | ForEach-Object { $_.role }) -join ', ')" -ForegroundColor White
        Write-Host ("    발화 = {0}" -f $utterance) -ForegroundColor Gray

        # RoutableDomain 카탈로그가 프롬프트에 실리는지 — 도메인만 늘리고 프롬프트를 잊는 사고를
        # 막는 장치가 실제로 도는지 확인한다. (AiRouterClientTest 가 목으로 보는 것과 같은 검증)
        $catalog = @("MATCHING_INTENT", "UNCLEAR", "OUT_OF_SCOPE")
        $missing = $catalog | Where-Object { $systemText -notlike "*$_*" }
        if ($missing) {
            Write-Host "  [!!] 시스템 프롬프트에 없는 도메인: $($missing -join ', ')" -ForegroundColor Red
        } else {
            Write-Host "  [OK] 도메인 카탈로그 $($catalog.Count)개가 시스템 프롬프트에 실림" -ForegroundColor Green
        }

        if ($userRaw -like "*assistantMessage*" -and $userRaw -like "*domain*") {
            Write-Host "  [OK] BeanOutputConverter 스키마가 사용자 메시지에 붙음" -ForegroundColor Green
        } else {
            Write-Host "  [!!] 응답 스키마가 안 붙었다 - .entity() 경로를 안 타는 것 같다" -ForegroundColor Red
        }

        # --- 고장 주입 (HTTP 레벨) ---
        if ($FailureMode -eq "http-500") {
            Write-Host "  -> 500 (고장 주입) - 백엔드는 매칭으로 통과시켜야 정상" -ForegroundColor Yellow
            Write-Json -Response $response -Object @{ error = @{ message = "stub injected failure"; type = "server_error" } } -StatusCode 500
            continue
        }
        # 실서버와 같게 조건부로 거절한다 — temperature 를 안 보내면 통과해야 한다.
        # 그래야 "그 줄을 지우면 해결되는가" 를 스텁만으로 확인할 수 있다.
        if ($FailureMode -eq "temperature-400" -and $null -ne $body.temperature -and [double]$body.temperature -ne 1) {
            Write-Host "  -> 400 (고장 주입: gpt-5 temperature 거부 재현)" -ForegroundColor Yellow
            $detail = "Unsupported value: 'temperature' does not support $($body.temperature) with this model. Only the default (1) is supported."
            Write-Json -Response $response -Object @{
                error = @{ message = $detail; type = "invalid_request_error"; param = "temperature"; code = "unsupported_value" }
            } -StatusCode 400
            continue
        }

        $script:CallCount++

        # --- 고장 주입 (본문 레벨) ---
        $brokenContent = $null
        switch ($FailureMode) {
            "broken-json"    { $brokenContent = "죄송하지만 JSON 으로 답할 수 없습니다" }
            "unknown-domain" { $brokenContent = '{"domain":"WEATHER_FORECAST","assistantMessage":"맑아요"}' }
            "empty-domain"   { $brokenContent = '{"assistantMessage":"문구만 있음"}' }
        }
        if ($null -ne $brokenContent) {
            Write-Host "  -> 200 (고장 주입: $FailureMode) - 백엔드는 매칭으로 통과시켜야 정상" -ForegroundColor Yellow
            Write-Host "     content=$brokenContent" -ForegroundColor DarkGray
            Write-Json -Response $response -Object (New-ChatCompletion -Model $model -Content $brokenContent)
            continue
        }

        # --- 정상 분류 ---
        if ($ForceDomain) {
            $domain = $ForceDomain
            $rule   = "-ForceDomain 으로 고정"
        } else {
            $resolved = Resolve-Domain -Utterance $utterance
            $domain   = $resolved.Domain
            $rule     = $resolved.Rule
        }
        Write-Host ("  판정: {0}  ({1})" -f $domain, $rule) -ForegroundColor White

        # assistantMessage 규약: 위임되는 판정은 비워 둔다(답변은 도메인 AI 가 만든다).
        # 나머지는 문구를 채우되 [stub#N] 을 박아 게이트웨이 기본 문구와 구분되게 한다.
        switch ($domain) {
            "MATCHING_INTENT" { $assistant = "" }
            "UNCLEAR"         { $assistant = "어떤 걸 도와드릴까요? 찾고 계신 팀이나 활동을 알려주세요. [stub#$($script:CallCount)]" }
            "OUT_OF_SCOPE"    { $assistant = "그 주제는 도와드리기 어려워요. 저는 함께할 팀이나 팀원을 찾는 걸 도와드릴 수 있어요. [stub#$($script:CallCount)]" }
        }

        $decision = [ordered]@{ domain = $domain; assistantMessage = $assistant }
        $content  = $decision | ConvertTo-Json -Depth 5 -Compress

        Write-Host ("  -> 200 (분류 #{0}) content={1}" -f $script:CallCount, $content) -ForegroundColor Green
        Write-Json -Response $response -Object (New-ChatCompletion -Model $model -Content $content)
    }
} finally {
    $listener.Stop()
    $listener.Close()
    Write-Host ""
    Write-Host "라우터 스텁을 중지했습니다." -ForegroundColor DarkGray
}
