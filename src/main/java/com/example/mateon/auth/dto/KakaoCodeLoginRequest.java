package com.example.mateon.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Schema(description = "웹 카카오 로그인 요청. React 콜백이 받은 인가코드와, "
  + "카카오 authorize 에 넘긴 redirect_uri 를 그대로 보낸다.")
@Getter
@Setter
public class KakaoCodeLoginRequest {

    @Schema(description = "카카오가 React 콜백 URL 에 붙여 준 인가코드. 액세스 토큰이 아니다.")
    @NotBlank(message = "카카오 인가코드를 입력해주세요.")
    private String authorizationCode;

    @Schema(description = "카카오 authorize 에 넘긴 redirect_uri 와 글자 단위로 같아야 한다. "
      + "서버 허용 목록에 있는 React 콜백만 받는다.")
    @NotBlank(message = "redirectUri 를 입력해주세요.")
    private String redirectUri;
}
