package com.example.mateon.bookmarks.controller;

import com.example.mateon.bookmarks.service.BookmarkService;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.GlobalExceptionHandler;
import com.example.mateon.common.exception.MateonException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 북마크 API 의 응답 계약을 고정한다.
 *
 * <p>
 * 여기서 지켜야 할 핵심은 <b>중복 요청이 에러가 아니라는 것</b>이다. 별 아이콘을 두 번 누르거나
 * 네트워크 재시도로 같은 요청이 두 번 오는 건 정상이고, 그때도 프론트는 같은 상태를 받아야 한다.
 * 나중에 누군가 "중복은 DUPLICATE_RESOURCE 로 막는 게 이 레포 관례 아니냐"며 되돌리면 여기서 걸린다.
 *
 * <p>
 * JSON 키가 {@code bookmarked} 인지도 함께 못박는다 — boolean 필드는 게터 이름에 따라 키가
 * 달라질 수 있어(UserProfileResponse 의 isMe 가 겪은 문제) 눈으로 확인해 둘 값이 아니다.
 */
class BookmarkControllerTest {

    private static final long USER_ID = 7L;
    private static final long EVENT_ID = 42L;

    private BookmarkService bookmarkService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        bookmarkService = mock(BookmarkService.class);
        mockMvc = MockMvcBuilders
          .standaloneSetup(new BookmarkController(bookmarkService))
          .setControllerAdvice(new GlobalExceptionHandler())
          .build();
    }

    @Nested
    @DisplayName("POST /api/bookmarks/events/{eventId}")
    class Add {

        @Test
        @DisplayName("새로 찜하면 201 과 bookmarked=true 를 준다")
        void createsBookmark() throws Exception {
            when(bookmarkService.addBookmark(USER_ID, EVENT_ID)).thenReturn(true);

            mockMvc.perform(post("/api/bookmarks/events/42").principal(auth()))
              .andExpect(status().isCreated())
              .andExpect(jsonPath("$.success").value(true))
              .andExpect(jsonPath("$.data.eventId").value(42))
              .andExpect(jsonPath("$.data.bookmarked").value(true));
        }

        @Test
        @DisplayName("이미 찜한 활동을 다시 찜해도 에러가 아니라 200 과 bookmarked=true 다")
        void repeatedBookmarkIsNotAnError() throws Exception {
            when(bookmarkService.addBookmark(USER_ID, EVENT_ID)).thenReturn(false);

            mockMvc.perform(post("/api/bookmarks/events/42").principal(auth()))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.success").value(true))
              .andExpect(jsonPath("$.data.bookmarked").value(true));
        }

        @Test
        @DisplayName("없는 활동을 찜하면 404 (400 이면 '요청이 잘못됐다'로 읽혀 오해를 부른다)")
        void rejectsUnknownEvent() throws Exception {
            when(bookmarkService.addBookmark(anyLong(), anyLong()))
              .thenThrow(new MateonException(ErrorCode.EVENT_NOT_FOUND));

            mockMvc.perform(post("/api/bookmarks/events/999999").principal(auth()))
              .andExpect(status().isNotFound())
              .andExpect(jsonPath("$.success").value(false));
        }
    }

    @Nested
    @DisplayName("DELETE /api/bookmarks/events/{eventId}")
    class Remove {

        @Test
        @DisplayName("해제하면 200 과 bookmarked=false 를 준다")
        void removesBookmark() throws Exception {
            when(bookmarkService.removeBookmark(USER_ID, EVENT_ID)).thenReturn(true);

            mockMvc.perform(delete("/api/bookmarks/events/42").principal(auth()))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.bookmarked").value(false));
        }

        @Test
        @DisplayName("찜한 적 없는 활동을 해제해도 200 이다 — 결과 상태가 같으므로 실패로 볼 이유가 없다")
        void removingAbsentBookmarkIsNotAnError() throws Exception {
            when(bookmarkService.removeBookmark(USER_ID, EVENT_ID)).thenReturn(false);

            mockMvc.perform(delete("/api/bookmarks/events/42").principal(auth()))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.success").value(true))
              .andExpect(jsonPath("$.data.bookmarked").value(false));
        }
    }

    @Nested
    @DisplayName("GET /api/bookmarks/events")
    class Query {

        @Test
        @DisplayName("id 목록 경로(/events/ids)가 /events/{eventId} 에 잡히지 않는다")
        void idsPathIsNotCapturedByNumericPathVariable() throws Exception {
            when(bookmarkService.getMyBookmarkedEventIds(USER_ID)).thenReturn(List.of(12L, 45L));

            mockMvc.perform(get("/api/bookmarks/events/ids").principal(auth()))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data[0]").value(12))
              .andExpect(jsonPath("$.data[1]").value(45));
        }

        @Test
        @DisplayName("목록은 page/size 를 그대로 서비스에 넘긴다")
        void passesPageAndSize() throws Exception {
            when(bookmarkService.getMyBookmarks(USER_ID, 2, 5)).thenReturn(List.of());

            mockMvc.perform(get("/api/bookmarks/events")
              .param("page", "2")
              .param("size", "5")
              .principal(auth()))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.length()").value(0));
        }
    }

    private Authentication auth() {
        return new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of());
    }
}
