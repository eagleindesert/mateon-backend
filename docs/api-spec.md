# Mateon Backend API Specification

> **Base URL**: `/`
>
> **인증 방식**: JWT Bearer Token (`Authorization: Bearer <accessToken>`)
>
> **Last Updated**: 2026-07-11

---

## 목차

- [공통 응답 구조](#공통-응답-구조)
- [1. 인증 (Auth)](#1-인증-auth)
- [2. 헬스체크 (Health)](#2-헬스체크-health)
- [3. 사용자 (User)](#3-사용자-user)
- [4. 일정/활동 (Events)](#4-일정활동-events)
- [5. 팀 매칭 (Teams)](#5-팀-매칭-teams)
- [6. 알림 (Notifications)](#6-알림-notifications)
- [7. OAuth 디버그 (Debug)](#7-oauth-디버그-debug)
- [공통 에러 응답](#공통-에러-응답)
- [Enum 정의](#enum-정의)

---

## 공통 응답 구조

모든 API 응답은 `ApiResponse<T>` 공통 래퍼 형식을 따릅니다.

### 성공 응답 (`success: true`)
```json
{
  "success": true,
  "message": "성공 또는 사용자 정의 성공 메시지",
  "data": { ... } // 혹은 List 등, 반환할 데이터가 없는 경우 null
}
```

### 실패 응답 (`success: false`)
```json
{
  "success": false,
  "message": "에러 설명 메시지",
  "data": null
}
```

---

## 1. 인증 (Auth)

> **Controller**: `AuthController`  
> **Base Path**: `/api/auth`  
> **인증 필요**: ❌ (일부 기능 제외)

### POST `/api/auth/email/request`
이메일 회원가입을 위한 인증코드를 발송합니다.

**Request Body** (`EmailRequest`):
```json
{
  "email": "user@dankook.ac.kr"
}
```
* **Validation**:
  * `email`: 필수, 올바른 이메일 형식이어야 합니다.

**Response** `200 OK`:
```json
{
  "success": true,
  "message": "인증코드가 발송되었습니다.",
  "data": null
}
```

---

### POST `/api/auth/email/verify`
이메일 가입을 위한 인증코드를 검증합니다.

**Request Body** (`EmailVerifyRequest`):
```json
{
  "email": "user@dankook.ac.kr",
  "code": "123456"
}
```
* **Validation**:
  * `email`: 필수, 올바른 이메일 형식이어야 합니다.
  * `code`: 필수, 6자리 숫자 형식이어야 합니다.

**Response** `200 OK`:
```json
{
  "success": true,
  "message": "이메일 인증이 완료되었습니다.",
  "data": null
}
```

---

### POST `/api/auth/school/email/request`
로그인 후 학교(재학생) 이메일 인증코드를 발송합니다.

**Headers**: `Authorization: Bearer <accessToken>` (필수)

**Request Body** (`SchoolEmailRequest`):
```json
{
  "schoolEmail": "student@dankook.ac.kr"
}
```
* **Validation**:
  * `schoolEmail`: 필수, 올바른 이메일 형식이어야 합니다.

**Response** `200 OK`:
```json
{
  "success": true,
  "message": "학교 이메일로 인증코드가 발송되었습니다.",
  "data": null
}
```

---

### POST `/api/auth/school/email/verify`
로그인 후 학교(재학생) 이메일 인증코드를 검증하여 재학생으로 확정합니다.

**Headers**: `Authorization: Bearer <accessToken>` (필수)

**Request Body** (`SchoolEmailVerifyRequest`):
```json
{
  "schoolEmail": "student@dankook.ac.kr",
  "code": "123456"
}
```
* **Validation**:
  * `schoolEmail`: 필수, 올바른 이메일 형식이어야 합니다.
  * `code`: 필수, 6자리 숫자 형식이어야 합니다.

**Response** `200 OK`:
```json
{
  "success": true,
  "message": "학교 인증이 완료되었습니다.",
  "data": null
}
```

---

### POST `/api/auth/signup`
일반 회원가입을 완료합니다.

**Request Body** (`SignupRequest`):
```json
{
  "email": "user@dankook.ac.kr",
  "password": "Password123!",
  "passwordConfirm": "Password123!",
  "name": "홍길동",
  "campus": "JUKJEON",
  "college": "SW융합대학",
  "major": "컴퓨터공학과",
  "grade": "3학년",
  "interestJobPrimary": "백엔드 개발자",
  "interestJobSecondary": "인프라 엔지니어",
  "interestJobTertiary": "데이터 엔지니어",
  "tagline": "열정적인 백엔드 개발자 지망생입니다."
}
```
* **Validation**:
  * `email`: 필수, 단국대학교 이메일(`@dankook.ac.kr`) 형식만 허용됩니다.
  * `password`: 필수, 10자 이상 20자 이하로 제한됩니다.
  * `passwordConfirm`: 필수.
  * `name`: 필수, 50자 이하로 제한됩니다.
  * `college`, `major`, `interestJobPrimary`, `interestJobSecondary`, `interestJobTertiary`: 선택, 최대 100자 이하여야 합니다.
  * `grade`: 선택, 최대 10자 이하여야 합니다.
  * `tagline`: 선택, 최대 200자 이하여야 합니다.

**Response** `200 OK`:
```json
{
  "success": true,
  "message": "회원가입이 완료되었습니다.",
  "data": {
    "accessToken": "string",
    "refreshToken": "string",
    "tokenType": "Bearer",
    "expiresIn": 86400000
  }
}
```

---

### POST `/api/auth/login`
이메일과 비밀번호로 로그인합니다.

**Request Body** (`LoginRequest`):
```json
{
  "email": "user@dankook.ac.kr",
  "password": "Password123!"
}
```
* **Validation**:
  * `email`: 필수, 올바른 이메일 형식이어야 합니다.
  * `password`: 필수.

**Response** `200 OK`:
```json
{
  "success": true,
  "message": "로그인 성공",
  "data": {
    "accessToken": "string",
    "refreshToken": "string",
    "tokenType": "Bearer",
    "expiresIn": 86400000
  }
}
```

---

### POST `/api/auth/social/kakao`
카카오 소셜 로그인을 완료하고 토큰을 발급받습니다. (React Native 등의 클라이언트가 카카오 SDK로 획득한 `accessToken`을 전달받아 검증 및 회원 처리합니다.)

**Request Body** (`KakaoLoginRequest`):
```json
{
  "accessToken": "string (카카오 SDK 액세스 토큰)"
}
```
* **Validation**:
  * `accessToken`: 필수.

**Response** `200 OK`:
```json
{
  "success": true,
  "message": "카카오 로그인 성공",
  "data": {
    "accessToken": "string",
    "refreshToken": "string",
    "tokenType": "Bearer",
    "expiresIn": 86400000
  }
}
```

---

### POST `/api/auth/token/refresh`
리프레시 토큰을 사용하여 새로운 액세스 토큰을 발급받습니다.

**Request Body** (`RefreshTokenRequest`):
```json
{
  "refreshToken": "string"
}
```
* **Validation**:
  * `refreshToken`: 필수.

**Response** `200 OK`:
```json
{
  "success": true,
  "message": "토큰이 갱신되었습니다.",
  "data": {
    "accessToken": "string",
    "refreshToken": "string",
    "tokenType": "Bearer",
    "expiresIn": 86400000
  }
}
```

---

### POST `/api/auth/password/change`
비로그인 상태에서 비밀번호 변경을 요청합니다.

**Request Body** (`ChangePasswordRequest`):
```json
{
  "email": "user@dankook.ac.kr",
  "currentPassword": "OldPassword123!",
  "newPassword": "NewPassword123!",
  "newPasswordConfirm": "NewPassword123!"
}
```
* **Validation**:
  * `email`: 필수, 올바른 이메일 형식이어야 합니다.
  * `currentPassword`: 필수.
  * `newPassword`: 필수, 10자 이상 20자 이하로 제한됩니다.
  * `newPasswordConfirm`: 필수.

**Response** `200 OK`:
```json
{
  "success": true,
  "message": "비밀번호가 변경되었습니다. 다시 로그인해주세요.",
  "data": null
}
```

---

### POST `/api/auth/logout`
로그아웃을 처리합니다.

**Request Body** (`LogoutRequest`):
```json
{
  "email": "user@dankook.ac.kr"
}
```
* **Validation**:
  * `email`: 필수, 올바른 이메일 형식이어야 합니다.

**Response** `200 OK`:
```json
{
  "success": true,
  "message": "로그아웃되었습니다.",
  "data": null
}
```

---

## 2. 헬스체크 (Health)

> **Controller**: `HealthController`  
> **Base Path**: `/`  
> **인증 필요**: ❌

### GET `/`
루트 경로에서 서버의 기본적인 동작 상태와 버전을 확인합니다.

**Response** `200 OK`:
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

---

### GET `/health`
로드 밸런서 또는 헬스 모니터링 툴을 위한 간단 상태 체크 API입니다.

**Response** `200 OK`:
```json
{
  "success": true,
  "message": "성공",
  "data": {
    "status": "UP"
  }
}
```

---

## 3. 사용자 (User)

> **Controller**: `UserController`  
> **Base Path**: `/api/users`  
> **인증 필요**: ✅ (`Authorization: Bearer <accessToken>`)

### GET `/api/users/me`
현재 로그인한 사용자의 상세 프로필 정보를 조회합니다.

**Response** `200 OK`:
```json
{
  "success": true,
  "message": "성공",
  "data": {
    "id": 1,
    "email": "user@dankook.ac.kr",
    "schoolEmail": "student@dankook.ac.kr",
    "schoolVerified": true,
    "name": "홍길동",
    "campus": "JUKJEON",
    "college": "SW융합대학",
    "major": "컴퓨터공학과",
    "grade": "3학년",
    "interestJobPrimary": "백엔드 개발자",
    "interestJobSecondary": "인프라 엔지니어",
    "interestJobTertiary": "데이터 엔지니어",
    "tagline": "열정적인 백엔드 개발자 지망생입니다.",
    "createdAt": "2026-07-11T12:00:00",
    "updatedAt": "2026-07-11T12:00:00"
  }
}
```

---

### PUT `/api/users/me`
현재 로그인한 사용자의 프로필 정보를 수정합니다.

**Request Body** (`UserUpdateRequest`):
```json
{
  "name": "김길동",
  "campus": "CHEONAN",
  "college": "과학기술대학",
  "major": "통계데이터사이언스",
  "grade": "4학년",
  "interestJobPrimary": "데이터 사이언티스트",
  "interestJobSecondary": "머신러닝 엔지니어",
  "interestJobTertiary": "데이터 분석가",
  "tagline": "데이터 분석에 특화된 학생입니다."
}
```
* **Validation**:
  * `name`: 최대 50자 이하여야 합니다.
  * `college`, `major`, `interestJobPrimary`, `interestJobSecondary`, `interestJobTertiary`: 최대 100자 이하여야 합니다.
  * `grade`: 최대 10자 이하여야 합니다.
  * `tagline`: 최대 200자 이하여야 합니다.

**Response** `200 OK`:
```json
{
  "success": true,
  "message": "정보가 수정되었습니다.",
  "data": {
    "id": 1,
    "email": "user@dankook.ac.kr",
    "schoolEmail": "student@dankook.ac.kr",
    "schoolVerified": true,
    "name": "김길동",
    "campus": "CHEONAN",
    "college": "과학기술대학",
    "major": "통계데이터사이언스",
    "grade": "4학년",
    "interestJobPrimary": "데이터 사이언티스트",
    "interestJobSecondary": "머신러닝 엔지니어",
    "interestJobTertiary": "데이터 분석가",
    "tagline": "데이터 분석에 특화된 학생입니다.",
    "createdAt": "2026-07-11T12:00:00",
    "updatedAt": "2026-07-11T12:30:00"
  }
}
```

---

### GET `/api/users/mypage`
마이페이지 종합 정보를 조회합니다. 여기에는 기본 프로필 정보, 드림이 리포트(AI 분석 결과), 참여가 확정(승인)된 활동 목록이 포함되어 있습니다.

**Response** `200 OK`:
```json
{
  "success": true,
  "message": "성공",
  "data": {
    "name": "김길동",
    "college": "과학기술대학",
    "major": "통계데이터사이언스",
    "grade": "4학년",
    "interestJobPrimary": "데이터 사이언티스트",
    "campus": "CHEONAN",
    "schoolVerified": true,
    "dreamyReport": {
      "score": 85,
      "strength": "다수의 파이썬 데이터 분석 및 통계 모델링 경험 보유",
      "weakness": "협업 툴 사용 경험 및 클라우드 배포 경험 부족",
      "recommendedAction": "Git을 이용한 팀 협업 프로젝트 또는 AWS 인프라 구축 공모전 참여 추천"
    },
    "participatedActivities": [
      {
        "id": 5,
        "title": "단국대학교 데이터 경진대회",
        "category": "SCHOOL"
      }
    ]
  }
}
```

---

### POST `/api/users/password/change`
로그인한 상태에서 현재 비밀번호를 인증하고 새 비밀번호로 변경합니다.

**Request Body** (`PasswordChangeRequest`):
```json
{
  "currentPassword": "Password123!",
  "newPassword": "NewPassword123!",
  "newPasswordConfirm": "NewPassword123!"
}
```
* **Validation**:
  * `currentPassword`: 필수.
  * `newPassword`: 필수, 10자 이상 20자 이하로 제한됩니다.
  * `newPasswordConfirm`: 필수.

**Response** `200 OK`:
```json
{
  "success": true,
  "message": "비밀번호가 변경되었습니다. 다시 로그인해주세요.",
  "data": null
}
```

---

## 4. 일정/활동 (Events)

> **Controller**: `EventController`  
> **Base Path**: `/api/events`  
> **인증 필요**: 엔드포인트별 다름

### GET `/api/events/search`
공모전, 대외활동, 교내 활동을 필터링 및 관련도 정렬하여 조회합니다.  
인증 정보(`Authorization: Bearer <accessToken>`)를 담아 보낼 경우, 사용자 프로필 정보를 바탕으로 매칭 관련도 점수(`Relevance Score`)를 매겨 가장 적합한 활동이 상위로 오도록 정렬됩니다.

**Headers**: `Authorization: Bearer <accessToken>` (선택사항)

**Query Parameters**:
* `college`: 단과대학명 필터 (예: `SW융합대학`, 생략하거나 `전체` 입력 시 전체 조회)
* `category`: 카테고리 필터 (`CONTEST`, `EXTERNAL`, `SCHOOL`)

**Response** `200 OK`:
```json
{
  "success": true,
  "message": "성공",
  "data": [
    {
      "id": 2,
      "category": "CONTEST",
      "title": "제3회 ICT 융합 서비스 공모전",
      "description": "ICT 기반의 유니크한 서비스 기획 및 개발 경진대회",
      "imageUrl": "https://example.com/contest2.jpg",
      "detailUrl": "https://example.com/details/2",
      "startDate": "2026-07-01",
      "endDate": "2026-08-15",
      "campusScope": "ALL",
      "targetColleges": "[\"SW융합대학\", \"경영대학\"]",
      "summarizedDescription": "대학생 대상 ICT 아이디어 공모전",
      "recommendedTargets": "[\"서비스 기획/개발에 관심 있는 단국대생\", \"팀 프로젝트를 경험하고 싶으신 분\"]"
    }
  ]
}
```

---

### GET `/api/events/recommended`
홈 화면에서 사용하기 적합한 맞춤 활동 추천을 제공합니다.  
사용자 프로필 기반으로 관련도 점수가 가장 높은 활동을 각 카테고리별로 1개씩(최대 3개) 선정하여 제공합니다.

**Headers**: `Authorization: Bearer <accessToken>` (필수)

**Query Parameters**:
* `category`: 특정 카테고리의 추천 정보만 1개 조회하고 싶을 때 사용 (`CONTEST`, `EXTERNAL`, `SCHOOL`)

**Response** `200 OK`:
```json
{
  "success": true,
  "message": "성공",
  "data": [
    {
      "id": 2,
      "category": "CONTEST",
      "title": "제3회 ICT 융합 서비스 공모전",
      "description": "ICT 기반의 유니크한 서비스 기획 및 개발 경진대회",
      "imageUrl": "https://example.com/contest2.jpg",
      "detailUrl": "https://example.com/details/2",
      "startDate": "2026-07-01",
      "endDate": "2026-08-15",
      "campusScope": "ALL",
      "targetColleges": "[\"SW융합대학\"]",
      "summarizedDescription": "대학생 대상 ICT 아이디어 공모전",
      "recommendedTargets": "[\"개발직군\"]"
    }
  ]
}
```

---

### GET `/api/events`
전체 활동 목록을 무작위(Random)로 조회합니다.

**Response** `200 OK`:
```json
{
  "success": true,
  "message": "성공",
  "data": [ /* EventResponseDTO[] */ ]
}
```

---

## 5. 팀 매칭 (Teams)

> **Controller**: `TeamController`  
> **Base Path**: `/api/teams`  
> **인증 필요**: 엔드포인트별 다름

### GET `/api/teams`
팀 모집글 목록을 통합 조회합니다.

**Headers**: `Authorization: Bearer <accessToken>` (선택사항, 내 모집글 조회 시 필수)

**Query Parameters**:
* `eventId`: 특정 활동(공모전, 교내활동 등)에 연결된 팀 모집글만 조회할 때 전달 (Long, optional)
* `category`: 활동 카테고리로 필터링 (`CONTEST`, `EXTERNAL`, `SCHOOL`, optional)
* `myPosts`: `true`로 지정 시 자신이 작성한 팀 모집글만 필터링 (boolean, optional, 기본값 `false`)

**Response** `200 OK`:
```json
{
  "success": true,
  "message": "성공",
  "data": [
    {
      "id": 3,
      "title": "ICT 융합 공모전 함께하실 백엔드 개발자 모십니다.",
      "role": ["백엔드 개발자"],
      "isRecruiting": true,
      "eventId": 2,
      "connectedActivityTitle": "제3회 ICT 융합 서비스 공모전",
      "connectedActivitySummary": "대학생 대상 ICT 아이디어 공모전",
      "characteristic": "매주 토요일 죽전역 부근 대면 회의",
      "promotionText": "열정 넘치는 개발자 환영합니다!",
      "capacity": 4,
      "currentMemberCount": 1,
      "recruitmentStartDate": "2026-07-11",
      "recruitmentEndDate": "2026-07-20",
      "leaderId": 1
    }
  ]
}
```

---

### GET `/api/teams/{teamId}`
특정 팀 모집글의 상세 내역을 조회합니다. 자신이 팀장인지 여부(`isLeader`), 해당 팀에 지원했는지 여부(`hasApplied`), 그리고 팀장(리더)의 기본적인 학적 정보가 포함됩니다.

**Headers**: `Authorization: Bearer <accessToken>` (선택사항, 사용자 맞춤 필드 조회를 위해 권장)

**Path Parameters**:
* `teamId`: 팀 모집글 ID (Long)

**Response** `200 OK` (`TeamDetailResponseDTO`):
```json
{
  "success": true,
  "message": "성공",
  "data": {
    "id": 3,
    "title": "ICT 융합 공모전 함께하실 백엔드 개발자 모십니다.",
    "role": ["백엔드 개발자"],
    "isRecruiting": true,
    "eventId": 2,
    "connectedActivityTitle": "제3회 ICT 융합 서비스 공모전",
    "connectedActivitySummary": "대학생 대상 ICT 아이디어 공모전",
    "characteristic": "매주 토요일 죽전역 부근 대면 회의",
    "promotionText": "열정 넘치는 개발자 환영합니다!",
    "capacity": 4,
    "currentMemberCount": 1,
    "recruitmentStartDate": "2026-07-11",
    "recruitmentEndDate": "2026-07-20",
    "leaderId": 1,
    "isLeader": false,
    "hasApplied": false,
    "leaderName": "홍길동",
    "leaderMajor": "컴퓨터공학과",
    "leaderGrade": "3학년",
    "leaderCollege": "SW융합대학"
  }
}
```

---

### POST `/api/teams`
새 팀 모집글을 작성합니다.

**Headers**: `Authorization: Bearer <accessToken>` (필수)

**Request Body** (`TeamRequestDTO`):
```json
{
  "eventId": 2,
  "title": "ICT 융합 공모전 함께하실 백엔드 개발자 모십니다.",
  "promotionText": "열정 넘치는 개발자 환영합니다!",
  "role": ["백엔드 개발자"],
  "characteristic": "매주 토요일 죽전역 부근 대면 회의",
  "capacity": 4,
  "recruitmentStartDate": "2026-07-11",
  "recruitmentEndDate": "2026-07-20"
}
```
* **Validation**:
  * `eventId`: 자율 프로젝트 모집일 경우 생략(null)이 가능합니다.
  * `title`: 필수, 빈 칸일 수 없습니다.
  * `role`: 필수, 비어 있을 수 없습니다.
  * `capacity`: 최소 1명 이상이어야 합니다.
  * `recruitmentStartDate`: 필수.
  * `recruitmentEndDate`: 필수.

**Response** `201 Created`:
```json
{
  "success": true,
  "message": "성공",
  "data": { /* TeamResponseDTO */ }
}
```

---

### PUT `/api/teams/{teamId}`
팀 모집글을 수정합니다. (팀장 본인만 가능)

**Headers**: `Authorization: Bearer <accessToken>` (필수)

**Path Parameters**:
* `teamId`: 수정할 팀 모집글 ID (Long)

**Request Body** (`TeamRequestDTO`): 등록용 Body와 동일

**Response** `200 OK`:
```json
{
  "success": true,
  "message": "성공",
  "data": { /* TeamResponseDTO */ }
}
```

---

### DELETE `/api/teams/{teamId}`
팀 모집글을 삭제합니다. (팀장 본인만 가능)

**Headers**: `Authorization: Bearer <accessToken>` (필수)

**Path Parameters**:
* `teamId`: 삭제할 팀 모집글 ID (Long)

**Response** `200 OK`:
```json
{
  "success": true,
  "message": "삭제되었습니다.",
  "data": null
}
```

---

### POST `/api/teams/{teamId}/apply`
특정 팀 모집글에 지원서를 제출하여 지원합니다.

**Headers**: `Authorization: Bearer <accessToken>` (필수)

**Path Parameters**:
* `teamId`: 지원할 대상 팀 ID (Long)

**Request Body** (`TeamApplicationRequestDTO`):
```json
{
  "introduction": "간단 자기소개 및 역량 소개",
  "message": "프로젝트에 성실히 참여하겠습니다. 꼭 같이 가고 싶습니다!",
  "contactNumber": "010-1234-5678",
  "portfolioUrl": "https://github.com/user/project (선택)"
}
```
* **Validation**:
  * `message`: 필수.
  * `contactNumber`: 필수.

**Response** `200 OK`:
```json
{
  "success": true,
  "message": "지원이 완료되었습니다.",
  "data": null
}
```

---

### GET `/api/teams/applications/me`
내가 쓴 지원서 목록을 조회합니다.

**Headers**: `Authorization: Bearer <accessToken>` (필수)

**Response** `200 OK`:
```json
{
  "success": true,
  "message": "성공",
  "data": [
    {
      "applicationId": 5,
      "teamId": 3,
      "teamTitle": "ICT 융합 공모전 함께하실 백엔드 개발자 모십니다.",
      "applicant": { /* UserResponse (내 정보) */ },
      "introduction": "간단 자기소개 및 역량 소개",
      "message": "프로젝트에 성실히 참여하겠습니다. 꼭 같이 가고 싶습니다!",
      "contactNumber": "010-1234-5678",
      "portfolioUrl": "https://github.com/user/project",
      "isMine": true,
      "status": "PENDING",
      "createdAt": "2026-07-11T13:00:00"
    }
  ]
}
```

---

### GET `/api/teams/{teamId}/applications`
해당 팀 모집글에 수신된 지원서 목록을 조회합니다. (해당 팀의 팀장만 접근 가능)

**Headers**: `Authorization: Bearer <accessToken>` (필수)

**Path Parameters**:
* `teamId`: 자신이 개설한 팀의 ID (Long)

**Response** `200 OK`:
```json
{
  "success": true,
  "message": "성공",
  "data": [
    {
      "applicationId": 5,
      "teamId": 3,
      "teamTitle": "ICT 융합 공모전 함께하실 백엔드 개발자 모십니다.",
      "applicant": { /* UserResponse (지원자 정보) */ },
      "introduction": "간단 자기소개 및 역량 소개",
      "message": "프로젝트에 성실히 참여하겠습니다. 꼭 같이 가고 싶습니다!",
      "contactNumber": "010-1234-5678",
      "portfolioUrl": "https://github.com/user/project",
      "isMine": false,
      "status": "PENDING",
      "createdAt": "2026-07-11T13:00:00"
    }
  ]
}
```

---

### PATCH `/api/teams/applications/{applicationId}`
수신한 지원서에 대해 승인 혹은 거절을 선택하여 처리합니다. (팀장만 가능)

**Headers**: `Authorization: Bearer <accessToken>` (필수)

**Path Parameters**:
* `applicationId`: 지원서 ID (Long)

**Query Parameters**:
* `isApproved`: 승인인 경우 `true`, 거절인 경우 `false` (boolean, 필수)

**Response** `200 OK`:
```json
{
  "success": true,
  "message": "승인되었습니다.", // 또는 "거절되었습니다."
  "data": null
}
```

---

### PUT `/api/teams/applications/{applicationId}`
자신이 제출했던 지원서를 수정합니다. (지원자 본인만 가능)

**Headers**: `Authorization: Bearer <accessToken>` (필수)

**Path Parameters**:
* `applicationId`: 수정할 지원서 ID (Long)

**Request Body** (`TeamApplicationRequestDTO`): 지원서 제출 시와 동일

**Response** `200 OK`:
```json
{
  "success": true,
  "message": "지원서가 수정되었습니다.",
  "data": null
}
```

---

### DELETE `/api/teams/applications/{applicationId}`
자신이 신청했던 지원을 취소합니다. (지원자 본인만 가능)

**Headers**: `Authorization: Bearer <accessToken>` (필수)

**Path Parameters**:
* `applicationId`: 삭제(취소)할 지원서 ID (Long)

**Response** `200 OK`:
```json
{
  "success": true,
  "message": "지원이 취소되었습니다.",
  "data": null
}
```

---

### GET `/api/teams/applications/{applicationId}`
특정 지원서의 상세 내역을 조회합니다. (지원서를 받은 팀장 또는 지원서를 제출한 지원자 본인만 조회 가능)

**Headers**: `Authorization: Bearer <accessToken>` (필수)

**Path Parameters**:
* `applicationId`: 지원서 ID (Long)

**Response** `200 OK` (`TeamApplicationResponseDTO`):
```json
{
  "success": true,
  "message": "성공",
  "data": {
    "applicationId": 5,
    "teamId": 3,
    "teamTitle": "ICT 융합 공모전 함께하실 백엔드 개발자 모십니다.",
    "applicant": { /* UserResponse */ },
    "introduction": "간단 자기소개 및 역량 소개",
    "message": "프로젝트에 성실히 참여하겠습니다. 꼭 같이 가고 싶습니다!",
    "contactNumber": "010-1234-5678",
    "portfolioUrl": "https://github.com/user/project",
    "isMine": false,
    "status": "APPROVED",
    "createdAt": "2026-07-11T13:00:00"
  }
}
```

---

## 6. 알림 (Notifications)

> **Controller**: `NotificationController`  
> **Base Path**: `/api/notifications`  
> **인증 필요**: ✅ (`Authorization: Bearer <accessToken>`)

### GET `/api/notifications/subscribe`
실시간 알림을 수신받기 위한 Server-Sent Events(SSE) 스트림을 구독합니다.

**Headers**: `Authorization: Bearer <accessToken>` (필수)  
**Produces**: `text/event-stream`

**Response** `200 OK`: `SseEmitter` 스트림 반환

---

### GET `/api/notifications`
현재 로그인한 사용자가 수신한 알림 목록을 전체 조회합니다.

**Response** `200 OK`:
```json
{
  "success": true,
  "message": "성공",
  "data": [
    {
      "id": 1,
      "title": "팀 지원 승인",
      "content": "홍길동 님이 보낸 'ICT 융합 공모전...' 팀 지원이 승인되었습니다.",
      "type": "APPROVE",
      "isRead": false,
      "createdAt": "2026-07-11T14:00:00"
    }
  ]
}
```

---

## 7. OAuth 디버그 (Debug)

> **Controller**: `OAuthDebugController`  
> **Base Path**: `/`  
> **인증 필요**: ❌  
> **프로필 제한**: `debug.oauth.enabled=true`인 로컬 개발 환경에서만 활성화됩니다.

### GET `/debug/oauth`
카카오 인가코드 수신용 로컬 디버그 엔드포인트입니다. 카카오 OAuth 리다이렉트 URI로 등록하여 수신된 인가 코드를 DB에 저장하고 안내 HTML을 응답합니다. 로컬 터미널에서 스크립트 실행 등을 위한 용도로 활용됩니다.

**Query Parameters**:
* `code`: 카카오 OAuth 인가 코드 (String, 필수)

**Response** `200 OK`: `text/html` 형식의 HTML 본문 반환

---

## 공통 에러 응답

`GlobalExceptionHandler`에 의해 잡히는 모든 에러 응답은 `ApiResponse.error()` 포맷을 준수하며, HTTP Status code와 함께 응답됩니다.

### 공통 에러 포맷
```json
{
  "success": false,
  "message": "에러 내용 설명",
  "data": null
}
```

| HTTP Status | 발생 조건 | 예시 메세지 (`message` 필드값) |
|-------------|-----------|----------------------------------|
| `400 Bad Request` | `MateonException` 비즈니스 예외 발생 | `이미 사용 중인 이메일입니다.`, `인증코드가 올바르지 않거나 만료되었습니다.` 등 |
| `400 Bad Request` | `IllegalArgumentException` 매개변수 오류 | 메서드에 잘못 전달된 인수 메시지 |
| `400 Bad Request` | `@Valid` 유효성 검증 실패 (`MethodArgumentNotValidException`) | `입력값 검증에 실패했습니다.` |
| `401 Unauthorized` | 로그인 인증 자격 증명 실패 (`BadCredentialsException`) | `이메일 또는 비밀번호가 올바르지 않습니다.` |
| `500 Internal Server Error` | 예기치 못한 일반 예외 (`Exception`) | `서버 오류가 발생했습니다.` |

---

## Enum 정의

### User.Campus
사용자 및 팀 모집글이 속하는 단국대학교 캠퍼스 분류입니다.
* `JUKJEON` : 죽전캠퍼스
* `CHEONAN` : 천안캠퍼스

### Event.Category
등록된 활동 혹은 공모전 등의 카테고리입니다.
* `CONTEST` : 공모전
* `EXTERNAL` : 대외활동
* `SCHOOL` : 교내활동

### Event.CampusScope
활동 또는 모집 글이 적용되는 대상 캠퍼스의 범위입니다.
* `JUKJEON` : 죽전캠퍼스 한정
* `CHEONAN` : 천안캠퍼스 한정
* `ALL` : 전체 캠퍼스 통합

### ApplicationStatus
팀 매칭 지원서에 대한 심사 상태를 나타냅니다.
* `PENDING` : 심사 대기 중
* `APPROVED` : 승인됨 (팀원으로 참여 확정)
* `REJECTED` : 거절됨
