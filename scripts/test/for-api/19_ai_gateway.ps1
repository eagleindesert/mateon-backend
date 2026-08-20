# 19_ai_gateway.ps1 (for-api-server) - AI 게이트웨이 테스트  /api/ai/chat
#
# ============================================================================
#  [!] 과금 주의 - 이 스크립트는 실제 LLM 을 두 군데서 호출합니다.
#      (1) 도메인 분류: 백엔드가 OpenAI 를 직접 부른다 (발화 1건당 1회, 단 아래 지름길 제외)
#      (2) 의도 추출: MATCHING_INTENT 로 판정된 발화만 FastAPI 로 위임된다
#      예상 호출: 분류 최대 5회 + 의도 추출 2회
#
#      과금 없이 돌리려면 백엔드가 두 스텁을 모두 보게 하세요:
#        pwsh -File ..\debug\ai-stub\start-all.ps1
#        (FastAPI 스텁 8000 + 라우터 스텁 8001 을 띄우고 주소를 인자로 넘겨 재기동한다)
# ============================================================================
#
# 사용법: pwsh -File .\19_ai_gateway.ps1
#         pwsh -File .\19_ai_gateway.ps1 -StrictRouting   # 분류 실패를 FAIL 로 취급
#
# 사전 조건:
#   1) 로그인이 선행되어 .auth-token.txt 가 있어야 한다 (99_run_all 또는 auth\02_auth.ps1).
#   2) 원격 백엔드의 ai.base-url 이 살아있는 FastAPI 를 가리켜야 한다 (19.6 위임 구간).
#
# [주의사항]
#   (1) 분류 결과를 단정할 수 없다. 라우터는 실패하면 무조건 매칭으로 통과시키도록 설계돼
#       있어서, 키가 없거나 모델 설정이 틀려도 200 이 오고 domain 만 MATCHING_INTENT 가 된다.
#       그래서 19.4 에서 먼저 탐지하고, 분류가 안 되는 환경이면 도메인 값 단정은 건너뛴다
#       (구조 계약 — endpoint/matching/assistantMessage 의 관계 — 는 어느 분기든 검증한다).
#       -StrictRouting 을 주면 그 경우도 FAIL 로 잡는다. 99_run_all 기본값은 관대한 쪽이다.
#   (2) 대화 세션을 나눠 쓴다. 게이트웨이는 "이 대화 세션에 진행 중인 작업이 정확히 하나면 분류를
#       건너뛴다". 그래서 매칭 위임을 한 대화 세션에서 되묻기 분기를 검증하면 라우터를 아예
#       안 타고 통과해 버린다. A 는 게이트웨이 직답 전용, B 는 위임 전용이다.
#   (3) 대화 세션 삭제 API 가 없다. 이 스크립트는 실행할 때마다 대화 세션을 2개 남긴다.
#       사이드바 목록 검증(19.3)을 누적 개수가 아니라 증분으로 하는 이유다.
param(
    # 분류가 안 되는 환경(키 없음/모델 설정 오류)을 FAIL 로 잡는다.
    # 기본값이 관대한 이유는 99_run_all 이 키 없는 서버에서도 돌아야 하기 때문이다.
    [switch]$StrictRouting
)
. "$PSScriptRoot\00_common.ps1"

Write-Host "`n########## 19. AI Gateway (AI 채팅 입구) - /api/ai/chat [인증 필요] ##########" -ForegroundColor Magenta

