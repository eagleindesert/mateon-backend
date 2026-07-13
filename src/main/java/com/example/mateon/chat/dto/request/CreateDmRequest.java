package com.example.mateon.chat.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CreateDmRequest {

    @NotNull
    private Long targetUserId; // DM 상대 사용자 id
}
