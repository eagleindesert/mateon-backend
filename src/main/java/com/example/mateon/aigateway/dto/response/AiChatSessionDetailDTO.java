package com.example.mateon.aigateway.dto.response;

import com.example.mateon.aichat.domain.AiChatMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 스레드 하나를 통째로 복원한 결과. 사이드바에서 옛 대화를 열 때 쓴다.
 *
 * <p><b>게이트웨이 턴도 함께 내려간다.</b> 도메인 작업에 속하지 않은 되묻기·범위 밖 안내도
 * 사용자 눈에는 자기가 나눈 대화라서, 빼고 그리면 했던 말이 사라진 것처럼 보인다.
 * (도메인 소관만 필요한 곳은 {@code GET /api/matching/intents/session} 이 따로 있다.)
 */
@Schema(name = "AiChatSessionDetail", description = "채팅 스레드 하나의 전체 대화.")
@Getter
public class AiChatSessionDetailDTO {

    private final Long sessionId;

    @Schema(description = "지금까지의 대화 전체. 시간순이며 그대로 채팅 화면에 그리면 된다.")
    private final List<MessageDTO> messages;

    private AiChatSessionDetailDTO(Long sessionId, List<MessageDTO> messages) {
        this.sessionId = sessionId;
        this.messages = messages;
    }

    public static AiChatSessionDetailDTO of(Long sessionId, List<AiChatMessage> messages) {
        return new AiChatSessionDetailDTO(sessionId, messages.stream().map(MessageDTO::new).toList());
    }

    /** 대화 한 줄. USER 발화와 AI 답변이 시간순으로 섞여 있다. */
    @Schema(name = "AiChatMessage", description = "대화 한 줄")
    @Getter
    public static class MessageDTO {

        @Schema(description = "발화 주체.", allowableValues = {"USER", "ASSISTANT"})
        private final String role;

        private final String message;

        @Schema(description = "이 발화가 속한 도메인. 게이트웨이가 혼자 답한 턴이면 null.",
                example = "MATCHING_INTENT")
        private final String domain;

        private final LocalDateTime createdAt;

        MessageDTO(AiChatMessage entity) {
            this.role = entity.getRole().name();
            this.message = entity.getContent();
            this.domain = entity.getTask() == null ? null : entity.getTask().getDomain().name();
            this.createdAt = entity.getCreatedAt();
        }
    }
}
