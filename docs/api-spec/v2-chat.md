# Mateon Backend API Specification (Notion)

> Base URL: `/`  
> 인증 방식: JWT Bearer Token (`Authorization: Bearer <accessToken>`)  
> Last Updated: 2026-07-13

---

## 📋 API 엔드포인트 총괄표

| # | 분류 | Method | Endpoint | 인증 | 설명 | FE 담당자 | BE 담당자 | FE 작업완료 | BE 작업완료 |
|---|------|--------|----------|------|------|-----------|-----------|-------------|-------------|
| 1 | Auth | POST | `/api/auth/email/request` | ❌ | 이메일 인증코드 발송 | - | - | ❌ | ✅ |
| 2 | Auth | POST | `/api/auth/email/verify` | ❌ | 이메일 인증코드 검증 | - | - | ❌ | ✅ |
| 3 | Auth | POST | `/api/auth/school/email/request` | ✅ | 학교 이메일 인증코드 발송 | - | - | ❌ | ✅ |
| 4 | Auth | POST | `/api/auth/school/email/verify` | ✅ | 학교 이메일 인증코드 검증 | - | - | ❌ | ✅ |
| 5 | Auth | POST | `/api/auth/signup` | ❌ | 일반 회원가입 | - | - | ❌ | ✅ |
| 6 | Auth | POST | `/api/auth/login` | ❌ | 이메일 로그인 | - | - | ❌ | ✅ |
| 7 | Auth | POST | `/api/auth/social/kakao` | ❌ | 카카오 로그인 | - | - | ❌ | ✅ |
| 8 | Auth | POST | `/api/auth/token/refresh` | ❌ | 토큰 갱신 | - | - | ❌ | ✅ |
| 9 | Auth | POST | `/api/auth/password/change` | ❌ | 비밀번호 변경 (비로그인 상태) | - | - | ❌ | ✅ |
| 10 | Auth | POST | `/api/auth/logout` | ❌ | 로그아웃 | - | - | ❌ | ✅ |
| 11 | Health | GET | `/` | ❌ | 루트 경로 헬스체크 | - | - | ❌ | ✅ |
| 12 | Health | GET | `/health` | ❌ | 헬스체크 | - | - | ❌ | ✅ |
| 13 | User | GET | `/api/users/me` | ✅ | 내 프로필 정보 조회 | - | - | ❌ | ✅ |
| 14 | User | PUT | `/api/users/me` | ✅ | 내 프로필 정보 수정 | - | - | ❌ | ✅ |
| 15 | User | GET | `/api/users/mypage` | ✅ | 마이페이지 종합 정보 조회 | - | - | ❌ | ✅ |
| 16 | User | POST | `/api/users/password/change` | ✅ | 비밀번호 변경 (로그인 상태) | - | - | ❌ | ✅ |
| 17 | Event | GET | `/api/events/search` | 👤 (선택) | 활동 검색 및 관련도 정렬 조회 | - | - | ❌ | ✅ |
| 18 | Event | GET | `/api/events/recommended` | ✅ | 맞춤 활동 추천 (카테고리별 1개) | - | - | ❌ | ✅ |
| 19 | Event | GET | `/api/events` | ❌ | 전체 활동 무작위 조회 | - | - | ❌ | ✅ |
| 20 | Team | GET | `/api/teams` | 👤 (선택) | 팀 모집글 목록 통합 조회 | - | - | ❌ | ✅ |
| 21 | Team | GET | `/api/teams/{teamId}` | 👤 (선택) | 팀 모집글 상세 조회 | - | - | ❌ | ✅ |
| 22 | Team | POST | `/api/teams` | ✅ | 팀 모집글 작성 | - | - | ❌ | ✅ |
| 23 | Team | PUT | `/api/teams/{teamId}` | ✅ | 팀 모집글 수정 | - | - | ❌ | ✅ |
| 24 | Team | DELETE | `/api/teams/{teamId}` | ✅ | 팀 모집글 삭제 | - | - | ❌ | ✅ |
| 25 | Team | POST | `/api/teams/{teamId}/apply` | ✅ | 특정 팀에 지원서 제출 | - | - | ❌ | ✅ |
| 26 | Team | GET | `/api/teams/applications/me` | ✅ | 내가 쓴 지원서 목록 조회 | - | - | ❌ | ✅ |
| 27 | Team | GET | `/api/teams/{teamId}/applications` | ✅ | 팀에 온 지원서 목록 조회 (팀장) | - | - | ❌ | ✅ |
| 28 | Team | PATCH | `/api/teams/applications/{applicationId}` | ✅ | 지원서 승인/거절 처리 (팀장) | - | - | ❌ | ✅ |
| 29 | Team | PUT | `/api/teams/applications/{applicationId}` | ✅ | 지원서 수정 (지원자) | - | - | ❌ | ✅ |
| 30 | Team | DELETE | `/api/teams/applications/{applicationId}` | ✅ | 지원 취소 (지원자) | - | - | ❌ | ✅ |
| 31 | Team | GET | `/api/teams/applications/{applicationId}` | ✅ | 지원서 상세 단건 조회 | - | - | ❌ | ✅ |
| 32 | Notification | GET | `/api/notifications/subscribe` | ✅ | 실시간 알림 SSE 구독 | - | - | ❌ | ✅ |
| 33 | Notification | GET | `/api/notifications` | ✅ | 내 알림 목록 조회 | - | - | ❌ | ✅ |
| 34 | Chat | POST | `/api/chat/rooms/dm` | ✅ | DM 방 조회 및 생성 | - | - | ❌ | ✅ |
| 35 | Chat | GET | `/api/chat/rooms` | ✅ | 참여 중인 채팅방 목록 조회 | - | - | ❌ | ✅ |
| 36 | Chat | GET | `/api/chat/rooms/{roomId}/messages` | ✅ | 채팅방 메시지 이력 조회 | - | - | ❌ | ✅ |
| 37 | Chat | POST | `/api/chat/rooms/{roomId}/read` | ✅ | 채팅방 메시지 읽음 처리 | - | - | ❌ | ✅ |
| 38 | Chat | PUB | `/app/chat.send` | ✅ | [STOMP] 채팅 메시지 전송 | - | - | ❌ | ✅ |
| 39 | Chat | SUB | `/topic/room.{roomId}` | ✅ | [STOMP] 채팅방 메시지 수신/구독 | - | - | ❌ | ✅ |
| 40 | Debug | GET | `/debug/oauth` | ❌ | 카카오 인가코드 수신 디버그 (dev) | - | - | ❌ | ✅ |

