package com.example.mateon.common.exception;

import com.example.mateon.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.util.HashMap;
import java.util.Map;

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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleException(Exception e) {
        // printStackTrace 는 System.err 로 직접 찍혀 타임스탬프·로그레벨 없이 logback 출력과 섞인다.
        log.error("처리되지 않은 예외", e);
        return ResponseEntity
          .status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR.getMessage()));
    }
}
