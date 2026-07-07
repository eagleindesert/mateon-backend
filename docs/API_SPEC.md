# Mateon Backend REST API 명세서

`src/main/java/.../controller` 의 컨트롤러를 기준으로 작성된 REST API 명세서입니다.

- **Base URL**: `http://{host}:{port}` (context-path 미설정)
- **인증 방식**: JWT Bearer 토큰 — 인증이 필요한 요청은 헤더에 `Authorization: Bearer {accessToken}` 을 포함해야 합니다.
- **Content-Type**: `application/json` (SSE 구독 엔드포인트 제외)

## 목차
- [공통 사항](#공통-사항)
- [1. Health (헬스체크)](#1-health-헬스체크)
- [2. Auth (인증) — `/api/auth`](#2-auth-인증--apiauth)
- [3. User (사용자) — `/api/users`](#3-user-사용자--apiusers)
- [4. Event (활동) — `/api/events`](#4-event-활동--apievents)
- [5. Team (팀 모집/지원) — `/api/teams`](#5-team-팀-모집지원--apiteams)
- [6. Notification (알림) — `/api/notifications`](#6-notification-알림--apinotifications)
- [부록: Enum 정의](#부록-enum-정의)

---

## 공통 사항

### 공통 응답 래퍼 (`ApiResponse<T>`)
모든 JSON 응답은 다음 형태로 감싸집니다. (SSE 스트림 제외)

```json
{
  "success": true,
  "message": "성공",
  "data": { }
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `success` | boolean | 성공 여부 |
| `message` | string | 결과 메시지 (기본값 `"성공"`) |
| `data` | T | 응답 데이터 (없을 경우 `null`) |

### 인증 요구사항 (SecurityConfig 기준)

| 경로 | 접근 권한 |
|------|-----------|
| `/`, `/health` | 누구나 (permitAll) |
| `/api/auth/**` | 누구나 (permitAll) |
| `/api/events/recommended` | 인증 필요 |
| `/api/events/**` (그 외) | 누구나 (permitAll, 인증 시 개인화 정렬) |
| `/api/users/**` | 인증 필요 |
| 그 외 모든 요청 (`/api/teams/**`, `/api/notifications/**` 등) | 인증 필요 |

### 공통 에러 응답
에러 발생 시 `success=false`, `data=null` 형태로 응답합니다.

```json
{
  "success": false,
  "message": "사용자를 찾을 수 없습니다.",
  "data": null
}
```

주요 에러 메시지(`ErrorCode`):

| 코드 | 메시지 |
|------|--------|
| `EMAIL_ALREADY_EXISTS` | 이미 사용 중인 이메일입니다. |
| `INVALID_EMAIL_DOMAIN` | 단국대학교 이메일(@dankook.ac.kr)만 사용 가능합니다. |
| `EMAIL_NOT_VERIFIED` | 이메일 인증이 완료되지 않았습니다. |
| `INVALID_VERIFICATION_CODE` | 인증코드가 올바르지 않거나 만료되었습니다. |
| `INVALID_CREDENTIALS` | 이메일 또는 비밀번호가 올바르지 않습니다. |
| `TOKEN_EXPIRED` | 토큰이 만료되었습니다. |
| `INVALID_TOKEN` | 유효하지 않은 토큰입니다. |
| `TOKEN_NOT_FOUND` | 리프레시 토큰을 찾을 수 없습니다. |
| `USER_NOT_FOUND` | 사용자를 찾을 수 없습니다. |
| `PASSWORD_MISMATCH` | 비밀번호가 일치하지 않습니다. |
| `RESOURCE_NOT_FOUND` | 요청한 정보를 찾을 수 없습니다. |
| `FORBIDDEN_ACCESS` | 해당 자원에 대한 접근 권한이 없습니다. |
| `DUPLICATE_RESOURCE` | 이미 처리된 내역이 존재합니다. |
| `INVALID_INPUT` | 잘못된 입력값입니다. |
| `UNAUTHORIZED` | 인증이 필요합니다. |
| `INTERNAL_SERVER_ERROR` | 서버 오류가 발생했습니다. |

---

## 1. Health (헬스체크)

### 1.1 루트 상태 확인
`GET /`

- **인증**: 불필요
- **응답 200**
```json
{
  "success": true,
  "message": "성공",
  "data": {
    "status": "UP",
    "message": "Mateon Backend API is running",
    "version": "1.0.0"
  }
}
```

### 1.2 헬스 체크
`GET /health`

- **인증**: 불필요
- **응답 200**
```json
{
  "success": true,
  "message": "성공",
  "data": { "status": "UP" }
}
```

---

## 2. Auth (인증) — `/api/auth`

### 2.1 이메일 인증코드 요청
`POST /api/auth/email/request`

- **인증**: 불필요
- **Request Body**

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `email` | string | ✅ | 이메일 형식 |

```json
{ "email": "student@dankook.ac.kr" }
```
- **응답 200**: `data = null`, message `"인증코드가 발송되었습니다."`

### 2.2 이메일 인증코드 검증
`POST /api/auth/email/verify`

- **인증**: 불필요
- **Request Body**

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `email` | string | ✅ | 이메일 형식 |
| `code` | string | ✅ | 6자리 숫자 |

```json
{ "email": "student@dankook.ac.kr", "code": "123456" }
```
- **응답 200**: message `"이메일 인증이 완료되었습니다."`

### 2.3 회원가입
`POST /api/auth/signup`

- **인증**: 불필요 (사전에 이메일 인증 완료 필요)
- **Request Body**

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `email` | string | ✅ | `@dankook.ac.kr` 도메인만 허용 |
| `password` | string | ✅ | 10~20자 |
| `passwordConfirm` | string | ✅ | 비밀번호 확인 |
| `name` | string | ✅ | 최대 50자 |
| `campus` | enum(`Campus`) | ❌ | `JUKJEON` / `CHEONAN` |
| `college` | string | ❌ | 단과대, 최대 100자 |
| `major` | string | ❌ | 학과, 최대 100자 |
| `grade` | string | ❌ | 학년, 최대 10자 |
| `interestJobPrimary` | string | ❌ | 희망직무1, 최대 100자 |
| `interestJobSecondary` | string | ❌ | 희망직무2, 최대 100자 |
| `interestJobTertiary` | string | ❌ | 희망직무3, 최대 100자 |
| `tagline` | string | ❌ | 한 줄 소개, 최대 200자 |

- **응답 200**: message `"회원가입이 완료되었습니다."`, `data = TokenResponse`

### 2.4 로그인
`POST /api/auth/login`

- **인증**: 불필요
- **Request Body**

| 필드 | 타입 | 필수 |
|------|------|------|
| `email` | string | ✅ |
| `password` | string | ✅ |

- **응답 200**: message `"로그인 성공"`, `data = TokenResponse`

**TokenResponse**
```json
{
  "accessToken": "eyJhb...",
  "refreshToken": "eyJhb...",
  "tokenType": "Bearer",
  "expiresIn": 3600
}
```

### 2.5 토큰 갱신
`POST /api/auth/token/refresh`

- **인증**: 불필요 (리프레시 토큰으로 검증)
- **Request Body**

| 필드 | 타입 | 필수 |
|------|------|------|
| `refreshToken` | string | ✅ |

- **응답 200**: message `"토큰이 갱신되었습니다."`, `data = TokenResponse`

### 2.6 비밀번호 변경
`POST /api/auth/password/change`

- **인증**: 불필요 (본문의 email/현재 비밀번호로 검증)
- **Request Body**

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `email` | string | ✅ | 이메일 형식 |
| `currentPassword` | string | ✅ | 현재 비밀번호 |
| `newPassword` | string | ✅ | 10~20자 |
| `newPasswordConfirm` | string | ✅ | 새 비밀번호 확인 |

- **응답 200**: message `"비밀번호가 변경되었습니다. 다시 로그인해주세요."`

### 2.7 로그아웃
`POST /api/auth/logout`

- **인증**: 불필요
- **Request Body**

| 필드 | 타입 | 필수 |
|------|------|------|
| `email` | string | ✅ |

- **응답 200**: message `"로그아웃되었습니다."`

---

## 3. User (사용자) — `/api/users`

> 모든 엔드포인트는 **인증 필요**. 사용자 식별은 토큰의 email로 처리됩니다.

### 3.1 내 프로필 조회
`GET /api/users/me`

- **응답 200**: `data = UserResponse`

**UserResponse**
```json
{
  "id": 1,
  "email": "student@dankook.ac.kr",
  "name": "홍길동",
  "campus": "JUKJEON",
  "college": "SW융합대학",
  "major": "소프트웨어학과",
  "grade": "3학년",
  "interestJobPrimary": "백엔드 개발자",
  "interestJobSecondary": null,
  "interestJobTertiary": null,
  "tagline": "안녕하세요",
  "createdAt": "2026-01-01T10:00:00",
  "updatedAt": "2026-01-02T10:00:00"
}
```

### 3.2 내 프로필 수정
`PUT /api/users/me`

- **Request Body** (모두 선택, 미전송 필드는 검증 규칙만 적용)

| 필드 | 타입 | 제약 |
|------|------|------|
| `name` | string | 최대 50자 |
| `campus` | enum(`Campus`) | `JUKJEON`/`CHEONAN` |
| `college` | string | 최대 100자 |
| `major` | string | 최대 100자 |
| `grade` | string | 최대 10자 |
| `interestJobPrimary` | string | 최대 100자 |
| `interestJobSecondary` | string | 최대 100자 |
| `interestJobTertiary` | string | 최대 100자 |
| `tagline` | string | 최대 200자 |

- **응답 200**: message `"정보가 수정되었습니다."`, `data = UserResponse`

### 3.3 마이페이지 조회
`GET /api/users/mypage`

- **응답 200**: `data = MyPageResponseDTO`

```json
{
  "name": "홍길동",
  "college": "SW융합대학",
  "major": "소프트웨어학과",
  "grade": "3학년",
  "interestJobPrimary": "백엔드 개발자",
  "campus": "JUKJEON",
  "dreamyReport": {
    "score": 85,
    "strength": "강점 설명",
    "weakness": "보완점 설명",
    "recommendedAction": "추천 활동"
  },
  "participatedActivities": [
    { "id": 10, "title": "OO 공모전", "category": "공모전" }
  ]
}
```

### 3.4 비밀번호 변경
`POST /api/users/password/change`

- **Request Body**

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `currentPassword` | string | ✅ | 현재 비밀번호 |
| `newPassword` | string | ✅ | 10~20자 |
| `newPasswordConfirm` | string | ✅ | 새 비밀번호 확인 |

- **응답 200**: message `"비밀번호가 변경되었습니다. 다시 로그인해주세요."`

---

## 4. Event (활동) — `/api/events`

### 4.1 활동 검색
`GET /api/events/search`

- **인증**: 선택 (인증 시 키워드 매칭 점수 기반으로 정렬됨)
- **Query Parameters**

| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| `college` | string | ❌ | 단과대 필터. `"전체"` 또는 공백 시 미적용 |
| `category` | enum(`Category`) | ❌ | `CONTEST`/`EXTERNAL`/`SCHOOL` |

- **동작**: college·category 조합에 따라 필터링, 인증 사용자는 관련도 점수 → 최신순 정렬, 비인증 사용자는 기본 목록.
- **응답 200**: `data = List<EventResponseDTO>`

### 4.2 맞춤 활동 추천 (홈화면)
`GET /api/events/recommended`

- **인증**: **필수**
- **Query Parameters**

| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| `category` | enum(`Category`) | ❌ | 지정 시 해당 카테고리 최상위 1개, 미지정 시 카테고리별 각 1개씩 |

- **응답 200**: `data = List<EventResponseDTO>`
- **에러**: 미인증 시 `UNAUTHORIZED`, 사용자 없음 시 `USER_NOT_FOUND`

### 4.3 전체 활동 조회 (랜덤)
`GET /api/events`

- **인증**: 불필요
- **동작**: 전체 활동을 랜덤 순서로 반환
- **응답 200**: `data = List<EventResponseDTO>`

**EventResponseDTO**
```json
{
  "id": 1,
  "category": "CONTEST",
  "title": "OO 공모전",
  "description": "상세 설명",
  "imageUrl": "https://...",
  "detailUrl": "https://...",
  "startDate": "2026-07-01",
  "endDate": "2026-07-31",
  "campusScope": "ALL",
  "targetColleges": "[\"SW융합대학\"]",
  "summarizedDescription": "요약 설명",
  "recommendedTargets": "[\"3학년\"]"
}
```

---

## 5. Team (팀 모집/지원) — `/api/teams`

> 별도 명시가 없는 한 **인증 필요**. 목록/상세 조회는 비인증도 접근 가능하나(SecurityConfig 기준 `/api/teams/**`는 인증 필요), 인증 정보 유무에 따라 `isMine`/`isLeader`/`hasApplied` 등이 달라집니다.

### 5.1 팀 모집글 목록 조회
`GET /api/teams`

- **Query Parameters**

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|----------|------|------|--------|------|
| `eventId` | Long | ❌ | | 특정 활동에 연결된 팀만 |
| `category` | string | ❌ | | 카테고리 필터 |
| `myPosts` | boolean | ❌ | `false` | 내가 쓴 모집글만 조회 |

- **응답 200**: `data = List<TeamResponseDTO>`

### 5.2 팀 모집글 상세 조회
`GET /api/teams/{teamId}`

- **Path**: `teamId` (Long)
- **응답 200**: `data = TeamDetailResponseDTO` (리더 정보 + `isLeader`, `hasApplied` 포함)

### 5.3 팀 모집글 작성
`POST /api/teams`

- **Request Body**

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `eventId` | Long | ❌ | 자율 프로젝트는 null |
| `title` | string | ✅ | 모집글 제목 |
| `promotionText` | string | ❌ | 홍보 문구 |
| `role` | string[] | ✅ | 모집 역할 (1개 이상) |
| `characteristic` | string | ❌ | 팀 특징 |
| `capacity` | int | ❌ | 모집 인원 (최소 1) |
| `recruitmentStartDate` | date | ✅ | 모집 시작일 |
| `recruitmentEndDate` | date | ✅ | 모집 종료일 |

- **응답 201**: `data = TeamResponseDTO`

### 5.4 팀 모집글 수정
`PUT /api/teams/{teamId}`

- **Path**: `teamId` (Long)
- **Request Body**: 5.3 과 동일 (`TeamRequestDTO`)
- **권한**: 작성자(리더)만
- **응답 200**: `data = TeamResponseDTO`

### 5.5 팀 모집글 삭제
`DELETE /api/teams/{teamId}`

- **Path**: `teamId` (Long)
- **권한**: 작성자(리더)만
- **응답 200**: message `"삭제되었습니다."`

### 5.6 팀 지원하기
`POST /api/teams/{teamId}/apply`

- **Path**: `teamId` (Long)
- **Request Body**

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `introduction` | string | ❌ | 간단 소개글 |
| `message` | string | ✅ | 지원 동기 |
| `contactNumber` | string | ✅ | 연락처 |
| `portfolioUrl` | string | ❌ | 포트폴리오 URL |

- **응답 200**: message `"지원이 완료되었습니다."`

### 5.7 내가 쓴 지원서 목록
`GET /api/teams/applications/me`

- **응답 200**: `data = List<TeamApplicationResponseDTO>`

### 5.8 내 팀에 온 지원서 목록 (팀장용)
`GET /api/teams/{teamId}/applications`

- **Path**: `teamId` (Long)
- **권한**: 해당 팀 리더만
- **응답 200**: `data = List<TeamApplicationResponseDTO>`

### 5.9 지원서 승인/거절 (팀장용)
`PATCH /api/teams/applications/{applicationId}`

- **Path**: `applicationId` (Long)
- **Query Parameters**

| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| `isApproved` | boolean | ✅ | `true`=승인, `false`=거절 |

- **응답 200**: message `"승인되었습니다."` 또는 `"거절되었습니다."`

### 5.10 지원서 수정 (지원자용)
`PUT /api/teams/applications/{applicationId}`

- **Path**: `applicationId` (Long)
- **Request Body**: 5.6 과 동일 (`TeamApplicationRequestDTO`)
- **권한**: 지원자 본인
- **응답 200**: message `"지원서가 수정되었습니다."`

### 5.11 지원 취소 (지원자용)
`DELETE /api/teams/applications/{applicationId}`

- **Path**: `applicationId` (Long)
- **권한**: 지원자 본인
- **응답 200**: message `"지원이 취소되었습니다."`

### 5.12 지원서 상세 조회
`GET /api/teams/applications/{applicationId}`

- **Path**: `applicationId` (Long)
- **권한**: 팀장 또는 지원자 본인
- **응답 200**: `data = TeamApplicationResponseDTO`

### 관련 DTO

**TeamResponseDTO**
```json
{
  "id": 1,
  "title": "백엔드 팀원 모집",
  "role": ["백엔드", "프론트엔드"],
  "isRecruiting": true,
  "eventId": 5,
  "connectedActivityTitle": "OO 공모전",
  "connectedActivitySummary": "요약",
  "characteristic": "열정적인 팀",
  "promotionText": "함께해요",
  "capacity": 4,
  "currentMemberCount": 2,
  "recruitmentStartDate": "2026-07-01",
  "recruitmentEndDate": "2026-07-31",
  "leaderId": 10
}
```

**TeamDetailResponseDTO** (TeamResponseDTO 상속 + 다음 필드 추가)
```json
{
  "isLeader": false,
  "hasApplied": true,
  "leaderName": "김루미",
  "leaderMajor": "통계데이터사이언스",
  "leaderGrade": "3학년",
  "leaderCollege": "SW융합대학"
}
```

**TeamApplicationResponseDTO**
```json
{
  "applicationId": 100,
  "teamId": 1,
  "teamTitle": "백엔드 팀원 모집",
  "applicant": { "id": 20, "name": "홍길동", "major": "소프트웨어학과", "grade": "3학년" },
  "introduction": "간단 소개",
  "message": "지원 동기",
  "contactNumber": "010-0000-0000",
  "portfolioUrl": "https://...",
  "isMine": true,
  "status": "PENDING",
  "createdAt": "2026-07-01T12:00:00"
}
```
> `applicant` 는 `UserResponse` 구조입니다 (3.1 참고).

---

## 6. Notification (알림) — `/api/notifications`

> 모든 엔드포인트 **인증 필요**.

### 6.1 실시간 알림 구독 (SSE)
`GET /api/notifications/subscribe`

- **Produces**: `text/event-stream`
- **동작**: 인증 사용자의 SSE 연결을 생성하여 실시간 알림 수신
- **응답**: `SseEmitter` 스트림 (ApiResponse 래핑 없음)

### 6.2 내 알림 목록 조회
`GET /api/notifications`

- **응답 200**: `data = List<NotificationResponseDTO>`

**NotificationResponseDTO**
```json
{
  "id": 1,
  "title": "지원 승인",
  "content": "OO 팀 지원이 승인되었습니다.",
  "type": "APPROVE",
  "isRead": false,
  "createdAt": "2026-07-01T12:00:00"
}
```
> `type` 예시: `"APPROVE"`, `"REJECT"` 등

---

## 부록: Enum 정의

### Campus (캠퍼스)
| 값 | 설명 |
|----|------|
| `JUKJEON` | 죽전 |
| `CHEONAN` | 천안 |

### Event.Category (활동 카테고리)
| 값 | 설명 |
|----|------|
| `CONTEST` | 공모전 |
| `EXTERNAL` | 대외활동 |
| `SCHOOL` | 교내 활동 |

### Event.CampusScope (활동 대상 캠퍼스)
| 값 | 설명 |
|----|------|
| `JUKJEON` | 죽전 |
| `CHEONAN` | 천안 |
| `ALL` | 전체 |

### ApplicationStatus (지원서 상태)
| 값 | 설명 |
|----|------|
| `PENDING` | 대기중 |
| `APPROVED` | 승인됨 |
| `REJECTED` | 거절됨 |