---

## 1. 인증 (Auth)

### POST `/api/auth/email/request` — 이메일 인증코드 발송

**Request Body (`EmailRequest`)**

| 필드 | 타입 | 필수 | Validation | 설명 |
|---|---|---|---|---|
| email | String | ✅ | @NotBlank, @Email | 인증코드를 받을 이메일 |

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`인증코드가 발송되었습니다.`) |
| data | Object | `null` |

---

### POST `/api/auth/email/verify` — 이메일 인증코드 검증

**Request Body (`EmailVerifyRequest`)**

| 필드 | 타입 | 필수 | Validation | 설명 |
|---|---|---|---|---|
| email | String | ✅ | @NotBlank, @Email | 검증할 사용자 이메일 |
| code | String | ✅ | @NotBlank, @Pattern("^\d{6}$") | 이메일로 받은 6자리 인증코드 |

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`이메일 인증이 완료되었습니다.`) |
| data | Object | `null` |

---

### POST `/api/auth/school/email/request` — 학교 이메일 인증코드 발송

**Headers**

| 헤더 | 값 | 필수 |
|---|---|---|
| Authorization | Bearer {accessToken} | ✅ |

**Request Body (`SchoolEmailRequest`)**

| 필드 | 타입 | 필수 | Validation | 설명 |
|---|---|---|---|---|
| schoolEmail | String | ✅ | @NotBlank, @Email | 학교(재학생) 이메일 주소 |

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`학교 이메일로 인증코드가 발송되었습니다.`) |
| data | Object | `null` |

---

### POST `/api/auth/school/email/verify` — 학교 이메일 인증코드 검증

**Headers**

| 헤더 | 값 | 필수 |
|---|---|---|
| Authorization | Bearer {accessToken} | ✅ |

**Request Body (`SchoolEmailVerifyRequest`)**

| 필드 | 타입 | 필수 | Validation | 설명 |
|---|---|---|---|---|
| schoolEmail | String | ✅ | @NotBlank, @Email | 학교(재학생) 이메일 주소 |
| code | String | ✅ | @NotBlank, @Pattern("^\d{6}$") | 6자리 학교 인증코드 |

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`학교 인증이 완료되었습니다.`) |
| data | Object | `null` |

---

### POST `/api/auth/signup` — 일반 회원가입

**Request Body (`SignupRequest`)**

| 필드 | 타입 | 필수 | Validation | 설명 |
|---|---|---|---|---|
| email | String | ✅ | @NotBlank, @Email, 단국대 메일 정규식 | 가입할 이메일 (단국대 메일만 가능) |
| password | String | ✅ | @NotBlank, 10자~20자 | 비밀번호 |
| passwordConfirm | String | ✅ | @NotBlank | 비밀번호 확인 |
| name | String | ✅ | @NotBlank, 최대 50자 | 이름 |
| campus | Campus | - | Enum | 소속 캠퍼스 (`JUKJEON`, `CHEONAN`) |
| college | String | - | 최대 100자 | 단과대학명 |
| major | String | - | 최대 100자 | 학과명 |
| grade | String | - | 최대 10자 | 학년 (예: `3학년`) |
| interestJobPrimary | String | - | 최대 100자 | 희망 직무 1 순위 |
| interestJobSecondary | String | - | 최대 100자 | 희망 직무 2 순위 |
| interestJobTertiary | String | - | 최대 100자 | 희망 직무 3 순위 |
| tagline | String | - | 최대 200자 | 한 줄 소개 |

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`회원가입이 완료되었습니다.`) |
| data | Object | 토큰 정보 객체 |
| data.accessToken | String | 액세스 토큰 |
| data.refreshToken | String | 리프레시 토큰 |
| data.tokenType | String | 토큰 타입 (`Bearer`) |
| data.expiresIn | Long | 만료 시간 (밀리초) |

