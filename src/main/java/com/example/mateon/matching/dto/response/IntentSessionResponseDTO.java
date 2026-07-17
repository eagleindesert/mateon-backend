package com.example.mateon.matching.dto.response;

import com.example.mateon.matching.domain.IntentSessionStatus;
import com.example.mateon.matching.domain.MatchingIntentMessage;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * GET /session 응답. 앱 재실행 시 AI 재호출 없이 대화를 복원하는 용도.
 */
@Getter
public class IntentSessionResponseDTO {

    private final Long sessionId;
    private final IntentSessionStatus status;
    private final boolean completed;
    private final List<String> missingFields;
    private final ExtractedDTO extracted;
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
    @Getter
    public static class MessageDTO {

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