try {

if (-not (Get-AccessToken)) {
    Write-Host "(!) accessToken 이 없습니다. 먼저 .\auth\02_auth.ps1 (또는 99_run_all) 을 실행하세요." -ForegroundColor Red
    return
}

$userId = Get-JwtSubject -Token (Get-AccessToken)
Write-Host "  (i) userId = $userId" -ForegroundColor DarkGray

# 한 턴의 응답이 프론트가 믿어도 되는 모양인지 검사한다. 도메인 값과 무관하게 항상 성립해야
# 하는 계약만 본다 — 분류가 폴백 중이어도 이건 깨지면 안 된다.
function Assert-TurnShape {
    param([string]$Prefix, $Turn, [long]$ExpectedSessionId)

    $d = $Turn.data
    Assert-Test -Title "$Prefix sessionId 를 그대로 돌려줌 (다음 턴에 재사용)" `
        -Condition ($d.sessionId -eq $ExpectedSessionId) `
        -Detail "보낸=$ExpectedSessionId, 받은=$($d.sessionId)"

    Assert-Test -Title "$Prefix assistantMessage 가 채워짐 (분기 따지기 전에 그대로 출력 가능)" `
        -Condition ([bool]$d.assistantMessage)

    $delegated = ($d.domain -eq "MATCHING_INTENT")
    Assert-Test -Title "$Prefix endpoint 와 domain 이 일치 (위임되는 판정만 경로가 있다)" `
        -Condition (($delegated -and [bool]$d.endpoint) -or (-not $delegated -and $null -eq $d.endpoint)) `
        -Detail "domain=$($d.domain), endpoint=$($d.endpoint)"

    Assert-Test -Title "$Prefix matching 은 위임된 턴에만 실려 온다" `
        -Condition (($delegated -and $null -ne $d.matching) -or (-not $delegated -and $null -eq $d.matching))
}

# 라우터 스텁이 답한 문구에는 [stub#N] 이 박혀 있다. 없으면 게이트웨이의 기본 문구가 나간 것
# (= LLM 이 문구를 안 줬거나 애초에 스텁까지 못 갔다).
function Test-FromStub {
    param($Turn)
    return [bool]($Turn.data.assistantMessage -match '\[stub#\d+\]')
}

# ── 19.0 초기화 ─────────────────────────────────────────────────────────────
# 이전 실행에서 남은 진행 중 매칭 작업을 버린다. 남아 있으면 19.6 이 새 작업을 여는 대신
# 그걸 이어받아 "완료까지 몇 턴 남았는지"가 실행마다 달라진다.
Invoke-Api -Method POST -Path "/api/matching/intents/session/restart" -Auth `
    -Title "19.0 매칭 작업 초기화 (restart)"

$before = @((Invoke-Api -Method GET -Path "/api/ai/chat/sessions" -Auth -PassThru -NoTrack `
    -Title "19.0b 기존 대화 세션 수 확인").data)
Write-Host "  (i) 기존 대화 세션 $($before.Count)건" -ForegroundColor DarkGray

# ── 19.1 새 대화 시작 ───────────────────────────────────────────────────────
$sa = Invoke-Api -Method POST -Path "/api/ai/chat/sessions" -Auth -PassThru `
    -Title "19.1 새 대화 시작 (대화 세션 A - 게이트웨이 직답 검증용)"

if (-not $sa.success) {
    Assert-Test -Title "19.1 대화 세션 생성 성공" -Condition $false -Detail $sa.message
    return
}

$sessionA = $sa.data.sessionId
Assert-Test -Title "19.1a sessionId 발급됨" -Condition ([bool]$sessionA) -Detail "sessionId=$sessionA"
Assert-Test -Title "19.1b title 이 null (첫 발화가 들어올 때 서버가 채운다)" `
    -Condition ($null -eq $sa.data.title)
Assert-Test -Title "19.1c lastMessage 가 null (아직 발화 없음)" `
    -Condition ($null -eq $sa.data.lastMessage)

# ── 19.2 빈 대화 세션 복원 ──────────────────────────────────────────────────
$empty = Invoke-Api -Method GET -Path "/api/ai/chat/sessions/$sessionA" -Auth -PassThru `
    -Title "19.2 빈 대화 세션 복원"
Assert-Test -Title "19.2a messages 가 0건" -Condition (@($empty.data.messages).Count -eq 0) `
    -Detail "messages=$(@($empty.data.messages).Count)"

# ── 19.3 사이드바 목록 ──────────────────────────────────────────────────────
# 누적 개수가 아니라 증분으로 본다 — 실행할 때마다 대화 세션이 쌓이고 지울 방법이 없다.
$list = Invoke-Api -Method GET -Path "/api/ai/chat/sessions" -Auth -PassThru `
    -Title "19.3 사이드바 목록"
$after = @($list.data)
Assert-Test -Title "19.3a 방금 만든 대화 세션이 목록에 있다" `
    -Condition ([bool]($after | Where-Object { $_.sessionId -eq $sessionA })) `
    -Detail "sessionId=$sessionA"
Assert-Test -Title "19.3b 목록이 1건 늘었다" -Condition ($after.Count -eq $before.Count + 1) `
    -Detail "$($before.Count) -> $($after.Count)"
Assert-Test -Title "19.3c 최근 순 정렬 (방금 만든 대화 세션이 맨 앞)" `
    -Condition ($after[0].sessionId -eq $sessionA) -Detail "맨 앞=$($after[0].sessionId)"

