package com.example.mateon.aigateway.dto.response;

import com.example.mateon.aichat.domain.AiChatSession;
import com.example.mateon.aichat.dto.AiChatSessionSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 사이드바 한 줄. 목록 조회와 스레드 생성이 같은 형태로 내려간다 — 프론트 입장에서는 방금 만든
 * 스레드도 목록에 끼워 넣을 한 줄이라, 모양이 다르면 두 번 다뤄야 한다.
 */
@Schema(name = "AiChatSessionSummary", description = "채팅 스레드 한 줄 (사이드바용).")
@Getter
public class AiChatSessionSummaryDTO {

    @Schema(description = "스레드 id. 발화를 보낼 때 이 값을 실어 보낸다.")
    private final Long sessionId;

    @Schema(description = "첫 사용자 발화에서 만든 제목. 아직 발화가 없으면 null.",
            example = "백엔드 개발자인데 같이 공모전 나갈 팀 찾고 있어요")
    private final String title;

    @Schema(description = "마지막 메시지 미리보기. 발화가 없으면 null.")
    private final String lastMessage;

    @Schema(description = "마지막 활동 시각. 목록은 이 값의 내림차순이다.")
    private final LocalDateTime updatedAt;

    private AiChatSessionSummaryDTO(Long sessionId, String title, String lastMessage,
                                    LocalDateTime updatedAt) {
        this.sessionId = sessionId;
        this.title = title;
        this.lastMessage = lastMessage;
        this.updatedAt = updatedAt;
    }

    public static AiChatSessionSummaryDTO of(AiChatSessionSummary summary) {
        return new AiChatSessionSummaryDTO(
                summary.sessionId(), summary.title(), summary.lastMessage(), summary.updatedAt());
    }

    /** 방금 만든 빈 스레드. 제목도 마지막 메시지도 아직 없다. */
    public static AiChatSessionSummaryDTO of(AiChatSession session) {
        return new AiChatSessionSummaryDTO(
                session.getId(), session.getTitle(), null, session.getUpdatedAt());
    }
}
