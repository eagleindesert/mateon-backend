package com.example.mateon.aigateway.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Schema(description = "AI 채팅 한 턴. 어느 대화 세션에 쓸지 지정해서 보낸다.")
@Getter
@Setter
@NoArgsConstructor
public class AiGatewayMessageRequestDTO {

    /**
     * 선택 항목으로 두고 "없으면 새 대화 세션"로 처리하지 않는다 — 한 엔드포인트가 두 가지 일을
     * 하게 되고, 프론트가 필드를 빠뜨렸을 때 대화 세션이 조용히 하나 더 생긴다. 대화 세션 생성은
     * {@code POST /api/ai/chat/sessions} 로 명시적으로 한다.
     */
    @Schema(description = "발화를 붙일 대화 세션 id. POST /api/ai/chat/sessions 로 받은 값이거나 "
            + "직전 응답의 sessionId.", example = "12")
    @NotNull(message = "대화 세션 id는 필수입니다.")
    private Long sessionId;

    @Schema(description = "사용자 발화.", example = "백엔드 개발자인데 같이 공모전 나갈 팀 찾고 있어요")
    @NotBlank(message = "메시지는 비어 있을 수 없습니다.")
    @Size(max = 1000, message = "메시지는 1000자를 넘을 수 없습니다.")
    private String message;
}
