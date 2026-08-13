package com.example.mateon.bookmarks.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 북마크 등록/해제 후의 현재 상태.
 *
 * <p>등록이 성공했는지가 아니라 <b>지금 찜한 상태인지</b>를 내려준다. 프론트는 이 값 하나로
 * 별 아이콘을 칠하면 되고, 요청이 중복이었는지 아닌지는 신경 쓸 필요가 없다.
 *
 * <p>필드명을 {@code isBookmarked} 가 아니라 {@code bookmarked} 로 둔다 — 그래야 Lombok 이
 * 만드는 게터가 {@code isBookmarked()} 가 되어 JSON 키가 {@code bookmarked} 로 안정적으로 나온다.
 * (UserProfileResponse 가 {@code @JsonProperty("isMe")} 를 달아야 했던 건 필드명 자체가
 * {@code is-} 로 시작해서 생긴 별개 사정이다.)
 */
@Schema(description = "북마크 등록·해제 후의 현재 상태")
@Getter
@AllArgsConstructor
public class BookmarkToggleResponseDTO {

    private Long eventId;
    @Schema(description = "**요청 성공 여부가 아니라 지금 찜한 상태인지**를 뜻한다. "
            + "이 값 하나로 별 아이콘을 칠하면 되고, 요청이 중복이었는지는 신경 쓰지 않아도 된다.")
    private boolean bookmarked;
}
