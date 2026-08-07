package com.example.mateon.bookmarks.controller;

import com.example.mateon.bookmarks.dto.BookmarkToggleResponseDTO;
import com.example.mateon.bookmarks.service.BookmarkService;
import com.example.mateon.common.dto.ApiResponse;
import com.example.mateon.events.dto.EventResponseDTO;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


/**
 * 활동 북마크(즐겨찾기) API [전 경로 인증 필수].
 *
 * <p>인증은 SecurityConfig 의 {@code /api/bookmarks/**} 매처가 강제하므로 여기서는 null 검사를
 * 하지 않는다 — 익명 요청은 컨트롤러까지 오지 못한다. (permitAll 인 /api/events/** 는 사정이
 * 달라서 EventController 쪽은 익명 토큰을 걸러 낸다.)
 *
 * <p>경로를 {@code /api/events/{id}/bookmark} 가 아니라 이쪽으로 뗀 이유: 활동 조회는
 * permitAll 이라 그 아래에 인증 필요 경로를 끼워 넣으면 SecurityConfig 의 매처 순서에
 * 기능의 보안이 걸리게 된다(순서가 뒤집히면 조용히 열린다). 접두사를 나누면 그 위험이 없다.
 */
@Tag(name = "북마크", description = "관심 활동 찜하기")
@RestController
@RequestMapping("/api/bookmarks")
@RequiredArgsConstructor
public class BookmarkController {

    private final BookmarkService bookmarkService;

    /**
     * 활동 북마크 등록.
     *
     * <p>새로 생기면 201, 이미 찜한 상태였으면 200 이다. 둘 다 성공이고 응답 본문도 같다
     * ({@code bookmarked: true}) — 프론트는 상태코드를 구분하지 않아도 되고, 구분하고 싶으면
     * 할 수 있다.
     */
    @PostMapping("/events/{eventId:\\d+}")
    public ResponseEntity<ApiResponse<BookmarkToggleResponseDTO>> addBookmark(
      Authentication authentication,
      @PathVariable Long eventId
    ) {
        Long userId = Long.valueOf(authentication.getName());
        boolean created = bookmarkService.addBookmark(userId, eventId);

        HttpStatus status = created ? HttpStatus.CREATED : HttpStatus.OK;
        String message = created ? "북마크에 추가되었습니다." : "이미 북마크한 활동입니다.";
        return ResponseEntity.status(status)
          .body(ApiResponse.success(message, new BookmarkToggleResponseDTO(eventId, true)));
    }

    /**
     * 활동 북마크 해제. 원래 찜하지 않았더라도 200 이다 — 결과 상태가 같으므로 실패로 볼 이유가 없다.
     */
    @DeleteMapping("/events/{eventId:\\d+}")
    public ResponseEntity<ApiResponse<BookmarkToggleResponseDTO>> removeBookmark(
      Authentication authentication,
      @PathVariable Long eventId
    ) {
        Long userId = Long.valueOf(authentication.getName());
        boolean removed = bookmarkService.removeBookmark(userId, eventId);

        String message = removed ? "북마크가 해제되었습니다." : "북마크한 적 없는 활동입니다.";
        return ResponseEntity.ok(
          ApiResponse.success(message, new BookmarkToggleResponseDTO(eventId, false)));
    }

    /**
     * 내 북마크 목록. 북마크한 최신순이며, 실린 활동은 전부 {@code bookmarked: true} 다.
     *
     * <p>경로 앞에 {@code /events} 를 둔 건 나중에 다른 대상(팀 모집글 등)이 생겼을 때
     * {@code /api/bookmarks/teams} 로 나란히 붙일 수 있게 하기 위해서다.
     */
    @GetMapping("/events")
    public ResponseEntity<ApiResponse<List<EventResponseDTO>>> getMyBookmarks(
      Authentication authentication,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size
    ) {
        Long userId = Long.valueOf(authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(bookmarkService.getMyBookmarks(userId, page, size)));
    }

    /**
     * 내가 찜한 활동 id 전량. 화면 여러 곳에서 별 아이콘을 칠해야 할 때 대조표로 쓴다.
     *
     * <p>위 {@code /events/{eventId:\d+}} 가 숫자로 못박혀 있어 이 경로가 그쪽에 잡히지 않는다
     * (UserController 의 {@code /{userId:\d+}} 와 같은 이유).
     */
    @GetMapping("/events/ids")
    public ResponseEntity<ApiResponse<List<Long>>> getMyBookmarkedEventIds(Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(bookmarkService.getMyBookmarkedEventIds(userId)));
    }
}