---

### POST `/api/auth/login` — 이메일 로그인

**Request Body (`LoginRequest`)**

| 필드 | 타입 | 필수 | Validation | 설명 |
|---|---|---|---|---|
| email | String | ✅ | @NotBlank, @Email | 가입한 이메일 |
| password | String | ✅ | @NotBlank | 비밀번호 |

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`로그인 성공`) |
| data | Object | 토큰 정보 객체 (위 signup 응답 구조와 동일) |

---

### POST `/api/auth/social/kakao` — 카카오 로그인

**Request Body (`KakaoLoginRequest`)**

| 필드 | 타입 | 필수 | Validation | 설명 |
|---|---|---|---|---|
| accessToken | String | ✅ | @NotBlank | 카카오 SDK를 통해 획득한 액세스 토큰 |

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`카카오 로그인 성공`) |
| data | Object | 토큰 정보 객체 (위 signup 응답 구조와 동일) |

---

### POST `/api/auth/token/refresh` — 토큰 갱신

**Request Body (`RefreshTokenRequest`)**

| 필드 | 타입 | 필수 | Validation | 설명 |
|---|---|---|---|---|
| refreshToken | String | ✅ | @NotBlank | 만료된 액세스 토큰 갱신용 리프레시 토큰 |

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`토큰이 갱신되었습니다.`) |
| data | Object | 신규 토큰 정보 객체 (위 signup 응답 구조와 동일) |

---

### POST `/api/auth/password/change` — 비밀번호 변경 (비로그인 상태)

**Request Body (`ChangePasswordRequest`)**

| 필드 | 타입 | 필수 | Validation | 설명 |
|---|---|---|---|---|
| email | String | ✅ | @NotBlank, @Email | 비밀번호를 변경할 계정 이메일 |
| currentPassword | String | ✅ | @NotBlank | 기존 비밀번호 |
| newPassword | String | ✅ | @NotBlank, 10자~20자 | 새 비밀번호 |
| newPasswordConfirm | String | ✅ | @NotBlank | 새 비밀번호 확인 |

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`비밀번호가 변경되었습니다. 다시 로그인해주세요.`) |
| data | Object | `null` |

---

### POST `/api/auth/logout` — 로그아웃

**Request Body (`LogoutRequest`)**

| 필드 | 타입 | 필수 | Validation | 설명 |
|---|---|---|---|---|
| email | String | ✅ | @NotBlank, @Email | 로그아웃할 계정 이메일 |

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`로그아웃되었습니다.`) |
| data | Object | `null` |

---

## 2. 헬스체크 (Health)

### GET `/` — 루트 경로 헬스체크

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`성공`) |
| data | Object | 상태 세부 정보 객체 |
| data.status | String | 서버 상태 (`UP`) |
| data.message | String | 소개 텍스트 (`Mateon Backend API is running`) |
| data.version | String | API 버전 (`1.0.0`) |

---

### GET `/health` — 헬스체크

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`성공`) |
| data | Object | 상태 세부 정보 객체 |
| data.status | String | 서버 상태 (`UP`) |

---

## 3. 사용자 (User)

### GET `/api/users/me` — 내 프로필 정보 조회

**Headers**

| 헤더 | 값 | 필수 |
|---|---|---|
| Authorization | Bearer {accessToken} | ✅ |

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`성공`) |
| data | Object | 사용자 상세 정보 객체 |
| data.id | Long | 고유 ID |
| data.email | String | 계정 이메일 |
| data.schoolEmail | String | 학교 이메일 (인증된 이메일) |
| data.schoolVerified | Boolean | 재학생(학교) 인증 여부 |
| data.name | String | 이름 |
| data.campus | Campus | 캠퍼스 (`JUKJEON` / `CHEONAN`) |
| data.college | String | 단과대 |
| data.major | String | 학과 |
| data.grade | String | 학년 |
| data.interestJobPrimary | String | 희망 직무 1 |
| data.interestJobSecondary | String | 희망 직무 2 |
| data.interestJobTertiary | String | 희망 직무 3 |
| data.tagline | String | 태그라인(한줄 소개) |
| data.createdAt | LocalDateTime | 계정 생성일시 |
| data.updatedAt | LocalDateTime | 계정 수정일시 |

---

### PUT `/api/users/me` — 내 프로필 정보 수정

**Headers**

