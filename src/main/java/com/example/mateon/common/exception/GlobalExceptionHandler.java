package com.example.mateon.common.exception;

import com.example.mateon.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import tools.jackson.databind.exc.InvalidFormatException;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MateonException.class)
    public ResponseEntity<ApiResponse<Object>> handleMateonException(MateonException e) {
        return ResponseEntity
          .status(e.getErrorCode().getStatus())
          .body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Object>> handleIllegalArgumentException(IllegalArgumentException e) {
        return ResponseEntity
          .status(HttpStatus.BAD_REQUEST)
          .body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Object>> handleBadCredentialsException(BadCredentialsException e) {
        return ResponseEntity
          .status(HttpStatus.UNAUTHORIZED)
          .body(ApiResponse.error(ErrorCode.INVALID_CREDENTIALS.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationException(MethodArgumentNotValidException e) {
        Map<String, String> errors = new HashMap<>();
        e.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        return ResponseEntity
          .status(HttpStatus.BAD_REQUEST)
          .body(ApiResponse.error("입력값 검증에 실패했습니다.", errors));
    }

    /**
     * 요청 본문(JSON)을 객체로 읽지 못했다. enum 에 없는 값, 잘못된 날짜 형식, 깨진 JSON 등
     * 대부분 클라이언트 입력 문제이므로 400 으로 돌려준다.
     *
     * <p>
     * 이 핸들러가 없으면 아래 catch-all 로 떨어져 500 "서버 오류가 발생했습니다" 가 나간다.
     * 그러면 프론트는 자기 요청이 잘못됐다는 걸 알 수 없고, 서버 장애로 오인하게 된다.
     * 응답 형태는 @Valid 실패(handleValidationException)와 동일한 필드별 메시지 맵이라
     * 클라이언트가 400 을 한 가지 방식으로 처리할 수 있다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleNotReadable(HttpMessageNotReadableException e) {
        Map<String, String> errors = new HashMap<>();
        if (e.getCause() instanceof InvalidFormatException cause && !cause.getPath().isEmpty()) {
            String field = cause.getPath().get(cause.getPath().size() - 1).getPropertyName();
            errors.put(field, describeInvalidValue(cause.getTargetType(), cause.getValue()));
        }
        return ResponseEntity
          .status(HttpStatus.BAD_REQUEST)
          .body(ApiResponse.error("입력값 검증에 실패했습니다.", errors));
    }

    /**
     * 쿼리 파라미터/경로 변수를 요구 타입으로 변환하지 못했다.
     * (예: /api/events/search?category=FOO — Category enum 에 없는 값)
     * 본문과 마찬가지로 클라이언트 입력 문제라 400 으로 돌려준다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        Map<String, String> errors = new HashMap<>();
        errors.put(e.getName(), describeInvalidValue(e.getRequiredType(), e.getValue()));
        return ResponseEntity
          .status(HttpStatus.BAD_REQUEST)
          .body(ApiResponse.error("입력값 검증에 실패했습니다.", errors));
    }

    /**
     * 잘못된 값을 사람이 읽을 수 있게 설명한다.
     * enum 이면 허용 값을 함께 알려준다 — 프론트가 오타를 바로 찾을 수 있어야 한다.
     */
    private String describeInvalidValue(Class<?> targetType, Object value) {
        if (targetType != null && targetType.isEnum()) {
            String allowed = Arrays.stream(targetType.getEnumConstants())
                    .map(String::valueOf)
                    .collect(Collectors.joining(", "));
            return String.format("'%s' 는 허용되지 않는 값입니다. 가능한 값: %s", value, allowed);
        }
        return String.format("'%s' 는 형식이 올바르지 않습니다.", value);
    }

    /**
     * 클라이언트가 SSE 같은 비동기 연결을 끊었다. 응답을 받을 대상이 없으므로 아무것도 만들지 않는다.
     *
     * <p>
     * 이 핸들러가 없으면 아래 catch-all 로 떨어지는데, SSE 요청은 응답 Content-Type 이
     * text/event-stream 으로 이미 굳어 있어 ApiResponse(JSON) 를 쓸 컨버터가 없다
     * ("No converter for [ApiResponse] with preset Content-Type 'text/event-stream'").
     * 반환 타입이 void 라 스프링은 '처리 완료, 본문 없음' 으로 간주한다.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException e) {
        // 사용자가 창을 닫은 정상적인 상황이라 스택트레이스를 남길 이유가 없다.
        log.debug("클라이언트 연결 종료로 응답 생략: {}", e.getMessage());
    }

    /**
     * 업로드 파일이 spring.servlet.multipart 제한을 넘었다.
     *
     * <p>이 핸들러가 없으면 catch-all 로 떨어져 500 "서버 오류가 발생했습니다" 가 나가는데,
     * 실제로는 사용자가 더 작은 파일을 고르면 해결되는 문제다.
     *
     * <p>도메인 중립 문구({@link ErrorCode#FILE_TOO_LARGE})를 쓴다. 이 예외는 멀티파트 파싱
     * 단계에서 터져 어느 엔드포인트로 갈 요청이었는지 알 수 없기 때문이다 — 이미지(10MB)와
     * 포트폴리오 PDF(20MB)의 한도가 다른데 한쪽 숫자를 적으면 다른 쪽에서 틀린 안내가 나간다.
     * 도메인별 한도 안내는 각 서비스가 하는 2차 검사(IMAGE_TOO_LARGE / PDF_TOO_LARGE)의 몫이다.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Object>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("업로드 크기 제한 초과: {}", e.getMessage());
        return ResponseEntity
          .status(ErrorCode.FILE_TOO_LARGE.getStatus())
          .body(ApiResponse.error(ErrorCode.FILE_TOO_LARGE.getMessage()));
    }

    /**
     * multipart 요청에 필수 파트가 없다 (예: 파일 필드명을 잘못 쓴 경우).
     * ServletException 계열이라 핸들러가 없으면 catch-all 500 이 되는데, 명백한 요청 오류다.
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Object>> handleMissingPart(MissingServletRequestPartException e) {
        return ResponseEntity
          .status(HttpStatus.BAD_REQUEST)
          .body(ApiResponse.error("'%s' 파일이 필요합니다.".formatted(e.getRequestPartName())));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleException(Exception e) {
        // printStackTrace 는 System.err 로 직접 찍혀 타임스탬프·로그레벨 없이 logback 출력과 섞인다.
        log.error("처리되지 않은 예외", e);
        return ResponseEntity
          .status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR.getMessage()));
    }
}
