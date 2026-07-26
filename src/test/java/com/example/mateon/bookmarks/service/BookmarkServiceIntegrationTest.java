package com.example.mateon.bookmarks.service;

import com.example.mateon.bookmarks.repository.EventBookmarkRepository;
import com.example.mateon.common.PageLimits;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.events.dto.EventResponseDTO;
import com.example.mateon.events.models.Event;
import com.example.mateon.events.models.Event.Category;
import com.example.mateon.events.models.Event.Field;
import com.example.mateon.events.repository.EventRepository;
import com.example.mateon.events.service.EventService;
import com.example.mateon.support.IntegrationTestBase;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 북마크를 실제 DB 에 대고 확인한다.
 *
 * <p>
 * UNIQUE 제약과 삭제 행 수, 그리고 검색 응답에 붙는 bookmarked 는 목으로는 검증할 수 없다 —
 * 리포지토리를 목으로 두면 제약도 조인도 실행되지 않는다.
 *
 * <p>
 * DB 는 {@link IntegrationTestBase} 가 띄우는 빈 컨테이너다. 그래도 활동은 제목에 넣은 tag 로
 * 골라 본다 — 한 JVM 이 컨테이너를 공유하므로 다른 테스트가 심은 활동이 섞여 있을 수 있다.
 * {@code @Transactional} 이라 끝나면 전부 롤백된다.
 */
class BookmarkServiceIntegrationTest extends IntegrationTestBase {

    @Autowired
    BookmarkService bookmarkService;
    @Autowired
    EventService eventService;
    @Autowired
    EventRepository eventRepository;
    @Autowired
    EventBookmarkRepository bookmarkRepository;
    @Autowired
    UserRepository userRepository;

    /**
     * 이번 실행이 심은 활동만 골라내기 위한 표식. 개발 DB 의 기존 데이터와 섞이면 안 된다.
     */
    private String tag;
    private Long userId;

    @BeforeEach
    void setUp() {
        tag = "bookmark-test-" + UUID.randomUUID();
        userId = userRepository.save(User.builder()
          .email(UUID.randomUUID() + "@test.ac.kr")
          .name("북마크 테스트 유저")
          .build()).getId();
    }

    @Test
    @DisplayName("같은 활동을 두 번 찜해도 행은 하나다 (두 번째는 '새로 안 생김'을 알린다)")
    void repeatedBookmarkCreatesSingleRow() {
        Long eventId = saveEvent(null);

        assertThat(bookmarkService.addBookmark(userId, eventId)).isTrue();
        assertThat(bookmarkService.addBookmark(userId, eventId)).isFalse();

        assertThat(bookmarkRepository.findAllBookmarkedEventIds(userId)).containsExactly(eventId);
    }

    @Test
    @DisplayName("해제하면 목록에서 빠지고, 찜한 적 없는 걸 해제하면 '지운 게 없음'을 알린다")
    void removeIsIdempotent() {
        Long eventId = saveEvent(null);
        bookmarkService.addBookmark(userId, eventId);

        assertThat(bookmarkService.removeBookmark(userId, eventId)).isTrue();
        assertThat(bookmarkService.removeBookmark(userId, eventId)).isFalse();

        assertThat(bookmarkRepository.findAllBookmarkedEventIds(userId)).isEmpty();
    }

    @Test
    @DisplayName("없는 활동을 찜하면 EVENT_NOT_FOUND 다")
    void rejectsUnknownEvent() {
        assertThatThrownBy(() -> bookmarkService.addBookmark(userId, -1L))
          .isInstanceOf(MateonException.class)
          .extracting(e -> ((MateonException) e).getErrorCode())
          .isEqualTo(ErrorCode.EVENT_NOT_FOUND);
    }