| 헤더 | 값 | 필수 |
|---|---|---|
| Authorization | Bearer {accessToken} | ✅ |

**Request Body (`UserUpdateRequest`)**

| 필드 | 타입 | 필수 | Validation | 설명 |
|---|---|---|---|---|
| name | String | - | 최대 50자 | 이름 |
| campus | Campus | - | Enum | 캠퍼스 (`JUKJEON`, `CHEONAN`) |
| college | String | - | 최대 100자 | 단과대 |
| major | String | - | 최대 100자 | 학과 |
| grade | String | - | 최대 10자 | 학년 |
| interestJobPrimary | String | - | 최대 100자 | 희망 직무 1 |
| interestJobSecondary | String | - | 최대 100자 | 희망 직무 2 |
| interestJobTertiary | String | - | 최대 100자 | 희망 직무 3 |
| tagline | String | - | 최대 200자 | 태그라인 |

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`정보가 수정되었습니다.`) |
| data | Object | 수정된 사용자 상세 정보 객체 (위 GET /api/users/me 응답 구조와 동일) |

---

### GET `/api/users/mypage` — 마이페이지 종합 정보 조회

**Headers**

| 헤더 | 값 | 필수 |
|---|---|---|
| Authorization | Bearer {accessToken} | ✅ |

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`성공`) |
| data | Object | 마이페이지 데이터 객체 |
| data.name | String | 이름 |
| data.college | String | 단과대 |
| data.major | String | 학과 |
| data.grade | String | 학년 |
| data.interestJobPrimary | String | 희망 직무 1 |
| data.campus | Campus | 캠퍼스 |
| data.schoolVerified | Boolean | 재학생 인증 상태 |
| data.dreamyReport | Object | AI 역량 분석 리포트 객체 |
| data.dreamyReport.score | Integer | 적합도 점수 (0~100) |
| data.dreamyReport.strength | String | 사용자 강점 요약 |
| data.dreamyReport.weakness | String | 사용자 보완점 요약 |
| data.dreamyReport.recommendedAction | String | 추천 조치/프로젝트 방향 |
| data.participatedActivities | Array | 참여 완료된 활동(팀 지원 승인) 요약 목록 |
| data.participatedActivities[].id | Long | 활동 ID |
| data.participatedActivities[].title | String | 활동 제목 |
| data.participatedActivities[].category | String | 활동 카테고리 |

---

### POST `/api/users/password/change` — 비밀번호 변경 (로그인 상태)

**Headers**

| 헤더 | 값 | 필수 |
|---|---|---|
| Authorization | Bearer {accessToken} | ✅ |

**Request Body (`PasswordChangeRequest`)**

| 필드 | 타입 | 필수 | Validation | 설명 |
|---|---|---|---|---|
| currentPassword | String | ✅ | @NotBlank | 현재 비밀번호 |
| newPassword | String | ✅ | @NotBlank, 10자~20자 | 변경할 신규 비밀번호 |
| newPasswordConfirm | String | ✅ | @NotBlank | 신규 비밀번호 확인 |

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`비밀번호가 변경되었습니다. 다시 로그인해주세요.`) |
| data | Object | `null` |

---

## 4. 일정/활동 (Events)

### GET `/api/events/search` — 활동 검색 및 관련도 정렬 조회

**Headers**

| 헤더 | 값 | 필수 |
|---|---|---|
| Authorization | Bearer {accessToken} | - |

**Query Parameters**

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| college | String | - | 단과대학명 필터 (생략하거나 `전체` 입력 시 전체 조회) |
| category | Category | - | 활동 카테고리 (`CONTEST`, `EXTERNAL`, `SCHOOL`) |

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`성공`) |
| data | Array | 활동 정보 목록 |
| data[].id | Long | 활동 ID |
| data[].category | Category | 카테고리 (`CONTEST`, `EXTERNAL`, `SCHOOL`) |
| data[].title | String | 활동 제목 |
| data[].description | String | 활동 상세 본문 |
| data[].imageUrl | String | 썸네일/포스터 이미지 URL |
| data[].detailUrl | String | 외부 링크 URL |
| data[].startDate | LocalDate | 활동 시작일 (YYYY-MM-DD) |
| data[].endDate | LocalDate | 활동 종료일 (YYYY-MM-DD) |
| data[].campusScope | CampusScope | 적용 캠퍼스 범위 (`JUKJEON`, `CHEONAN`, `ALL`) |
| data[].targetColleges | String (JSON) | 모집 대상 단과대학 목록 (JSON 배열 스트링) |
| data[].summarizedDescription | String | AI 한 줄 요약 문구 |
| data[].recommendedTargets | String (JSON) | AI 추천 대상자 목록 (JSON 배열 스트링) |

---

### GET `/api/events/recommended` — 맞춤 활동 추천

**Headers**

| 헤더 | 값 | 필수 |
|---|---|---|
| Authorization | Bearer {accessToken} | ✅ |

