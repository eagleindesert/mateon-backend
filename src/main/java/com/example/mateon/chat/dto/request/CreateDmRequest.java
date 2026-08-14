package com.example.mateon.chat.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "DM 방 조회-or-생성 요청")
@Getter
@NoArgsConstructor
public class CreateDmRequest {

    @Schema(description = "DM 상대의 userId. 추천 목록·팀 상세·역제안 응답에 담긴 userId 를 그대로 쓰면 된다.")
    @NotNull
    private Long targetUserId; // DM 상대 사용자 id
}
