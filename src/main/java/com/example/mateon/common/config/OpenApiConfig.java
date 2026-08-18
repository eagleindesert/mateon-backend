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

    /** 에러 응답 본문의 공통 스키마 이름. 아래 커스터마이저가 $ref 로 가리킨다. */
    private static final String ERROR_SCHEMA = "ErrorResponse";
    private static final String ERROR_SCHEMA_REF = "#/components/schemas/" + ERROR_SCHEMA;

    /** 인증 실패(403) 본문의 스키마 이름. 공통 봉투를 쓰지 않는 유일한 응답이라 따로 둔다. */
    private static final String AUTH_ERROR_SCHEMA = "AuthErrorResponse";
    private static final String AUTH_ERROR_SCHEMA_REF = "#/components/schemas/" + AUTH_ERROR_SCHEMA;

    @Bean
    public OpenAPI mateonOpenAPI() {
        return new OpenAPI()
          .info(new Info()
            .title("Mateon API")
            .version("v8")
            .description("""
                                교내 활동/공모전 팀 매칭 서비스 API.

                                인증이 필요한 엔드포인트는 로그인(`POST /api/auth/login`)으로 받은
                                accessToken 을 우측 상단 Authorize 에 넣으면 그대로 호출해 볼 수 있다.

                                모든 응답은 `{ success, message, data }` 로 감싸인다."""))
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
     * <p>여기만 형식이 다른 건 예외 처리 흐름이 달라서다 — GlobalExceptionHandler 는 핸들러까지
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

    /** {@code @Valid @RequestBody} 를 받는지 — GlobalExceptionHandler 의 400 이 여기서만 나온다. */
    private boolean hasValidatedBody(HandlerMethod handlerMethod) {
        return Arrays.stream(handlerMethod.getMethod().getParameters())
          .anyMatch(this::isValidatedBody);
    }

    private boolean isValidatedBody(Parameter parameter) {
        return parameter.isAnnotationPresent(RequestBody.class)
          && parameter.isAnnotationPresent(Valid.class);
    }

    /** 파일 업로드인지. {@link RequestMapping#consumes()} 가 멀티파트로 못박힌 경우만 해당한다. */
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
     * <p>OpenAPI 는 상태코드당 응답이 하나뿐이라, 덮어쓰면 컨트롤러가 적어 둔 도메인 에러
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
