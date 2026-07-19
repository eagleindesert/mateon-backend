    package com.example.mateon.common.exception;

    import lombok.Getter;
    import org.springframework.http.HttpStatus;

    @Getter
    public enum ErrorCode {
        // 인증 관련
        EMAIL_ALREADY_EXISTS("이미 사용 중인 이메일입니다."),
        INVALID_EMAIL_DOMAIN("교육기관 이메일(.ac.kr)만 사용 가능합니다."),
        EMAIL_NOT_VERIFIED("이메일 인증이 완료되지 않았습니다."),
        INVALID_VERIFICATION_TOKEN("이메일 인증 정보가 유효하지 않습니다. 인증을 다시 진행해주세요."),
        EMAIL_REQUEST_TOO_FREQUENT(HttpStatus.TOO_MANY_REQUESTS, "인증코드 요청은 잠시 후 다시 시도해주세요."),
        INVALID_VERIFICATION_CODE("인증코드가 올바르지 않거나 만료되었습니다."),
        INVALID_CREDENTIALS("이메일 또는 비밀번호가 올바르지 않습니다."),
        SCHOOL_EMAIL_ALREADY_USED("이미 다른 계정에서 사용 중인 학교 이메일입니다."),
        SCHOOL_NOT_VERIFIED("학교 인증이 필요한 기능입니다."),
        KAKAO_AUTH_FAILED("카카오 인증에 실패했습니다."),

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

        // 협업 온도/팀원 평가 관련 ---
        // 평가는 "종료된 팀"에서 "기간 안에" "팀원끼리"만 가능하다. 프론트가 각 실패를 구분해
        // 안내 문구를 다르게 띄울 수 있어야 해서 코드를 쪼갠다.
        TEAM_ALREADY_ENDED("이미 종료된 팀입니다."),
        TEAM_NOT_ENDED("아직 종료되지 않은 팀입니다. 활동 종료 후 평가할 수 있습니다."),
        REVIEW_PERIOD_EXPIRED("평가 기간이 종료되었습니다."),
        NOT_TEAM_MEMBER("해당 팀의 팀원이 아닙니다."),
        CANNOT_REVIEW_SELF("자기 자신은 평가할 수 없습니다."),
        ALREADY_REVIEWED("이미 평가를 제출했습니다."),
        INVALID_RATING("평가 점수는 1~5 사이여야 합니다."),

        // 매칭/추천 관련 ---
        // 추천은 의도 추출이 끝나야(user_embeddings + matching_intent_slots) 가능하다.
        // "아직 준비가 안 됐다"이지 "없다"가 아니라 404 가 아닌 400 으로 준다.
        MATCHING_INTENT_REQUIRED("먼저 매칭 의도 추출을 완료해주세요."),

        // AI 서버 관련 --- (별도 FastAPI 서버. 의도 추출/임베딩)
        // 둘을 가르는 이유: 프론트가 재시도 여부를 판단할 수 있어야 한다.
        AI_SERVER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI 서버에 연결할 수 없습니다. 잠시 후 다시 시도해주세요."),
        AI_SERVER_ERROR(HttpStatus.BAD_GATEWAY, "AI 서버 응답 처리에 실패했습니다."),

        // 채팅 관련 ---
        CHAT_ROOM_NOT_FOUND("채팅방을 찾을 수 없습니다."),
        NOT_ROOM_MEMBER("해당 채팅방의 참여자가 아닙니다."),
        CANNOT_CHAT_WITH_SELF("자기 자신과는 채팅할 수 없습니다."),

        // 기타
        INTERNAL_SERVER_ERROR("서버 오류가 발생했습니다."),
        BAD_REQUEST("잘못된 요청입니다."),
        UNAUTHORIZED("인증이 필요합니다.");

        private final HttpStatus status;
        private final String message;

        // 상태코드 미지정 시 기본 400 (기존 코드값 동작 유지)
        ErrorCode(String message) {
            this(HttpStatus.BAD_REQUEST, message);
        }

        ErrorCode(HttpStatus status, String message) {
            this.status = status;
            this.message = message;
        }
    }

