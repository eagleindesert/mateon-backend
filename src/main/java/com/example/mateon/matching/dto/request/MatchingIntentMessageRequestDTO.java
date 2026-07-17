package com.example.mateon.matching.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * POST /messages 요청.
 *
 * <p>대화 이력은 백엔드가 보관하므로 프론트는 새 답변 한 줄만 보낸다.
 * 세션은 JWT 의 userId 로 찾으므로 sessionId 도 받지 않는다.
 */
@Getter
@Setter
public class MatchingIntentMessageRequestDTO {

    @NotBlank(message = "메시지는 비어 있을 수 없습니다.")
    @Size(max = 1000, message = "메시지는 1000자를 넘을 수 없습니다.")
    private String message;
}
