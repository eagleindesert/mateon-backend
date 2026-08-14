package com.example.mateon.matching.dto.response;

import com.example.mateon.matching.domain.IntentSessionStatus;
import com.example.mateon.matching.domain.MatchingIntentMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * GET /session 응답. 앱 재실행 시 AI 재호출 없이 대화를 복원하는 용도.
 */
@Schema(description = "진행 중인 의도 추출 대화의 스냅샷. 진행 중인 세션이 없으면 이 객체 대신 data 가 null 이다.")
@Getter
public class IntentSessionResponseDTO {

    private final Long sessionId;
    private final IntentSessionStatus status;
    @Schema(description = "의도 추출 완료 여부. true 여야 추천 API 를 쓸 수 있다.")
    private final boolean completed;
    @Schema(description = "아직 못 채운 항목(snake_case).")
    private final List<String> missingFields;
    private final ExtractedDTO extracted;
    @Schema(description = "지금까지의 대화 전체. 시간순이며 그대로 채팅 화면에 그리면 된다.")
    private final List<MessageDTO> messages;

    public IntentSessionResponseDTO(Long sessionId, IntentSessionStatus status, boolean completed,
                                    List<String> missingFields, ExtractedDTO extracted,
                                    List<MessageDTO> messages) {
        this.sessionId = sessionId;
        this.status = status;
        this.completed = completed;
        this.missingFields = missingFields;
        this.extracted = extracted;
        this.messages = messages;
    }

    /** 대화 한 턴. USER 발화와 AI 의 assistant_message 가 시간순으로 섞여 있다. */
    @Schema(name = "IntentMessage", description = "대화 한 줄")
    @Getter
    public static class MessageDTO {

        @Schema(description = "발화 주체.", allowableValues = {"USER", "ASSISTANT"})
        private final String role;
        private final String message;
        private final LocalDateTime createdAt;

        public MessageDTO(MatchingIntentMessage entity) {
            this.role = entity.getRole().name();
            this.message = entity.getMessage();
            this.createdAt = entity.getCreatedAt();
        }
    }
}