    @Test
    @DisplayName("목록은 활동 시작일순이 아니라 북마크한 최신순이다")
    void listsByBookmarkedTimeNotEventStartDate() {
        // 시작일 순서와 찜한 순서가 서로 반대가 되도록 심는다. 정렬 기준을 잘못 잡으면 여기서 갈린다.
        Long newerEvent = saveEvent(LocalDate.of(2026, 12, 1));
        Long olderEvent = saveEvent(LocalDate.of(2020, 1, 1));

        bookmarkService.addBookmark(userId, newerEvent);
        bookmarkService.addBookmark(userId, olderEvent); // 나중에 찜했으니 앞에 와야 한다

        assertThat(myBookmarkIds()).containsExactly(olderEvent, newerEvent);
    }

    @Test
    @DisplayName("북마크 목록에 실린 활동은 전부 bookmarked=true 다")
    void listedEventsAreMarkedBookmarked() {
        bookmarkService.addBookmark(userId, saveEvent(null));

        assertThat(bookmarkService.getMyBookmarks(userId, 0, PageLimits.MAX_PAGE_SIZE))
          .isNotEmpty()
          .allMatch(EventResponseDTO::isBookmarked);
    }

    @Test
    @DisplayName("검색 응답의 bookmarked 는 찜한 활동에만 true 이고, 비로그인 조회는 전부 false 다")
    void searchMarksOnlyBookmarkedEvents() {
        Long bookmarked = saveEvent(null);
        Long plain = saveEvent(null);
        bookmarkService.addBookmark(userId, bookmarked);

        assertThat(searchMine(userId))
          .filteredOn(dto -> dto.getId().equals(bookmarked))
          .allMatch(EventResponseDTO::isBookmarked);
        assertThat(searchMine(userId))
          .filteredOn(dto -> dto.getId().equals(plain))
          .noneMatch(EventResponseDTO::isBookmarked);

        // 비로그인(userId=null)에게는 남의 북마크가 새어 나가면 안 된다.
        assertThat(searchMine(null)).noneMatch(EventResponseDTO::isBookmarked);
    }

    @Test
    @DisplayName("검색 결과의 순서는 로그인 여부와 무관하게 같다 — bookmarked 는 표시용 필드일 뿐이다")
    void bookmarkDoesNotAffectSearchOrder() {
        Long first = saveEvent(LocalDate.of(2026, 5, 1));
        Long second = saveEvent(LocalDate.of(2020, 5, 1));
        // 뒤에 와야 할 활동을 찜한다. 북마크가 정렬에 끼어들면 순서가 뒤집힌다.
        bookmarkService.addBookmark(userId, second);

        List<Long> anonymousOrder = ids(searchMine(null));
        assertThat(anonymousOrder).containsExactly(first, second);
        assertThat(ids(searchMine(userId))).isEqualTo(anonymousOrder);
    }

    // --- 헬퍼 ---
    /**
     * 이번 테스트가 심은 활동만 골라낸 검색 결과. userId 가 null 이면 비로그인 조회다.
     */
    private List<EventResponseDTO> searchMine(Long viewerId) {
        return eventService.search(null, null, null, null, tag, 0, PageLimits.MAX_PAGE_SIZE, viewerId)
          .stream()
          .filter(dto -> dto.getTitle() != null && dto.getTitle().contains(tag))
          .toList();
    }

    private List<Long> myBookmarkIds() {
        return ids(bookmarkService.getMyBookmarks(userId, 0, PageLimits.MAX_PAGE_SIZE).stream()
          .filter(dto -> dto.getTitle() != null && dto.getTitle().contains(tag))
          .toList());
    }

    private static List<Long> ids(List<EventResponseDTO> events) {
        return events.stream().map(EventResponseDTO::getId).toList();
    }

    private Long saveEvent(LocalDate startDate) {
        Event event = new Event();
        event.setCategory(Category.CONTEST);
        event.setField(Field.ETC);
        event.setTitle(tag + " " + UUID.randomUUID());
        event.setStartDate(startDate);
        return eventRepository.saveAndFlush(event).getId();
    }
}