# ── 19.4 범위 밖 발화 (여기서 라우터 동작 여부를 판정한다) ──────────────────
$oos = Invoke-Api -Method POST -Path "/api/ai/chat/messages" -Auth -PassThru `
    -Title "19.4 범위 밖 발화 (OUT_OF_SCOPE 기대)" -Body @{
        sessionId = $sessionA
        message   = "오늘 서울 날씨 어때?"
    }

if (-not $oos.success) {
    Write-Host ""
    Write-Host "  (!) 발화 전송이 실패했습니다: $($oos.message)" -ForegroundColor Red
    Write-Host "      503 이면 백엔드가 AI 서버(FastAPI)에 닿지 못하는 상태입니다." -ForegroundColor Yellow
    Assert-Test -Title "19.4 발화 전송 성공" -Condition $false -Detail $oos.message
    return
}

Assert-TurnShape -Prefix "19.4" -Turn $oos -ExpectedSessionId $sessionA

# 라우터가 실제로 분류를 하고 있는가. 날씨 질문이 매칭으로 갔다면 분류가 안 되는 것이다.
$routerLive = ($oos.data.domain -ne "MATCHING_INTENT")

if (-not $routerLive) {
    Write-Host ""
    Write-Host "  (!) 날씨 질문이 MATCHING_INTENT 로 갔습니다 - 라우터가 분류를 못 하고 폴백 중입니다." -ForegroundColor Yellow
    Write-Host "      라우터는 어떤 실패든 매칭으로 통과시키므로 200 이 오고 에러도 안 납니다." -ForegroundColor DarkGray
    Write-Host "      확인 순서:" -ForegroundColor DarkGray
    Write-Host "        1. OPENAI_API_KEY 가 비었는가        -> 401 후 폴백" -ForegroundColor DarkGray
    Write-Host "        2. airouter.enabled=false 인가       -> 분류 자체를 건너뜀" -ForegroundColor DarkGray
    Write-Host "        3. 모델이 temperature 를 거부하는가  -> 400 후 폴백" -ForegroundColor DarkGray
    Write-Host "      백엔드 로그에서 'AI 라우터 호출 실패' 를 찾으면 1/3 이 구분됩니다." -ForegroundColor DarkGray
    Write-Host "      과금 없이 분류를 보려면: pwsh -File ..\debug\ai-stub\start-all.ps1" -ForegroundColor DarkGray
}

Assert-Test -Title "19.4a domain=OUT_OF_SCOPE" -Condition ($oos.data.domain -eq "OUT_OF_SCOPE") `
    -Detail "domain=$($oos.data.domain)"

