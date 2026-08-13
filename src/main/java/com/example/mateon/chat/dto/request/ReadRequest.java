package com.example.mateon.chat.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "읽음 처리 요청")
@Getter
@NoArgsConstructor
public class ReadRequest {

    @Schema(description = "여기까지 읽었다고 표시할 메시지 id. 보통 화면에 보인 마지막 메시지의 messageId 를 넣는다.")
    @NotNull
    private Long lastReadMessageId; // 여기까지 읽음 처리할 마지막 메시지 id
}