**Query Parameters**

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| category | Category | - | 추천받을 카테고리 제한 (`CONTEST`, `EXTERNAL`, `SCHOOL`) |

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`성공`) |
| data | Array | 각 카테고리별 맞춤 점수가 가장 높은 활동 1개씩 선별된 목록 (최대 3개) |

---

### GET `/api/events` — 전체 활동 무작위 조회

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`성공`) |
| data | Array | 무작위 정렬된 전체 활동 배열 (위 search 응답 구조와 동일) |

---

## 5. 팀 매칭 (Teams)

### GET `/api/teams` — 팀 모집글 목록 통합 조회

**Headers**

| 헤더 | 값 | 필수 |
|---|---|---|
| Authorization | Bearer {accessToken} | - (myPosts=true일 시 필수) |

**Query Parameters**

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---|---|---|---|---|
| eventId | Long | - | - | 특정 활동에 매핑된 팀 모집글만 필터링 |
| category | String | - | - | 활동 카테고리 필터링 |
| myPosts | Boolean | - | `false` | `true` 전달 시 자신이 작성한 모집글만 조회 |

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`성공`) |
| data | Array | 팀 모집글 요약 배열 |
| data[].id | Long | 팀 모집글 ID |
| data[].title | String | 모집글 제목 |
| data[].role | Array (String) | 모집하는 역할 리스트 (예: `["백엔드 개발자", "기획자"]`) |
| data[].isRecruiting | Boolean | 현재 모집 진행 여부 |
| data[].eventId | Long | 연결된 활동 ID (자율 프로젝트일 시 null) |
| data[].connectedActivityTitle | String | 연결된 활동 제목 |
| data[].connectedActivitySummary | String | 연결된 활동 AI 요약 본문 |
| data[].characteristic | String | 팀의 작업 성향 및 특징 |
| data[].promotionText | String | 홍보용 텍스트 |
| data[].capacity | Integer | 모집 정원 |
| data[].currentMemberCount | Integer | 현재 확정된 팀원 수 (리더 포함) |
| data[].recruitmentStartDate | LocalDate | 모집 시작일 (YYYY-MM-DD) |
| data[].recruitmentEndDate | LocalDate | 모집 종료일 (YYYY-MM-DD) |
| data[].leaderId | Long | 팀장(리더)의 사용자 ID |

---

### GET `/api/teams/{teamId}` — 팀 모집글 상세 조회

**Headers**

| 헤더 | 값 | 필수 |
|---|---|---|
| Authorization | Bearer {accessToken} | - (팀장 여부, 지원 여부 확인 시 권장) |

**Path Parameters**

| 파라미터 | 타입 | 설명 |
|---|---|---|
| teamId | Long | 팀 모집글 ID |

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`성공`) |
| data | Object | 팀 상세 데이터 객체 (위 `TeamResponse` 확장) |
| data.id ~ data.leaderId | - | 위 GET `/api/teams` 응답 필드와 동일 |
| data.isLeader | Boolean | 요청한 사용자가 이 팀의 팀장인지 여부 |
| data.hasApplied | Boolean | 요청한 사용자가 이 팀에 지원서를 작성했는지 여부 |
| data.leaderName | String | 팀장 이름 |
| data.leaderMajor | String | 팀장 전공 |
| data.leaderGrade | String | 팀장 학년 |
| data.leaderCollege | String | 팀장 단과대 |

---

### POST `/api/teams` — 팀 모집글 작성

**Headers**

| 헤더 | 값 | 필수 |
|---|---|---|
| Authorization | Bearer {accessToken} | ✅ |

**Request Body (`TeamRequestDTO`)**

| 필드 | 타입 | 필수 | Validation | 설명 |
|---|---|---|---|---|
| eventId | Long | - | - | 참여할 활동 고유 ID (자율 프로젝트 시 생략 가능) |
| title | String | ✅ | @NotBlank | 모집글 제목 |
| promotionText | String | - | - | 홍보 텍스트 |
| role | Array (String) | ✅ | @NotEmpty | 모집할 팀원 역할 배열 |
| characteristic | String | - | - | 팀 협업 특징 / 성향 |
| capacity | Integer | ✅ | @Min(1) | 팀 모집 정원 (최소 1명) |
| recruitmentStartDate | LocalDate | ✅ | @NotNull | 모집 시작 날짜 (YYYY-MM-DD) |
| recruitmentEndDate | LocalDate | ✅ | @NotNull | 모집 종료 날짜 (YYYY-MM-DD) |

**Response 201 Created**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`성공`) |
| data | Object | 등록 완료된 팀 정보 객체 (위 `TeamResponseDTO`와 동일) |

---

### PUT `/api/teams/{teamId}` — 팀 모집글 수정

**Headers**

| 헤더 | 값 | 필수 |
|---|---|---|
| Authorization | Bearer {accessToken} | ✅ |

**Path Parameters**