if ($routerLive) {
    Assert-Test -Title "19.4b 스텁이 답한 문구다 ([stub#N] 표시)" -Condition (Test-FromStub $oos) `
        -Detail "표시가 없으면 게이트웨이 기본 문구이거나 실제 LLM 응답입니다"
} else {
    Write-Host "  (i) 19.4b 스텁 문구 확인 - 분류가 폴백 중이라 건너뜁니다." -ForegroundColor DarkGray
}

# ── 19.5 되묻기 발화 ────────────────────────────────────────────────────────
# 19.4 가 작업을 만들지 않았으므로(위임 없는 판정) 이 대화 세션은 여전히 라우터를 탄다.
$unclear = Invoke-Api -Method POST -Path "/api/ai/chat/messages" -Auth -PassThru `
    -Title "19.5 내용 없는 발화 (UNCLEAR 기대)" -Body @{
        sessionId = $sessionA
        message   = "안녕하세요"
    }

if ($unclear.success) {
    Assert-TurnShape -Prefix "19.5" -Turn $unclear -ExpectedSessionId $sessionA
    if ($routerLive) {
        Assert-Test -Title "19.5a domain=UNCLEAR" -Condition ($unclear.data.domain -eq "UNCLEAR") `
            -Detail "domain=$($unclear.data.domain)"
    } else {
        Write-Host "  (i) 19.5a 도메인 단정 - 분류가 폴백 중이라 건너뜁니다." -ForegroundColor DarkGray
    }
}

# ── 19.6 대화 세션 A 복원 (게이트웨이 턴은 domain 이 null) ──────────────────
$restoredA = Invoke-Api -Method GET -Path "/api/ai/chat/sessions/$sessionA" -Auth -PassThru `
    -Title "19.6 대화 세션 A 복원"
$msgsA = @($restoredA.data.messages)

Assert-Test -Title "19.6a 대화 4건 (USER/ASSISTANT 두 턴)" -Condition ($msgsA.Count -eq 4) `
    -Detail "messages=$($msgsA.Count)"
if ($msgsA.Count -ge 2) {
    Assert-Test -Title "19.6b 첫 줄이 USER, 둘째 줄이 ASSISTANT (시간순)" `
        -Condition ($msgsA[0].role -eq "USER" -and $msgsA[1].role -eq "ASSISTANT")
    Assert-Test -Title "19.6c 사용자 발화가 그대로 저장됨" `
        -Condition ($msgsA[0].message -eq "오늘 서울 날씨 어때?") -Detail $msgsA[0].message
}
if ($routerLive) {
    # 게이트웨이가 혼자 답한 턴은 작업이 없다 = domain 이 null 이다.
    # 이게 도메인 AI 로 새어 나가지 않는다는 것의 관찰 가능한 증거다.
    $allNull = ($msgsA | Where-Object { $null -ne $_.domain }).Count -eq 0
    Assert-Test -Title "19.6d 게이트웨이가 답한 턴은 domain 이 전부 null" -Condition $allNull `
        -Detail "domain 이 붙은 줄 = $(($msgsA | Where-Object { $null -ne $_.domain }).Count)건"
} else {
    Write-Host "  (i) 19.6d domain=null 검증 - 분류가 폴백 중이라 건너뜁니다." -ForegroundColor DarkGray
}

# ── 19.7 제목 자동 설정 ─────────────────────────────────────────────────────
$list2 = Invoke-Api -Method GET -Path "/api/ai/chat/sessions" -Auth -PassThru `
    -Title "19.7 사이드바 재조회 (제목/미리보기 채워짐 기대)"
$mine = @($list2.data) | Where-Object { $_.sessionId -eq $sessionA } | Select-Object -First 1
Assert-Test -Title "19.7a title 이 첫 발화로 채워짐" `
    -Condition ($mine.title -eq "오늘 서울 날씨 어때?") -Detail "title=$($mine.title)"
Assert-Test -Title "19.7b lastMessage 가 마지막 한 줄" -Condition ([bool]$mine.lastMessage) `
    -Detail "lastMessage=$($mine.lastMessage)"

# ── 19.8 매칭 위임 (대화 세션 B) ────────────────────────────────────────────
# 새 대화 세션을 쓰는 이유는 상단 주석 (2) 참고 — 위임이 시작되면 그 대화 세션은 라우터를 건너뛴다.
$sb = Invoke-Api -Method POST -Path "/api/ai/chat/sessions" -Auth -PassThru `
    -Title "19.8 새 대화 시작 (대화 세션 B - 매칭 위임 검증용)"
$sessionB = $sb.data.sessionId

$match = Invoke-Api -Method POST -Path "/api/ai/chat/messages" -Auth -PassThru `
    -Title "19.8a 매칭 발화 (MATCHING_INTENT 기대, FastAPI 로 위임됨)" -Body @{
        sessionId = $sessionB
        message   = "백엔드 개발자인데 같이 공모전 나갈 팀 찾고 있어요"
    }

if (-not $match.success) {
    Write-Host "  (!) 위임 호출이 실패했습니다: $($match.message)" -ForegroundColor Red
    Write-Host "      502/503 이면 백엔드가 FastAPI 에 닿지 못하는 상태입니다 (라우터가 아니라 도메인 AI)." -ForegroundColor Yellow
    Assert-Test -Title "19.8a 매칭 위임 성공" -Condition $false -Detail $match.message
} else {
    Assert-TurnShape -Prefix "19.8" -Turn $match -ExpectedSessionId $sessionB
    Assert-Test -Title "19.8b domain=MATCHING_INTENT" -Condition ($match.data.domain -eq "MATCHING_INTENT") `
        -Detail "domain=$($match.data.domain)"
    Assert-Test -Title "19.8c endpoint 가 매칭 경로 (참고값이지 계약은 아니다)" `
        -Condition ($match.data.endpoint -eq "/api/matching/intents/messages") `
        -Detail "endpoint=$($match.data.endpoint)"
    Assert-Test -Title "19.8d matching 이 통째로 실려 옴 (추가 호출 불필요)" `
        -Condition ([bool]$match.data.matching.sessionId) `
        -Detail "matching.sessionId=$($match.data.matching.sessionId)"
    Assert-Test -Title "19.8e assistantMessage 가 도메인 AI 의 답변과 같다" `
        -Condition ($match.data.assistantMessage -eq $match.data.matching.assistantMessage)
    Assert-Test -Title "19.8f embeddingVector 가 응답에 없다 (1536개 float 를 프론트로 안 보낸다)" `
        -Condition ($null -eq $match.data.matching.embeddingVector)
}

# ── 19.9 진행 중 작업이 하나면 분류를 건너뛴다 ──────────────────────────────
# 19.8 이 이 대화 세션에 매칭 작업을 열었으므로, 이제 무슨 말을 해도 라우터를 안 타고
# 그리로 간다. 사용자가 그 AI 의 질문에 답하는 중이라는 판단이다.
# 일부러 범위 밖 발화를 보내 — 라우터를 탔다면 OUT_OF_SCOPE 가 나올 문장이다.
if ($match.success) {
    $shortcut = Invoke-Api -Method POST -Path "/api/ai/chat/messages" -Auth -PassThru `
        -Title "19.9 위임 중 대화 세션에 범위 밖 발화 (그래도 매칭으로 가야 정상)" -Body @{
            sessionId = $sessionB
            message   = "그런데 오늘 서울 날씨 어때?"
        }

    if ($shortcut.success) {
        if ($routerLive) {
            Assert-Test -Title "19.9a 분류를 건너뛰고 매칭으로 이어짐" `
                -Condition ($shortcut.data.domain -eq "MATCHING_INTENT") `
                -Detail "domain=$($shortcut.data.domain) (라우터를 탔다면 OUT_OF_SCOPE 였을 문장)"
        } else {
            Write-Host "  (i) 19.9a 지름길 검증 - 분류가 폴백 중이면 어차피 매칭이라 의미가 없어 건너뜁니다." -ForegroundColor DarkGray
        }
        Assert-Test -Title "19.9b 같은 대화 세션에 이어짐" `
            -Condition ($shortcut.data.sessionId -eq $sessionB)
    }
}

