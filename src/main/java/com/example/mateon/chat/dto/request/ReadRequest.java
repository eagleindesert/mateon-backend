package com.example.mateon.chat.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ReadRequest {

    @NotNull
    private Long lastReadMessageId; // 여기까지 읽음 처리할 마지막 메시지 id
}