| 파라미터 | 타입 | 설명 |
|---|---|---|
| teamId | Long | 팀 모집글 ID |

**Request Body (`TeamRequestDTO`)**

* 위 등록용 Body(`TeamRequestDTO`) 구조 및 검증 규칙과 동일

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`성공`) |
| data | Object | 수정 완료된 팀 정보 객체 |

---

### DELETE `/api/teams/{teamId}` — 팀 모집글 삭제

**Headers**

| 헤더 | 값 | 필수 |
|---|---|---|
| Authorization | Bearer {accessToken} | ✅ |

**Path Parameters**

| 파라미터 | 타입 | 설명 |
|---|---|---|
| teamId | Long | 팀 모집글 ID |

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`삭제되었습니다.`) |
| data | Object | `null` |

---

### POST `/api/teams/{teamId}/apply` — 특정 팀에 지원서 제출

**Headers**

| 헤더 | 값 | 필수 |
|---|---|---|
| Authorization | Bearer {accessToken} | ✅ |

**Path Parameters**

| 파라미터 | 타입 | 설명 |
|---|---|---|
| teamId | Long | 지원할 팀 모집글 ID |

**Request Body (`TeamApplicationRequestDTO`)**

| 필드 | 타입 | 필수 | Validation | 설명 |
|---|---|---|---|---|
| introduction | String | - | - | 간단한 역량 및 본인 소개 |
| message | String | ✅ | @NotBlank | 지원 동기 및 자세한 내용 |
| contactNumber | String | ✅ | @NotBlank | 연락처 (예: `010-XXXX-XXXX`) |
| portfolioUrl | String | - | - | 포트폴리오 혹은 깃허브 URL |

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`지원이 완료되었습니다.`) |
| data | Object | `null` |

---

### GET `/api/teams/applications/me` — 내가 쓴 지원서 목록 조회

**Headers**

| 헤더 | 값 | 필수 |
|---|---|---|
| Authorization | Bearer {accessToken} | ✅ |

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`성공`) |
| data | Array | 지원서 정보 배열 |
| data[].applicationId | Long | 지원서 고유 ID |
| data[].teamId | Long | 지원한 팀 모집글 ID |
| data[].teamTitle | String | 지원한 팀 모집글 제목 |
| data[].applicant | Object | 지원자 정보 객체 (`UserResponse` 구조와 동일) |
| data[].introduction | String | 소개 문구 |
| data[].message | String | 지원 메시지 |
| data[].contactNumber | String | 연락처 |
| data[].portfolioUrl | String | 포트폴리오 링크 |
| data[].isMine | Boolean | 자신이 신청한 지원서인지 여부 (`true`) |
| data[].status | ApplicationStatus | 지원 상태 (`PENDING`, `APPROVED`, `REJECTED`) |
| data[].createdAt | LocalDateTime | 지원서 작성 시각 |

---

### GET `/api/teams/{teamId}/applications` — 팀에 온 지원서 목록 조회 (팀장용)

**Headers**

| 헤더 | 값 | 필수 |
|---|---|---|
| Authorization | Bearer {accessToken} | ✅ |

**Path Parameters**

| 파라미터 | 타입 | 설명 |
|---|---|---|
| teamId | Long | 내가 생성한 팀의 ID |

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`성공`) |
| data | Array | 팀에 접수된 지원서 배열 (위 응답의 `data` 구조와 동일하며, `isMine`은 `false`로 제공됨) |

---

### PATCH `/api/teams/applications/{applicationId}` — 지원서 승인/거절 처리 (팀장용)

**Headers**

| 헤더 | 값 | 필수 |
|---|---|---|
| Authorization | Bearer {accessToken} | ✅ |

**Path Parameters**

| 파라미터 | 타입 | 설명 |
|---|---|---|
| applicationId | Long | 지원서 고유 ID |

**Query Parameters**

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| isApproved | Boolean | ✅ | `true`는 승인, `false`는 거절 처리 |

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 처리 결과 문구 (`승인되었습니다.` 또는 `거절되었습니다.`) |
| data | Object | `null` |

---

### PUT `/api/teams/applications/{applicationId}` — 지원서 수정 (지원자용)

**Headers**

| 헤더 | 값 | 필수 |
|---|---|---|
| Authorization | Bearer {accessToken} | ✅ |

**Path Parameters**

| 파라미터 | 타입 | 설명 |
|---|---|---|
| applicationId | Long | 내가 작성한 지원서 ID |

**Request Body (`TeamApplicationRequestDTO`)**

* 위 등록용 Body(`TeamApplicationRequestDTO`) 구조 및 검증 규격과 동일

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`지원서가 수정되었습니다.`) |
| data | Object | `null` |

---

### DELETE `/api/teams/applications/{applicationId}` — 지원 취소 (지원자용)

**Headers**

| 헤더 | 값 | 필수 |
|---|---|---|
| Authorization | Bearer {accessToken} | ✅ |

**Path Parameters**

