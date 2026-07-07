    package com.example.mateon.common.exception;

    import lombok.Getter;

    @Getter
    public enum ErrorCode {
        // 인증 관련
        EMAIL_ALREADY_EXISTS("이미 사용 중인 이메일입니다."),
        INVALID_EMAIL_DOMAIN("단국대학교 이메일(@dankook.ac.kr)만 사용 가능합니다."),
        EMAIL_NOT_VERIFIED("이메일 인증이 완료되지 않았습니다."),
        INVALID_VERIFICATION_CODE("인증코드가 올바르지 않거나 만료되었습니다."),
        INVALID_CREDENTIALS("이메일 또는 비밀번호가 올바르지 않습니다."),

        // 토큰 관련
        TOKEN_EXPIRED("토큰이 만료되었습니다."),
        INVALID_TOKEN("유효하지 않은 토큰입니다."),
        TOKEN_NOT_FOUND("리프레시 토큰을 찾을 수 없습니다."),

        // 사용자 관련
        USER_NOT_FOUND("사용자를 찾을 수 없습니다."),
        PASSWORD_MISMATCH("비밀번호가 일치하지 않습니다."),
        INVALID_PASSWORD_FORMAT("비밀번호는 10-20자의 영문과 숫자 조합이어야 합니다."),
        // 팀/지원/공통 관련 ---
        RESOURCE_NOT_FOUND("요청한 정보를 찾을 수 없습니다."),       // 팀, 활동, 지원서 등이 DB에 없을 때
        FORBIDDEN_ACCESS("해당 자원에 대한 접근 권한이 없습니다."),   // 남의 팀 수정 시도 등
        DUPLICATE_RESOURCE("이미 처리된 내역이 존재합니다."),         // 중복 지원 방지
        INVALID_INPUT("잘못된 입력값입니다."),

        // 기타
        INTERNAL_SERVER_ERROR("서버 오류가 발생했습니다."),
        BAD_REQUEST("잘못된 요청입니다."),
        UNAUTHORIZED("인증이 필요합니다.");

        private final String message;

        ErrorCode(String message) {
            this.message = message;
        }
    }

