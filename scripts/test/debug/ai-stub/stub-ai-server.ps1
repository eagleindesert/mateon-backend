# stub-ai-server.ps1
# 별도 FastAPI AI 서버의 스텁. 처리하는 엔드포인트:
#   POST /intents/extract                  (매칭 의도 추출)
#   POST /internal/teams/embedding:refresh (팀 임베딩 계산)
#   POST /recommendations/user-to-team     (유저→팀 추천 점수 계산)
#   POST /recommendations/team-to-user     (팀→유저 역제안 추천 점수 계산)
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
Write-Host " AI 서버 스텁 (intents/extract, teams/embedding:refresh, recommendations 양방향+reason)" -ForegroundColor Magenta
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
Write-Host "  동작 (/recommendations/user-to-team): 역할 일치 여부로 점수 분기" -ForegroundColor DarkGray
Write-Host "    desired_roles 와 recruiting_roles 가 겹치면 0.9x, 아니면 0.1x + label 생성" -ForegroundColor DarkGray
Write-Host "    (일부러 점수 오름차순으로 돌려준다 — 백엔드가 내림차순 정렬하는지 확인용)" -ForegroundColor DarkGray
Write-Host "  동작 (/recommendations/team-to-user): 위와 같되 질의/후보가 뒤집힘 (역제안)" -ForegroundColor DarkGray
Write-Host "    query=팀(recruiting_roles), candidates=유저(desired_roles) - 겹치면 0.9x" -ForegroundColor DarkGray
Write-Host "  동작 (/recommendations/reason): 받은 세 요약을 그대로 찍고 [stub#N] 문장 반환" -ForegroundColor DarkGray
Write-Host "    N 은 호출 일련번호 — 같은 쌍을 두 번 물었는데 N 이 같으면 백엔드 캐시가 동작한 것" -ForegroundColor DarkGray
Write-Host "    candidate_summary/target_summary 가 비면 [!!] 로 표시된다 (백엔드 조립 실패)" -ForegroundColor DarkGray
Write-Host ""
Write-Host "  중지: Ctrl+C" -ForegroundColor Yellow
Write-Host ""

$rand = New-Object System.Random

# /recommendations/reason 호출 일련번호. 생성된 문장에 박아 넣어 백엔드의 이유 캐시가
# 동작하는지 밖에서 확인할 수 있게 한다 (같은 쌍을 두 번 물었는데 번호가 같으면 캐시 hit).
$script:ReasonCallCount = 0

# 난수 임베딩 벡터 생성 (두 엔드포인트가 공유)
function New-StubVector {
    param([int]$Dimension)
    $vector = New-Object 'double[]' $Dimension
    for ($i = 0; $i -lt $Dimension; $i++) {
        $vector[$i] = [Math]::Round(($rand.NextDouble() * 2 - 1), 6)
    }
    return ,$vector   # 콤마: 배열이 파이프라인에서 풀리지 않게
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

        $knownPaths = @("/intents/extract", "/internal/teams/embedding:refresh",
                        "/recommendations/user-to-team", "/recommendations/team-to-user",
                        "/recommendations/reason")
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

                Write-Host ("    candidate_id={0}  vector={1}  recruiting_roles=[{2}]  required_skills=[{3}]  activity_style={4}  beginner_friendly={5}" -f `
                    $c.candidate_id, $dimOk, ($roles -join ", "), (@($meta.required_skills) -join ", "), $meta.activity_style, $meta.beginner_friendly) -ForegroundColor Gray

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
                    candidate_id = $c.candidate_id
                    score        = $score
                    label        = $label
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

                Write-Host ("    candidate_id={0}  vector={1}  desired_roles=[{2}]  skills=[{3}]  experience_level={4}  activity_style={5}" -f `
                    $c.candidate_id, $dimOk, ($roles -join ", "), (@($meta.skills) -join ", "), $meta.experience_level, $meta.activity_style) -ForegroundColor Gray

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
                    candidate_id = $c.candidate_id
                    score        = $score
                    label        = $label
                }
                $i++
            }

            # user-to-team 과 마찬가지로 일부러 점수 오름차순으로 돌려준다.
            $recommendations = @($recommendations | Sort-Object { $_.score })

            Write-Host "  -> 200 (recommendations $($recommendations.Count)건, 점수 오름차순으로 반환)" -ForegroundColor Green
            Write-Json -Response $response -Object ([ordered]@{ recommendations = $recommendations })
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