| 파라미터 | 타입 | 설명 |
|---|---|---|
| applicationId | Long | 취소할 지원서 ID |

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`지원이 취소되었습니다.`) |
| data | Object | `null` |

---

### GET `/api/teams/applications/{applicationId}` — 지원서 상세 단건 조회

**Headers**

| 헤더 | 값 | 필수 |
|---|---|---|
| Authorization | Bearer {accessToken} | ✅ |

**Path Parameters**

| 파라미터 | 타입 | 설명 |
|---|---|---|
| applicationId | Long | 조회할 지원서 ID |

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`성공`) |
| data | Object | 지원서 상세 데이터 객체 (위 `TeamApplicationResponseDTO` 구조와 동일) |

---

## 6. 알림 (Notifications)

### GET `/api/notifications/subscribe` — 실시간 알림 SSE 구독

**Headers**

| 헤더 | 값 | 필수 |
|---|---|---|
| Authorization | Bearer {accessToken} | ✅ |

**Produces**: `text/event-stream`

**Response 200 OK**

* `SseEmitter` 연결 반환

---

### GET `/api/notifications` — 내 알림 목록 조회

**Headers**

| 헤더 | 값 | 필수 |
|---|---|---|
| Authorization | Bearer {accessToken} | ✅ |

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`성공`) |
| data | Array | 알림 목록 배열 |
| data[].id | Long | 알림 고유 ID |
| data[].title | String | 알림 제목 |
| data[].content | String | 알림 본문 내용 |
| data[].type | String | 알림 액션 타입 (예: `APPROVE`, `REJECT` 등) |
| data[].isRead | Boolean | 수신 확인 및 읽음 상태 여부 |
| data[].createdAt | LocalDateTime | 알림 발생 시각 (클라이언트 연산용 원본) |

---

## 7. 채팅 (Chat)

### POST `/api/chat/rooms/dm` — DM 방 조회 및 생성 (멱등)

**Headers**

| 헤더 | 값 | 필수 |
|---|---|---|
| Authorization | Bearer {accessToken} | ✅ |

**Request Body (`CreateDmRequest`)**

| 필드 | 타입 | 필수 | Validation | 설명 |
|---|---|---|---|---|
| targetUserId | Long | ✅ | @NotNull | DM 상대방 고유 사용자 ID |

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`성공`) |
| data | Object | 생성 혹은 조회된 DM 방 정보 |
| data.roomId | Long | 채팅방 고유 ID |

---

### GET `/api/chat/rooms` — 참여 중인 채팅방 목록 조회

**Headers**

| 헤더 | 값 | 필수 |
|---|---|---|
| Authorization | Bearer {accessToken} | ✅ |

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`성공`) |
| data | Array | 참여 중인 채팅방 목록 |
| data[].roomId | Long | 채팅방 고유 ID |
| data[].type | RoomType | 채팅방 타입 (`DM`, `GROUP`) |
| data[].teamId | Long | GROUP 방일 때 연결된 팀 ID (없으면 `null`) |
| data[].title | String | GROUP 방 이름 (DM 방은 상대 사용자 이름으로 채워짐) |
| data[].partnerId | Long | DM 상대 사용자 ID (GROUP 방은 `null`) |
| data[].lastMessage | String | 마지막 메시지 미리보기 (메시지가 없으면 `null`) |
| data[].lastMessageAt | LocalDateTime | 마지막 메시지 전송 시각 (메시지가 없으면 `null`, YYYY-MM-DDTHH:mm:ss 형식) |
| data[].unreadCount | Long | 안읽음 메시지 개수 |

---

### GET `/api/chat/rooms/{roomId}/messages` — 채팅방 메시지 이력 조회

**Headers**

| 헤더 | 값 | 필수 |
|---|---|---|
| Authorization | Bearer {accessToken} | ✅ |

**Path Parameters**

| 파라미터 | 타입 | 설명 |
|---|---|---|
| roomId | Long | 조회할 채팅방 ID |

**Query Parameters**

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---|---|---|---|---|
| before | Long | - | - | 이 메시지 ID보다 이전의 메시지만 조회 (페이징용) |
| size | Integer | - | 30 | 한 번에 조회할 메시지 개수 |

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`성공`) |
| data | Array | 채팅 메시지 목록 (오래된 순 → 최신 순) |
| data[].messageId | Long | 메시지 고유 ID |
| data[].roomId | Long | 채팅방 고유 ID |
| data[].senderId | Long | 발신자 고유 ID |
| data[].senderName | String | 발신자 이름 |
| data[].content | String | 메시지 내용 |
| data[].createdAt | LocalDateTime | 메시지 생성 시각 (YYYY-MM-DDTHH:mm:ss 형식) |

---

### POST `/api/chat/rooms/{roomId}/read` — 채팅방 메시지 읽음 처리

**Headers**

| 헤더 | 값 | 필수 |
|---|---|---|
| Authorization | Bearer {accessToken} | ✅ |

