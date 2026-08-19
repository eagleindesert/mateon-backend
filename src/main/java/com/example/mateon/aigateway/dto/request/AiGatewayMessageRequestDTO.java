package com.example.mateon.aigateway.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Schema(description = "AI 채팅 한 턴. 사용자가 입력한 문장을 그대로 보내면 된다.")
@Getter
@Setter
@NoArgsConstructor
public class AiGatewayMessageRequestDTO {

    @Schema(description = "사용자 발화.", example = "백엔드 개발자인데 같이 공모전 나갈 팀 찾고 있어요")
    @NotBlank(message = "메시지는 비어 있을 수 없습니다.")
    @Size(max = 1000, message = "메시지는 1000자를 넘을 수 없습니다.")
    private String message;
}
