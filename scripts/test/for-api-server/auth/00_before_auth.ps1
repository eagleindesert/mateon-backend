# 00_before_auth.ps1 (for-api-server) - 회원가입 전(前) DB 정리 SQL 생성기  [DB 직접 실행용]
# 사용법: powershell -ExecutionPolicy Bypass -File .\auth\00_before_auth.ps1
#         powershell -ExecutionPolicy Bypass -File .\auth\00_before_auth.ps1 -Clip   # 클립보드 복사
#
# [목적]
#   원격 서버는 DB 직접 조작 헬퍼가 없다(00_common 참고). 그래서 테스트 계정 재가입 시
#   users.email / users.school_email / email_verifications.email 의 UNIQUE 제약에 걸려
#   signup 이 실패할 수 있다. 이 스크립트는 .env(환경변수)에 설정된 테스트 이메일들을
#   그대로 끼워 넣은 "정리 SQL" 을 터미널에 출력한다. 출력된 SQL 을 원격 DB(pgAdmin/psql)에
#   붙여넣어 실행하면, 해당 계정과 그에 딸린 FK 자식 행이 모두 지워져 깨끗하게 재가입할 수 있다.
#
#   ※ 이 스크립트 자체는 DB 에 아무것도 쓰지 않는다. SQL 을 "출력만" 한다.
#     (원격 DB 접속 자격은 스크립트가 갖고 있지 않으므로 사람이 직접 실행한다.)
param(
    [switch]$Clip   # 지정 시 생성된 SQL 을 클립보드로도 복사한다.
)
. "$PSScriptRoot\..\00_common.ps1"

# 정리 대상 이메일 = .env 의 테스트 계정 A / 유저 B / 학교 인증 이메일 (빈 값/중복 제거)
$emails = @($script:TestEmail, $script:UserBEmail, $script:SchoolEmail) |
    Where-Object { $_ -and $_.Trim() } |
    ForEach-Object { $_.Trim() } |
    Select-Object -Unique

if ($emails.Count -eq 0) {
    Write-Host "(!) 정리할 이메일이 없습니다. .env 의 MATEON_TEST_EMAIL / MATEON_USERB_EMAIL / MATEON_SCHOOL_EMAIL 을 확인하세요." -ForegroundColor Red
    return
}

# SQL IN-list 로 안전하게 직렬화 (작은따옴표는 '' 로 이스케이프)
$inList = ($emails | ForEach-Object { "'" + ($_ -replace "'", "''") + "'" }) -join ", "

# 대상 유저 id 집합: 로그인 이메일 또는 학교 이메일(school_email)이 일치하는 유저.
$userIds = "SELECT id FROM users WHERE email IN ($inList) OR school_email IN ($inList)"

$sql = @"
-- ================================================================
--  회원가입 전 테스트 계정 정리 SQL  (00_before_auth.ps1 자동 생성)
--  대상 이메일: $($emails -join ', ')
--  FK 자식 → 부모(users) 순서로 삭제하여 UNIQUE 제약 충돌을 없앤다.
-- ================================================================
BEGIN;

-- 1) 채팅 메시지 (sender_id -> users)
DELETE FROM chat_messages
WHERE sender_id IN ($userIds);

-- 2) 채팅방 멤버 (user_id -> users)
DELETE FROM chat_room_members
WHERE user_id IN ($userIds);

-- 3) 팀 지원서 (user_id -> users, team_id -> teams)
--    대상 유저가 낸 지원서 + 대상 유저가 리더인 팀에 달린 지원서까지 제거한다.
DELETE FROM team_applications
WHERE user_id IN ($userIds)
   OR team_id IN (SELECT id FROM teams WHERE leader_user_id IN ($userIds));

-- 4) 대상 유저가 리더인 팀 (leader_user_id -> users; FK 는 없지만 잔여물 정리)
DELETE FROM teams
WHERE leader_user_id IN ($userIds);

-- 5) 알림 (user_id -> users)
DELETE FROM notification
WHERE user_id IN ($userIds);

-- 6) 리프레시 토큰 (user_id UNIQUE — 재로그인 위해 정리)
DELETE FROM refresh_tokens
WHERE user_id IN ($userIds);

-- 7) 이메일 인증코드 (email UNIQUE — 깨끗한 재요청 위해 정리)
DELETE FROM email_verifications
WHERE email IN ($inList);

-- 8) 마지막으로 유저 본체 (email / school_email UNIQUE)
DELETE FROM users
WHERE email IN ($inList) OR school_email IN ($inList);

COMMIT;
"@

Write-Host "`n########## 0. Before-Auth DB 정리 SQL 생성 (BaseUrl=$script:BaseUrl) ##########" -ForegroundColor Magenta
Write-Host "  대상 이메일: $($emails -join ', ')" -ForegroundColor DarkGray
Write-Host "  아래 SQL 을 원격 DB(pgAdmin/psql)에 붙여넣어 실행하세요. (이 스크립트는 DB 에 쓰지 않습니다)`n" -ForegroundColor Yellow

# SQL 본문은 파싱/복사 편의를 위해 색 없이 그대로 출력한다.
Write-Output $sql

if ($Clip) {
    try {
        $sql | Set-Clipboard
        Write-Host "`n  (i) SQL 을 클립보드로 복사했습니다." -ForegroundColor Green
    } catch {
        Write-Host "`n  (!) 클립보드 복사 실패: $($_.Exception.Message)" -ForegroundColor Red
    }
}