# ── 19.10 검증 (잘못된 요청) ────────────────────────────────────────────────
Invoke-Api -Method POST -Path "/api/ai/chat/messages" -Auth `
    -Title "19.10 sessionId 누락 (@NotNull → 차단 기대)" -Body @{ message = "안녕" }

Invoke-Api -Method POST -Path "/api/ai/chat/messages" -Auth `
    -Title "19.10b 빈 메시지 (@NotBlank → 차단 기대)" -Body @{ sessionId = $sessionA; message = "" }

Invoke-Api -Method POST -Path "/api/ai/chat/messages" -Auth `
    -Title "19.10c 없는 대화 세션 (AI_CHAT_SESSION_NOT_FOUND → 차단 기대)" -Body @{
        sessionId = 99999999
        message   = "남의 대화 세션에 쓰기"
    }

Invoke-Api -Method GET -Path "/api/ai/chat/sessions/99999999" -Auth `
    -Title "19.10d 없는 대화 세션 복원 (차단 기대)"

# ── 19.11 인증 없이 호출 ────────────────────────────────────────────────────
Invoke-Api -Method GET -Path "/api/ai/chat/sessions" `
    -Title "19.11 인증 없이 목록 조회 (차단 기대)"

# ── 뒷정리 ──────────────────────────────────────────────────────────────────
# 19.8 이 연 매칭 작업을 닫는다. 대화 세션은 지울 API 가 없어 그대로 남는다.
Invoke-Api -Method POST -Path "/api/matching/intents/session/restart" -Auth `
    -Title "19.12 뒷정리 (매칭 작업 restart)"

Write-Host ""
if (-not $routerLive) {
    $msg = "  (!) 이번 실행에서 도메인 분류는 동작하지 않았습니다 (전부 매칭으로 폴백)."
    if ($StrictRouting) {
        Assert-Test -Title "19.x 라우터가 분류를 수행함 (-StrictRouting)" -Condition $false `
            -Detail "날씨 질문이 MATCHING_INTENT 로 갔습니다"
    } else {
        Write-Host $msg -ForegroundColor Yellow
        Write-Host "      도메인 값 단정은 건너뛰었고 구조 계약만 검증했습니다." -ForegroundColor DarkGray
        Write-Host "      실패로 잡으려면: pwsh -File .\19_ai_gateway.ps1 -StrictRouting" -ForegroundColor DarkGray
    }
}
Write-Host "  (i) 만든 대화 세션: A=$sessionA, B=$sessionB (삭제 API 가 없어 남습니다)" -ForegroundColor DarkGray

Write-Host "`n########## 19. AI Gateway 테스트 완료 ##########" -ForegroundColor Magenta

} finally {
    Write-TestSummary | Out-Null
}
