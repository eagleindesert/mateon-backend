package com.example.mateon.common.exception;

import com.example.mateon.common.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 전역 예외 처리의 응답 계약을 고정한다.
 *
 * <p>이 클래스가 제일 먼저 필요한 이유는, 나머지 모든 컨트롤러 테스트가 여기서 나오는 상태코드를
 * 그대로 단언하기 때문이다. 그런데 이 프로젝트의 상태코드 매핑은 직관과 어긋나는 지점이 많다 —
 * {@code FORBIDDEN_ACCESS} 가 403 이 아니라 400 이고, {@code RESOURCE_NOT_FOUND} 도 404 가
 * 아니라 400 이며, 로그인 실패({@code INVALID_CREDENTIALS})조차 401 이 아니라 400 이다.
 * 이건 실수가 아니라 {@link ErrorCode} 가 상태를 지정하지 않으면 400 을 기본값으로 쓰기 때문인데,
 * 모르는 사람이 "버그"로 보고 고치면 이미 그 코드로 동작 중인 프론트가 통째로 깨진다.
 *
 * <p>또 하나 고정할 것은 <b>봉투의 통일성</b>이다. {@code @Valid} 실패, 본문 파싱 실패,
 * 쿼리 파라미터 타입 불일치는 원인이 전혀 다른데도 프론트가 400 을 한 가지 방식으로 처리할 수
 * 있도록 같은 형태({@code message} 고정 + {@code data} 에 필드별 메시지 맵)로 나가야 한다.
 *
 * <p>실제 HTTP 를 거쳐야 의미가 있는 것들(본문 파싱은 진짜 Jackson 컨버터가 만들어야 한다)은
 * MockMvc 로, 응답을 만들지 않는 것이 요점인 핸들러는 직접 호출로 검증한다.
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ProbeController())
                .setControllerAdvice(handler)
                .build();
    }

    @Nested
    @DisplayName("MateonException 은 ErrorCode 가 정한 상태코드로 나간다")
    class MateonExceptionMapping {

        @Test
        @DisplayName("상태를 지정하지 않은 코드는 400 이다 (INVALID_INPUT)")
        void defaultsToBadRequest() throws Exception {
            mockMvc.perform(get("/probe/mateon/INVALID_INPUT"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_INPUT.getMessage()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("USER_NOT_FOUND 는 404 다 — 400 이면 '요청이 잘못됐다'로 읽혀 오해를 부른다")
        void userNotFoundIs404() throws Exception {
            mockMvc.perform(get("/probe/mateon/USER_NOT_FOUND"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("EMAIL_REQUEST_TOO_FREQUENT 는 429 다 (프론트가 재시도 안내를 띄울 근거)")
        void tooFrequentIs429() throws Exception {
            mockMvc.perform(get("/probe/mateon/EMAIL_REQUEST_TOO_FREQUENT"))
                    .andExpect(status().isTooManyRequests());
        }

        @Test
        @DisplayName("IMAGE_TOO_LARGE 는 413 이다")
        void imageTooLargeIs413() throws Exception {
            mockMvc.perform(get("/probe/mateon/IMAGE_TOO_LARGE"))
                    .andExpect(status().is(413));
        }

        @Test
        @DisplayName("STORAGE_QUOTA_EXCEEDED 는 507 이다 — 413 과 달리 사용자가 할 수 있는 일이 없다")
        void storageQuotaIs507() throws Exception {
            mockMvc.perform(get("/probe/mateon/STORAGE_QUOTA_EXCEEDED"))
                    .andExpect(status().isInsufficientStorage());
        }

        @Test
        @DisplayName("AI 서버 실패는 502, 연결 불가는 503 으로 갈린다 — 프론트 재시도 판단 근거다")
        void aiServerFailuresAreSplit() throws Exception {
            mockMvc.perform(get("/probe/mateon/AI_SERVER_ERROR"))
                    .andExpect(status().isBadGateway());

            mockMvc.perform(get("/probe/mateon/AI_SERVER_UNAVAILABLE"))
                    .andExpect(status().isServiceUnavailable());
        }

        @Test
        @DisplayName("FORBIDDEN_ACCESS 는 403 이 아니라 400 이다 (기본값을 그대로 쓴다)")
        void forbiddenAccessIs400() throws Exception {
            mockMvc.perform(get("/probe/mateon/FORBIDDEN_ACCESS"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("RESOURCE_NOT_FOUND 는 404 가 아니라 400 이다 — 그래서 EVENT_NOT_FOUND 가 따로 있다")
        void resourceNotFoundIs400() throws Exception {
            mockMvc.perform(get("/probe/mateon/RESOURCE_NOT_FOUND"))
                    .andExpect(status().isBadRequest());

            mockMvc.perform(get("/probe/mateon/EVENT_NOT_FOUND"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("커스텀 메시지를 준 경우 ErrorCode 기본 문구가 아니라 그 메시지가 나간다")
        void customMessageWins() throws Exception {
            mockMvc.perform(get("/probe/mateon-custom"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("메시지 내용이 비어 있습니다."));
        }
    }

    @Nested
    @DisplayName("400 계열 세 가지가 같은 봉투를 쓴다")
    class UniformBadRequestEnvelope {

        @Test
        @DisplayName("@Valid 실패는 data 에 필드별 메시지를 담는다")
        void validationFailure() throws Exception {
            mockMvc.perform(post("/probe/valid")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value("입력값 검증에 실패했습니다."))
                    .andExpect(jsonPath("$.data.name").value("이름은 필수입니다."));
        }

        @Test
        @DisplayName("본문의 enum 오타도 같은 봉투로 나간다 — 없으면 500 '서버 오류' 로 오인된다")
        void unreadableBodyUsesSameEnvelope() throws Exception {
            mockMvc.perform(post("/probe/body")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"kind\":\"FOO\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value("입력값 검증에 실패했습니다."))
                    .andExpect(jsonPath("$.data.kind")
                            .value("'FOO' 는 허용되지 않는 값입니다. 가능한 값: ALPHA, BETA"));
        }

        @Test
        @DisplayName("쿼리 파라미터 enum 오타도 같은 봉투 + 허용값 안내를 준다")
        void typeMismatchOnEnumParam() throws Exception {
            mockMvc.perform(get("/probe/enum-param").param("kind", "FOO"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("입력값 검증에 실패했습니다."))
                    .andExpect(jsonPath("$.data.kind")
                            .value("'FOO' 는 허용되지 않는 값입니다. 가능한 값: ALPHA, BETA"));
        }

        @Test
        @DisplayName("enum 이 아닌 타입 불일치는 허용값 없이 형식 안내만 준다")
        void typeMismatchOnNonEnum() throws Exception {
            mockMvc.perform(get("/probe/long-path/abc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.data.id").value("'abc' 는 형식이 올바르지 않습니다."));
        }

        @Test
        @DisplayName("깨진 JSON 은 필드를 특정할 수 없어 data 가 빈 맵이다 (그래도 400 봉투는 유지)")
        void malformedJsonHasEmptyData() throws Exception {
            mockMvc.perform(post("/probe/body")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{not json"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("입력값 검증에 실패했습니다."))
                    .andExpect(jsonPath("$.data").isMap())
                    .andExpect(jsonPath("$.data").isEmpty());
        }
    }

    @Nested
    @DisplayName("그 밖의 핸들러")
    class Others {

        @Test
        @DisplayName("IllegalArgumentException 은 400 이고 예외 메시지를 그대로 쓴다")
        void illegalArgument() throws Exception {
            mockMvc.perform(get("/probe/illegal-argument"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("이미 처리된 지원서입니다"));
        }

        @Test
        @DisplayName("BadCredentialsException 은 401 이되 메시지는 예외가 아니라 ErrorCode 것을 쓴다")
        void badCredentialsHidesInternalMessage() throws Exception {
            mockMvc.perform(get("/probe/bad-credentials"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_CREDENTIALS.getMessage()))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("UserDetails"))));
        }

        @Test
        @DisplayName("필수 파일 파트가 없으면 400 과 파트 이름이 담긴 안내를 준다")
        void missingPart() throws Exception {
            mockMvc.perform(multipart("/probe/upload")
                            .file(new MockMultipartFile("wrong_name", "a.pdf", "application/pdf", new byte[]{1})))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("'pdf_file' 파일이 필요합니다."));
        }

        @Test
        @DisplayName("멀티파트 컨테이너 한도 초과는 413 이고 도메인 중립 문구를 쓴다")
        void maxUploadSize() throws Exception {
            mockMvc.perform(get("/probe/too-large"))
                    .andExpect(status().is(413))
                    .andExpect(jsonPath("$.message").value(ErrorCode.FILE_TOO_LARGE.getMessage()));
        }

        @Test
        @DisplayName("예상 못 한 예외는 500 이되 원문이 새지 않는다 — 스택트레이스가 응답에 실리면 안 된다")
        void catchAllDoesNotLeak() throws Exception {
            mockMvc.perform(get("/probe/boom"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message").value(ErrorCode.INTERNAL_SERVER_ERROR.getMessage()))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("jdbc:postgresql"))));
        }

        @Test
        @DisplayName("클라이언트가 끊은 async 요청은 응답을 만들지 않는다 (본문 없음)")
        void asyncRequestNotUsableProducesNoBody() throws Exception {
            mockMvc.perform(get("/probe/async-gone"))
                    .andExpect(content().string(""));
        }
    }

    @Nested
    @DisplayName("handleNotWritable: 연결 끊김과 진짜 직렬화 버그를 가른다")
    class NotWritable {

        @Test
        @DisplayName("원인 체인에 ClientAbortException 이 있으면 응답을 만들지 않는다 (null)")
        void clientAbortReturnsNull() {
            HttpMessageNotWritableException e = new HttpMessageNotWritableException(
                    "쓰기 실패", new ClientAbortException("broken pipe"));

            assertThat(handler.handleNotWritable(e)).isNull();
        }

        @Test
        @DisplayName("원인이 한 단계 더 깊어도 찾아낸다 — 체인 전체를 훑기 때문이다")
        void findsCauseDeeperInChain() {
            HttpMessageNotWritableException e = new HttpMessageNotWritableException(
                    "쓰기 실패",
                    new IllegalStateException("wrapped", new AsyncRequestNotUsableException("연결 종료")));

            assertThat(handler.handleNotWritable(e)).isNull();
        }

        @Test
        @DisplayName("연결 끊김이 아닌 직렬화 실패는 진짜 서버 버그라 500 을 낸다")
        void realSerializationFailureIs500() {
            HttpMessageNotWritableException e = new HttpMessageNotWritableException(
                    "순환 참조", new IllegalStateException("cycle"));

            ResponseEntity<ApiResponse<Object>> response = handler.handleNotWritable(e);

            assertThat(response).isNotNull();
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody().getMessage())
                    .isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // 예외를 던지기만 하는 시험용 컨트롤러. 실제 도메인 컨트롤러를 쓰면 그쪽 변경에
    // 에러 계약 테스트가 끌려다니므로 여기서만 쓰는 최소 컨트롤러를 둔다.
    // -------------------------------------------------------------------------
    enum Kind {ALPHA, BETA}

    record BodyDto(Kind kind) {
    }

    record ValidDto(@NotBlank(message = "이름은 필수입니다.") String name) {
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe/mateon/{code}")
        void mateon(@PathVariable String code) {
            throw new MateonException(ErrorCode.valueOf(code));
        }

        @GetMapping("/probe/mateon-custom")
        void mateonCustom() {
            throw new MateonException(ErrorCode.INVALID_INPUT, "메시지 내용이 비어 있습니다.");
        }

        @PostMapping("/probe/valid")
        void valid(@Valid @RequestBody ValidDto dto) {
        }

        @PostMapping("/probe/body")
        void body(@RequestBody BodyDto dto) {
        }

        @GetMapping("/probe/enum-param")
        void enumParam(@RequestParam Kind kind) {
        }

        @GetMapping("/probe/long-path/{id}")
        void longPath(@PathVariable Long id) {
        }

        @GetMapping("/probe/illegal-argument")
        void illegalArgument() {
            throw new IllegalArgumentException("이미 처리된 지원서입니다");
        }

        @GetMapping("/probe/bad-credentials")
        void badCredentials() {
            throw new BadCredentialsException("UserDetails 조회 실패: 내부 구현 노출");
        }

        @PostMapping("/probe/upload")
        void upload(@RequestPart("pdf_file") MultipartFile file) {
        }

        @GetMapping("/probe/too-large")
        void tooLarge() {
            throw new org.springframework.web.multipart.MaxUploadSizeExceededException(10L);
        }

        @GetMapping("/probe/boom")
        void boom() {
            throw new RuntimeException("jdbc:postgresql://prod-db:5432 접속 실패 (password=hunter2)");
        }

        @GetMapping("/probe/async-gone")
        void asyncGone() throws IOException {
            throw new AsyncRequestNotUsableException("클라이언트가 연결을 끊었습니다");
        }
    }
}