**Path Parameters**

| 파라미터 | 타입 | 설명 |
|---|---|---|
| roomId | Long | 읽음 처리할 채팅방 ID |

**Request Body (`ReadRequest`)**

| 필드 | 타입 | 필수 | Validation | 설명 |
|---|---|---|---|---|
| lastReadMessageId | Long | ✅ | @NotNull | 여기까지 읽음 처리할 마지막 메시지 ID |

**Response 200 OK**

| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 성공 여부 (`true`) |
| message | String | 결과 메시지 (`성공`) |
| data | Object | `null` |

---

### [WebSocket PUB] `/app/chat.send` — [STOMP] 채팅 메시지 전송

**STOMP Connection Headers**

| 헤더 | 값 | 필수 | 설명 |
|---|---|---|---|
| Authorization | Bearer {accessToken} | ✅ | WebSocket CONNECT 요청 시 인증 헤더 포함 필요 (인터셉터에서 검증) |

**Destination**: `/app/chat.send`

**Payload (`ChatMessageRequest`)**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| roomId | Long | ✅ | 전송할 채팅방 ID |
| content | String | ✅ | 메시지 내용 |

**동작 설명**:
클라이언트가 `/app/chat.send` 목적지로 메시지를 발행하면, 서버에서 발신자 정보를 세션 principal로부터 획득하여 메시지를 저장하고, 해당 방의 구독자들에게 `/topic/room.{roomId}` 주소로 메시지를 브로드캐스트합니다.

---

### [WebSocket SUB] `/topic/room.{roomId}` — [STOMP] 채팅방 메시지 수신/구독

**Destination**: `/topic/room.{roomId}`

**Broadcast Payload (`ChatMessageResponse`)**

| 필드 | 타입 | 설명 |
|---|---|---|
| messageId | Long | 메시지 고유 ID |
| roomId | Long | 채팅방 고유 ID |
| senderId | Long | 발신자 고유 ID |
| senderName | String | 발신자 이름 |
| content | String | 메시지 내용 |
| createdAt | LocalDateTime | 메시지 생성 시각 (YYYY-MM-DDTHH:mm:ss 형식) |

---

## 8. OAuth 디버그 (Debug)

### GET `/debug/oauth` — 카카오 인가코드 수신용 디버그 (dev 환경)

**Query Parameters**

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| code | String | ✅ | 카카오 인가 코드 |

**Response 200 OK**

* `text/html` 웹페이지 코드가 반환됩니다.

---

## 공통 에러 응답

`GlobalExceptionHandler`에 의해 핸들링되는 모든 에러는 `ApiResponse.error()` 포맷을 따릅니다.

### 공통 에러 응답 구조
| 필드 | 타입 | 설명 |
|---|---|---|
| success | Boolean | 에러 상태 이므로 항상 `false` |
| message | String | 상세 에러 사유 (클라이언트 노출용 메시지) |
| data | Object | 항상 `null` |

### HTTP 상태 코드별 예외 조건
| HTTP Status | Exception Class | 예시 메시지 (`message` 필드값) |
|---|---|---|
| `400 Bad Request` | `MateonException` | `이미 사용 중인 이메일입니다.`, `인증코드가 올바르지 않거나 만료되었습니다.` 등 |
| `400 Bad Request` | `IllegalArgumentException` | 입력 매개변수 유효성 위반 에러 구체 내용 |
| `400 Bad Request` | `MethodArgumentNotValidException` | `입력값 검증에 실패했습니다.` |
| `401 Unauthorized` | `BadCredentialsException` | `이메일 또는 비밀번호가 올바르지 않습니다.` |
| `500 Internal Server Error` | `Exception` | `서버 오류가 발생했습니다.` |

---

## Enum 정의

### User.Campus
| 값 | 설명 |
|---|---|
| `JUKJEON` | 죽전캠퍼스 |
| `CHEONAN` | 천안캠퍼스 |

### Event.Category
| 값 | 설명 |
|---|---|
| `CONTEST` | 공모전 |
| `EXTERNAL` | 대외활동 |
| `SCHOOL` | 교내활동 |

### Event.CampusScope
| 값 | 설명 |
|---|---|
| `JUKJEON` | 죽전캠퍼스 소속 학생 한정 |
| `CHEONAN` | 천안캠퍼스 소속 학생 한정 |
| `ALL` | 캠퍼스 구분 없음 |

### ApplicationStatus
| 값 | 설명 |
|---|---|
| `PENDING` | 대기 중 (팀장의 처리를 대기하는 상태) |
| `APPROVED` | 승인됨 (팀 매칭이 완료되어 팀원으로 참여한 상태) |
| `REJECTED` | 거절됨 (팀장이 지원을 거절한 상태) |

### RoomType
| 값 | 설명 |
|---|---|
| `DM` | 1:1 개인 채팅 |
| `GROUP` | 팀 등 다인 그룹 채팅 |
