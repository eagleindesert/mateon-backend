package com.example.mateon.common.config;

import com.example.mateon.common.exception.ErrorCode;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import jakarta.validation.Valid;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * API 명세의 정본.
 *
 * <p>
 * 이전에는 명세가 손으로 쓴 마크다운(docs/api-spec)뿐이었다. 코드와 별개 출처라 조용히
 * 어긋났고, 실제로 v7 개정에서 서버가 내려주지 않는 필드 세 개가 문서에 들어간 채 v8 까지
 * 복사됐다. 여기서 만드는 스키마는 DTO 클래스에서 직접 생성되므로 같은 어긋남이 구조적으로
 * 불가능하다 — 필드를 지우면 문서에서도 사라진다.
 *
 * <p>
 * 인증 방식을 여기 한 번만 선언한다. 마크다운 명세는 엔드포인트마다 Authorization 헤더 표를
 * 손으로 반복하고 있었는데, 그건 빠뜨리기도 틀리기도 쉽다.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    /**
     * 에러 응답 본문의 공통 스키마 이름. 아래 커스터마이저가 $ref 로 가리킨다.
     */
    private static final String ERROR_SCHEMA = "ErrorResponse";
    private static final String ERROR_SCHEMA_REF = "#/components/schemas/" + ERROR_SCHEMA;

    /**
     * 인증 실패(403) 본문의 스키마 이름. 공통 봉투를 쓰지 않는 유일한 응답이라 따로 둔다.
     */
    private static final String AUTH_ERROR_SCHEMA = "AuthErrorResponse";
    private static final String AUTH_ERROR_SCHEMA_REF = "#/components/schemas/" + AUTH_ERROR_SCHEMA;

    @Bean
    public OpenAPI mateonOpenAPI() {
        return new OpenAPI()
          .info(new Info()
            .title("Mateon API")
            .version("v9-2")
            .description("""
# Mateon Backend API 변경 명세서 (v9-2)

> Base URL: `/`<br/>
> 인증 방식: JWT Bearer Token (`Authorization: Bearer <accessToken>`)<br/>
> Last Updated: 2026-09-03

🔑 **인증**: 인증이 필요한 엔드포인트는 로그인(`POST /api/auth/login`)으로 받은 accessToken 을 우측 상단 Authorize 에 넣으면 그대로 호출해 볼 수 있다.<br/><br/>
📌 **안내**: v9부터는 상세 엔드포인트 명세(Request/Response 스키마, 파라미터, 헤더, 상태 코드 등)를 **Swagger UI**가 정본(Single Source of Truth)으로 제공합니다. 본 문서는 프론트엔드(FE) 연동에 필요한 **v9 대비 기능/정책 변경 사항 및 신규 기능 동작 방식**을 텍스트로 정리한 문서입니다.

---

## 📋 v9 → v9-2 핵심 변경 사항 요약

- **🗺️ 공모전 유사도 지도 API 신설**:
  - `GET /api/events/{eventId}/similarity-map` — 기준 활동과 다른 활동들의 코사인 유사도·방사형 그래프 좌표를 반환한다.
  - 비로그인 허용(활동 검색과 같다). 쿼리 `topN` 기본 500, 최소 1, 최대 500.
  - 응답 id 는 우리 `events.id` (`Long`). 임베딩 벡터는 내려주지 않는다.
  - `radius`·`x`/`y` 는 이번 후보군 안의 **상대 순위**다. 서로 다른 요청의 점 간 거리를 비교하면 안 된다. 색과 UI 는 `similarity` 또는 `rankPercentile` 로 결정한다.
- **🔧 활동 등록 후 임베딩 비동기 계산**:
  - `POST /api/events` 의 201 응답 계약(`EventResponseDTO`)은 그대로다. 프론트가 벡터를 기다리지 않는다.
  - 등록 직후 유사도 지도를 치면 아직 벡터가 없을 수 있다 → 400 `EVENT_EMBEDDING_NOT_READY`. 잠시 후 재시도하면 된다.
  - 임베딩 실패가 등록 자체를 막지는 않는다. 실패한 활동은 서버가 다시 채우므로, 같은 400 을 받다가 성공하면 200 이 된다.
- **🆕 에러 코드 `EVENT_EMBEDDING_NOT_READY` (400) 추가**:
  - 기준 활동 행은 있지만 임베딩이 아직이거나 실패한 상태. `TEAM_EMBEDDING_NOT_READY` 와 같은 성격이다.

---

## 1. 🗺️ 공모전 유사도 지도 (`GET /api/events/{eventId}/similarity-map`)

* **인증**: 선택. 비로그인도 그대로 쓸 수 있다. 토큰을 보내도 응답이 달라지지 않는다.
* **동작**: 기준 활동을 그래프 중심에 두고, 임베딩이 있는 다른 활동들을 유사도 순으로 배치한다.
* **그래프 해석**: `radius` 와 `x`/`y` 는 절대 유사도가 아니라 이번 후보군 안의 상대 순위다. 서로 다른 요청의 점 간 거리를 직접 비교하면 안 된다. 색과 UI 는 `similarity` 또는 `rankPercentile` 로 결정한다.
* **빈 후보**: 후보가 0건이면 200 이고 `points` 는 빈 배열이다 (오류가 아니다).

**클라이언트가 구분해야 하는 실패**

* **400** `EVENT_EMBEDDING_NOT_READY`: 기준 활동의 임베딩이 아직 없거나 실패 상태다. 등록은 커밋 후 비동기로 벡터를 채우므로, 방금 올린 활동을 바로 물으면 이 코드가 난다. 잠시 후 다시 호출하면 된다.
* **404** `EVENT_NOT_FOUND`: 그 활동 행이 없다. 재시도해도 같은 결과가 나온다.
* **502** `AI_SERVER_ERROR` / **503** `AI_SERVER_UNAVAILABLE`: AI 서버 장애. 잠시 후 재시도한다.

---

## 2. 🔧 활동 등록 시 임베딩 비동기 갱신 (`POST /api/events`)

* **응답 계약은 v9 과 같다.** 201 `EventResponseDTO`. 프론트가 새 필드를 읽을 필요는 없다.
* 저장이 끝난 뒤 서버가 임베딩을 비동기로 채운다. AI 장애가 등록 자체를 막지 않는다.
* 유사도 지도의 기준 벡터가 이 값이다. 등록 직후 지도를 치면 400 `EVENT_EMBEDDING_NOT_READY` 가 날 수 있다.

---

## 3. 🛠️ 신규 ErrorCode 정리 (FE 에러 처리용)

| ErrorCode | HTTP Status | 에러 메시지 / 발생 조건 |
|---|---|---|
| `EVENT_EMBEDDING_NOT_READY` | `400 Bad Request` | 공모전 정보 분석이 아직 완료되지 않았습니다. 잠시 후 다시 시도해주세요. (유사도 지도 기준 활동의 임베딩이 아직이거나 실패한 상태. 활동 행 자체가 없으면 `EVENT_NOT_FOUND`) |

---

# Mateon Backend API 변경 명세서 (v9)

> Last Updated: 2026-08-25

## 📋 v8 → v9 핵심 변경 사항 요약

- **🤖 AI 챗봇 단일 게이트웨이 및 멀티 스레드 대화 세션 신설**:
  - 사용자 발화 의도(`MATCHING_INTENT`, `UNCLEAR`, `OUT_OF_SCOPE`)를 자동 분류하여 알맞은 AI 도메인으로 라우팅하는 단일 입구(`POST /api/ai/chat/messages`) 제공.
  - 사이드바용 다중 대화 스레드 관리(새 세션 생성, 세션 목록 조회, 세션 대화 이력 복원) 지원.
  - 신규 에러 코드 `AI_CHAT_SESSION_NOT_FOUND` (404) 추가.
- **🌡️ 협업 온도 상시 노출 전환**:
  - 평가 2건 미만 시 `null`(비공개)로 처리하던 표본 제한을 제거하고, **평가 건수와 무관하게 협업 온도를 항상 노출 (기본값 `36.5`)**.
  - 평가 0건인 유저도 기준점 `36.5`도로 표기되며, 온도를 싣지 않는 API(`collaborationReviewCount: null`)와의 구분 계약 유지.
- **📝 사용자 프로필 서술형 포트폴리오(`portfolio`) 필드 신설**:
  - 한 줄 소개(200자 제한)와 별도로, 여러 줄의 긴 역량/경험을 작성할 수 있는 `portfolio` 필드 추가 (최대 5,000자).
  - 프로필 수정 및 프로필 조회 응답에 반영.
- **👥 팀 상세 조회 응답 확정 팀원 명단(`members`) 추가**:
  - `GET /api/teams/{teamId}` 응답에 확정된 팀원 목록(`members`) 추가 (팀장 포함, `currentMemberCount`와 동일 크기).
  - 응답 JSON 키 명칭: 조회자의 팀장 여부 `leader`, 활동 종료 여부 `isEnded`, 팀원의 팀장 여부 `members[].isLeader`.
- **🔔 팀 활동 흐름 내 누락 알림 5종 추가**:
  - 지원서 접수 알림(팀장 수신), 지원 취소 알림(팀장 수신), 역제안 취소 알림(대상 유저 수신), 팀 삭제 알림(소속 팀원 및 대기자 전원 수신), 1인 팀 활동 자동 종료 알림(팀장 수신) 신규 발송.
- **🪣 서버 저장 공간 부족 에러(507) 도입**:
  - 단일 파일 용량 초과(413) 외에 서버 전체 저장소 공간 부족 시 `STORAGE_QUOTA_EXCEEDED` (HTTP 507) 반환.
- **📡 실시간 알림(SSE) 수명주기 안내**:
  - SSE 연결은 서버 정책상 일정 시간(기본 1시간) 후 정상 종료되며, 클라이언트가 자동으로 재연결하면 됨.
- **📑 API 명세의 Swagger UI 정본화**:
  - 엔드포인트별 세부 스키마 및 호출 테스트는 Swagger UI를 통해 확인 가능.

---

## 1. 🤖 AI 챗봇 단일 게이트웨이 및 멀티 스레드 대화 세션

### 1) AI 챗봇 단일 입구 (`POST /api/ai/chat/messages`)
* 사용자의 모든 채팅 발화는 이 엔드포인트 하나로 전송합니다.
* 서버가 발화 의도를 분석하여 응답의 `domain` 필드로 결과를 분기합니다.
  - `MATCHING_INTENT`: 팀/프로젝트 매칭 의도 추출 도메인으로 처리됨. `data.matching` 객체에 의도 추출 결과 및 완료 여부(`matching.completed`)가 함께 포함되어 내려오므로 추가 API 호출이 필요 없습니다.
  - `UNCLEAR`: 사용자의 의도가 불분명하여 되묻는 상태입니다. `data.assistantMessage`를 화면에 렌더링하고 다음 발화를 다시 보냅니다.
  - `OUT_OF_SCOPE`: 서비스가 다루지 않는 주제입니다. `data.assistantMessage`의 안내 문구를 화면에 표시합니다.
* `assistantMessage`는 모든 도메인 응답에서 채워져 내려오므로, 분기 처리 전 화면에 바로 렌더링할 수 있습니다.

### 2) 멀티 스레드 대화 세션 관리 (사이드바 지원)
* 기존의 단일 세션 제한이 해제되어 사용자가 여러 대화 스레드를 생성하고 전환할 수 있습니다.
* **신규 엔드포인트 흐름**:
  1. `POST /api/ai/chat/sessions`: 새 빈 대화 세션을 생성하고 `sessionId`를 발급받습니다. (세션 제목 `title`은 첫 발화 시 서버가 자동 생성하므로 생성 직후에는 `null`).
  2. `GET /api/ai/chat/sessions`: 사용자의 대화 세션 목록을 최근 활동순으로 조회합니다. (사이드바 렌더링용, `lastMessage` 포함).
  3. `GET /api/ai/chat/sessions/{sessionId}`: 특정 대화 세션의 전체 메시지 이력을 시간순으로 조회하여 채팅방 UI를 복원합니다.
  4. `POST /api/ai/chat/messages`: 발화 전송 시 요청 Body에 해당 `sessionId`를 필수로 포함합니다.

---

## 2. 🌡️ 협업 온도 정책 변경: 평가 건수 무관 상시 노출

* **평가 건수 무관 상시 노출**: 기존(v8)의 평가 2건 미만 시 `null`(비공개) 처리 정책이 제거되어, **평가 건수(0건 또는 1건)와 무관하게 온도가 항상 숫자로 노출**됩니다.
* **신규/0건 사용자 표기**: 아직 평가를 받지 않은 사용자도 기본 기준점인 `36.5`도로 표기됩니다.
* **적용 응답 DTO**:
  - `GET/PUT /api/users/me` (`UserResponse`): `collaborationTemperature`가 항상 제공됩니다.
  - `GET /api/users/{userId}` (`UserProfileResponse`): 타인 프로필에서도 온도가 항상 제공됩니다.
  - `GET /api/teams/{teamId}` (`TeamDetailResponseDTO`): `leaderCollaborationTemperature`가 항상 제공됩니다.
* **온도 미포함 API 구분**: 지원서 응답의 `applicant` 객체처럼 온도를 조회하지 않는 경로에서는 `collaborationReviewCount`가 `null`로 내려가며, 이를 통해 "온도를 주지 않는 API"와 "평가를 0건 받은 사용자(`collaborationReviewCount: 0`)"를 구분합니다.

---

## 3. 📝 사용자 프로필 서술형 포트폴리오(`portfolio`) 필드 신설

* **서술형 포트폴리오 추가**: 한 줄 소개(`tagline`, 200자)로 담기 어려운 긴 소개글과 역량을 작성할 수 있는 `portfolio` 필드가 프로필에 추가되었습니다.
* **구분**:
  - PDF AI 요약문(`POST /api/portfolios/summarize`)이나 지원서 링크(`portfolioUrl`)와는 별개인 **사용자 프로필 자체의 텍스트 필드**입니다.
* **반영 DTO**:
  - 프로필 수정 요청(`UserUpdateRequest`): `portfolio` 필드 지원 (최대 5,000자).
  - 내 프로필(`UserResponse`) 및 타인 프로필(`UserProfileResponse`): `portfolio` 필드 지원 (미작성 시 `null`).

---

## 4. 👥 팀 상세 조회 응답 확정 팀원 명단(`members`) 추가

* **확정 팀원 명단 (`members`) 필드 추가**:
  - `GET /api/teams/{teamId}` 응답에 현재 참여 중인 팀원 목록(`members`)이 추가되었습니다.
  - 팀원 객체 항목: `userId`, `name`, `major`, `isLeader` (`boolean`).
  - 팀장을 `isLeader: true`로 포함하며, 크기는 항상 `currentMemberCount`와 동일합니다.
  - 역제안을 수락하여 합류한 팀원도 이 명단에 정상 포함됩니다.
* **응답 JSON 직렬화 키 명칭 요약**:
  - `leader` (`boolean`): 현재 조회자 본인이 해당 팀의 팀장인지 여부 (비로그인 시 `false`).
  - `isEnded` (`boolean`): 팀 활동 종료 여부.
  - `members[].isLeader` (`boolean`): 해당 팀원이 팀장인지 여부.

---

## 5. 🔔 팀 활동 흐름 내 누락 알림 5종 추가

팀 활동 진행 시 아래 5가지 상황에서 실시간 알림이 추가로 발송됩니다.

1. **팀 지원 접수**: 사용자가 팀에 지원(`POST /api/teams/{teamId}/apply`) 시 → 팀장에게 `"지원서 도착"` 알림 발송.
2. **팀 지원 취소**: 지원자가 지원을 취소(`DELETE /api/teams/applications/{applicationId}`) 시 → 팀장에게 `"지원 취소"` 알림 발송.
3. **역제안 취소**: 팀장이 역제안을 회수(`DELETE /api/teams/offers/{offerId}`) 시 → 대상 사용자에게 `"제안 취소"` 알림 발송.
4. **팀 삭제**: 팀장이 팀을 삭제(`DELETE /api/teams/{teamId}`) 시 → 팀원 및 대기 중인 지원자/제안자 전원에게 `"팀 삭제"` 알림 발송 (삭제한 팀장 본인 제외).
5. **활동 자동 종료**: 마감일 경과로 1인 팀이 자동 종료될 때 → 팀장에게 `"활동 자동 종료"` 알림 발송 (팀장 수동 종료 시에는 발송 생략).

---

## 6. 🪣 서버 저장 공간 부족 에러(507) 도입

* **`STORAGE_QUOTA_EXCEEDED` (HTTP 507 Insufficient Storage)**:
  - 파일 개별 용량 초과(`413 Payload Too Large`)와 구분되어, 서버 전체의 저장소 용량이 가득 찼을 때 반환되는 에러입니다.
  - 발생 가능 경로: 프로필 이미지 업로드(`POST /api/users/me/profile-image`), 공모전 포스터 초안 추출(`POST /api/events/extract-image`).
  - 클라이언트 안내: 사용자가 파일 크기를 줄여도 해결되지 않으므로, "저장 공간이 가득 차 파일을 업로드할 수 없습니다. 잠시 후 다시 시도하거나 관리자에게 문의해주세요." 류의 시스템 안내를 표시합니다.

---

## 7. 📡 실시간 알림(SSE) 수명주기 안내

* **정상 연결 만료 및 재연결**: 실시간 알림 SSE 구독(`GET /api/notifications/subscribe`)은 서버 정책에 따라 일정 시간(기본 1시간) 후 연결이 정상 종료될 수 있습니다.
* **클라이언트 처리**: 브라우저 `EventSource` 또는 앱 SSE 클라이언트의 기본 재연결 메커니즘을 통해 자동으로 다시 구독을 요청하면 되며, 연결이 끊겨 있던 동안 수신된 알림은 `GET /api/notifications`로 조회하여 채웁니다.

---

## 8. 🛠️ 신규 및 주요 ErrorCode 정리 (FE 에러 처리용)

| ErrorCode | HTTP Status | 에러 메시지 / 발생 조건 |
|---|---|---|
| `AI_CHAT_SESSION_NOT_FOUND` | `404 Not Found` | 대화를 찾을 수 없습니다. (존재하지 않거나 타인의 대화 세션 ID 접근 시) |
| `STORAGE_QUOTA_EXCEEDED` | `507 Insufficient Storage` | 저장 공간이 가득 차 파일을 업로드할 수 없습니다. 잠시 후 다시 시도하거나 관리자에게 문의해주세요. |
| `FILE_TOO_LARGE` | `413 Payload Too Large` | 업로드 가능한 파일 크기를 초과했습니다. (서버 멀티파트 통합 용량 한도 초과 시) |
| `IMAGE_TOO_LARGE` | `413 Payload Too Large` | 이미지는 10MB 이하만 업로드할 수 있습니다. (프로필 사진, 포스터 이미지) |
| `PDF_TOO_LARGE` | `413 Payload Too Large` | 포트폴리오 PDF는 20MB 이하만 업로드할 수 있습니다. |
| `INVALID_IMAGE_FILE` | `400 Bad Request` | jpg, jpeg, png 형식의 이미지 파일만 업로드할 수 있습니다. |
| `INVALID_PDF_FILE` | `400 Bad Request` | pdf 형식의 파일만 업로드할 수 있습니다. |
"""))
          .components(new Components()
            .addSecuritySchemes(BEARER_SCHEME,
              new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("로그인 응답의 accessToken. `Bearer ` 접두어는 UI 가 붙인다."))
            .addSchemas(ERROR_SCHEMA, errorSchema())
            .addSchemas(AUTH_ERROR_SCHEMA, authErrorSchema()))
          // 기본을 '인증 필요'로 둔다 — SecurityConfig 의 anyRequest().authenticated() 와 같은 방향이다.
          .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }

    /**
     * 실패 응답의 본문. {@code BaseResponse.error(...)} 가 내는 형태 그대로다 —
     * success 는 항상 false 이고 data 는 null 이다(검증 실패만 예외적으로 필드→메시지 맵).
     */
    private Schema<?> errorSchema() {
        return new ObjectSchema()
          .name(ERROR_SCHEMA)
          .description("실패 응답. 성공 응답과 같은 봉투를 쓰고 success 가 false 다.")
          .addProperty("success", new Schema<Boolean>().type("boolean").example(false))
          .addProperty("message", new StringSchema()
            .description("사용자에게 그대로 보여줄 수 있는 한국어 문구 (ErrorCode 의 메시지)")
            .example(ErrorCode.INVALID_INPUT.getMessage()))
          .addProperty("data", new Schema<>()
            .nullable(true)
            .description("보통 null. 입력값 검증 실패(400)일 때만 필드→메시지 맵이 실린다."));
    }

    /**
     * 인증 실패(403) 본문. 스프링 시큐리티가 직접 내는 응답이라 우리 봉투를 쓰지 않는다.
     *
     * <p>
     * 여기만 형식이 다른 건 예외 처리 흐름이 달라서다 — GlobalExceptionHandler 는 핸들러까지
     * 도달한 요청만 다루는데, 인증 거부는 그 앞의 필터 체인에서 끝난다.
     */
    private Schema<?> authErrorSchema() {
        return new ObjectSchema()
          .name(AUTH_ERROR_SCHEMA)
          .description("인증 실패 응답. 공통 봉투를 쓰지 않는 유일한 형식이다.")
          .addProperty("timestamp", new StringSchema().example("2026-08-13T12:47:47.653Z"))
          .addProperty("status", new Schema<Integer>().type("integer").example(403))
          .addProperty("error", new StringSchema().example("Forbidden"))
          .addProperty("path", new StringSchema().example("/api/users/me"));
    }

    /**
     * 모든 엔드포인트가 똑같이 낼 수 있는 실패 응답을 자동으로 붙인다.
     *
     * <p>
     * 손으로 달지 않는 이유는 분량이다. 401·500 은 65개 엔드포인트 전부에 해당하는데, 그걸
     * 어노테이션으로 반복하면 새 엔드포인트가 생길 때마다 빠뜨리고 문구도 갈라진다. 여기서
     * 한 번 정하면 앞으로 추가되는 엔드포인트에도 저절로 붙는다.
     *
     * <p>
     * 엔드포인트 고유의 실패(FORBIDDEN_ACCESS, SCHOOL_NOT_VERIFIED 등)는 그 메서드에서만
     * 의미가 있으므로 각 컨트롤러의 {@code @ApiResponse} 가 담당한다. 이미 선언된 상태코드는
     * 여기서 덮어쓰지 않는다 — 도메인 문구가 항상 더 구체적이다.
     */
    @Bean
    public OperationCustomizer commonErrorResponsesCustomizer() {
        return (operation, handlerMethod) -> {
            ApiResponses responses = operation.getResponses();
            if (responses == null) {
                responses = new ApiResponses();
                operation.setResponses(responses);
            }

            if (requiresAuth(handlerMethod)) {
                // 401 이 아니라 403 이다. JwtAuthenticationFilter 는 토큰이 없거나 유효하지 않으면
                // 아무것도 하지 않고 통과시키고, 그 뒤 스프링 시큐리티가 익명 요청을 거부한다.
                // AuthenticationEntryPoint 를 따로 두지 않아 그 거부가 403 으로 나간다.
                // 실제 동작을 그대로 적는다 — 문서가 401 이라고 하면 프론트가 오지 않는 상태코드를
                // 기다리게 된다.
                putIfAbsent(responses, "403", authErrorContent(), """
                        토큰이 없거나, 만료됐거나, 형식이 잘못됐다. 셋을 구분하지 않고 모두 403 이다.

                        **본문이 공통 봉투(`{ success, message, data }`)가 아니다** — 스프링 시큐리티가
                        직접 내는 응답이라 아래 형식이며, 이 경우만 예외다.

                        우측 상단 Authorize 에 로그인 응답의 accessToken 을 넣고 다시 호출한다.""");
            } else {
                // 전역 요구사항을 이 오퍼레이션에서만 해제한다. OpenAPI 에서 빈 배열은
                // "인증 없이 호출 가능"이라는 뜻이고, UI 는 이때만 자물쇠를 뗀다.
                //
                // 애초에 @SecurityRequirement(name = "") 하나로 될 거라고 본 게 오해였다 —
                // springdoc 은 이름이 빈 요구사항을 버릴 뿐 빈 배열을 만들어 주지 않는다.
                // 그래서 그 어노테이션이 붙은 엔드포인트도 문서에서는 계속 인증 필요로 보였다.
                // 어노테이션은 '이 엔드포인트는 비로그인 허용'이라는 표시로 남기고, 실제
                // 스펙 반영은 여기서 한다.
                operation.setSecurity(new ArrayList<>());
            }

            if (hasValidatedBody(handlerMethod)) {
                putIfAbsent(responses, "400", """
                        입력값 검증에 실패했다. data 에 필드명→사유 맵이 실린다.
                        예: `{ "email": "올바른 이메일 형식이 아닙니다" }`
                        본문 JSON 자체를 읽지 못한 경우(enum 오타, 날짜 형식 등)도 같은 형태다.""");
            }

            if (isMultipart(operation)) {
                putIfAbsent(responses, "413", ErrorCode.FILE_TOO_LARGE.name() + " — "
                  + ErrorCode.FILE_TOO_LARGE.getMessage()
                  + " (멀티파트 컨테이너 한도. 도메인별 한도는 각 엔드포인트 설명 참고)");
            }

            putIfAbsent(responses, "500", ErrorCode.INTERNAL_SERVER_ERROR.name() + " — "
              + ErrorCode.INTERNAL_SERVER_ERROR.getMessage());

            attachErrorSchema(responses);
            return operation;
        };
    }

    /**
     * 이 핸들러가 토큰을 요구하는지.
     *
     * <p>
     * {@code operation.getSecurity()} 대신 핸들러의 어노테이션을 직접 읽는다. 전역 요구사항은
     * OpenAPI 최상위에 있고 오퍼레이션에는 비어 있는 게 정상이라, operation 만 봐서는
     * "인증 불필요라고 선언한 것"과 "아무 말도 안 해서 전역 기본을 따르는 것"을 구분할 수 없다.
     * 비로그인 허용은 {@code @SecurityRequirement(name = "")} 로 표시하기로 했으므로(TeamController
     * 참고) 빈 이름이 붙어 있는지만 보면 된다.
     */
    private boolean requiresAuth(HandlerMethod handlerMethod) {
        var declared = AnnotatedElementUtils.findMergedAnnotation(
          handlerMethod.getMethod(), io.swagger.v3.oas.annotations.security.SecurityRequirement.class);
        return declared == null || !declared.name().isBlank();
    }

    /**
     * {@code @Valid @RequestBody} 를 받는지 — GlobalExceptionHandler 의 400 이 여기서만 나온다.
     */
    private boolean hasValidatedBody(HandlerMethod handlerMethod) {
        return Arrays.stream(handlerMethod.getMethod().getParameters())
          .anyMatch(this::isValidatedBody);
    }

    private boolean isValidatedBody(Parameter parameter) {
        return parameter.isAnnotationPresent(RequestBody.class)
          && parameter.isAnnotationPresent(Valid.class);
    }

    /**
     * 파일 업로드인지. {@link RequestMapping#consumes()} 가 멀티파트로 못박힌 경우만 해당한다.
     */
    private boolean isMultipart(Operation operation) {
        io.swagger.v3.oas.models.parameters.RequestBody body = operation.getRequestBody();
        if (body == null || body.getContent() == null) {
            return false;
        }
        return body.getContent().containsKey(org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE);
    }

    /**
     * 각 컨트롤러가 선언한 실패 응답에 본문 스키마를 채워 준다.
     *
     * <p>
     * {@code @ApiResponse(responseCode = "400", description = ...)} 만 적으면 springdoc 이
     * 스키마 없는 와일드카드 미디어타입 응답을 만든다. 그러면 UI 의 Responses 표에 설명만 뜨고
     * 실제로 어떤 JSON 이 오는지는 안 보여, 커스터마이저가 붙인 401/500 과 생김새도 갈린다.
     * 실패 응답의 본문은 전부 같은 봉투이므로 여기서 한 번에 맞춘다.
     */
    private void attachErrorSchema(ApiResponses responses) {
        responses.forEach((status, response) -> {
            if (status.startsWith("2") || alreadyErrorShaped(response)) {
                return;
            }
            response.setContent(errorContent());
        });
    }

    /**
     * 이 응답이 이미 실패 본문을 가리키고 있는지.
     *
     * <p>
     * "스키마가 있으면 그대로 둔다"로는 부족했다. 컨트롤러가 {@code @ApiResponse} 에 본문을 적지
     * 않으면 springdoc 이 <b>성공 응답의 스키마를 대신 넣기</b> 때문이다 — 그러면 404 자리에
     * {@code ApiResponseUserResponse} 가 붙어 "실패인데 data 에 유저가 들어 있다"는 거짓말이 된다.
     * 우리가 붙인 두 스키마만 통과시키고 나머지는 덮어쓴다.
     */
    private boolean alreadyErrorShaped(ApiResponse response) {
        Content content = response.getContent();
        if (content == null) {
            return false;
        }
        return content.values().stream()
          .map(MediaType::getSchema)
          .filter(java.util.Objects::nonNull)
          .map(Schema::get$ref)
          .anyMatch(ref -> ERROR_SCHEMA_REF.equals(ref) || AUTH_ERROR_SCHEMA_REF.equals(ref));
    }

    private Content errorContent() {
        return contentOf(ERROR_SCHEMA_REF);
    }

    private Content authErrorContent() {
        return contentOf(AUTH_ERROR_SCHEMA_REF);
    }

    private Content contentOf(String schemaRef) {
        return new Content().addMediaType(
          org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
          new MediaType().schema(new Schema<>().$ref(schemaRef)));
    }

    /**
     * 이미 선언된 상태코드는 건드리지 않는다.
     *
     * <p>
     * OpenAPI 는 상태코드당 응답이 하나뿐이라, 덮어쓰면 컨트롤러가 적어 둔 도메인 에러
     * 설명이 조용히 사라진다.
     */
    private void putIfAbsent(ApiResponses responses, String status, String description) {
        putIfAbsent(responses, status, errorContent(), description);
    }

    private void putIfAbsent(ApiResponses responses, String status, Content content, String description) {
        if (responses.containsKey(status)) {
            return;
        }
        responses.addApiResponse(status, new ApiResponse()
          .description(description)
          .content(content));
    }
}
