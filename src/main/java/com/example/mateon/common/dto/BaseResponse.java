package com.example.mateon.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Schema(description = "모든 응답을 감싸는 공통 봉투. 실제 내용은 data 안에 있다.")
@Getter
@AllArgsConstructor
public class BaseResponse<T> {
    @Schema(description = "처리 성공 여부. 실패면 data 는 보통 null 이다.", example = "true")
    private boolean success;
    @Schema(description = "사용자에게 그대로 보여줄 수 있는 한국어 안내 문구", example = "성공")
    private String message;
    @Schema(description = "응답 본문. 돌려줄 값이 없는 API 는 null 이다.")
    private T data;

    public static <T> BaseResponse<T> success(T data) {
        return new BaseResponse<>(true, "성공", data);
    }

    public static <T> BaseResponse<T> success(String message, T data) {
        return new BaseResponse<>(true, message, data);
    }

    public static <T> BaseResponse<T> error(String message) {
        return new BaseResponse<>(false, message, null);
    }

    public static <T> BaseResponse<T> error(String message, T data) {
        return new BaseResponse<>(false, message, data);
    }
}
