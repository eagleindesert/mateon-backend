package com.example.mateon.bookmarks.service;

import com.example.mateon.bookmarks.repository.EventBookmarkRepository;
import com.example.mateon.events.models.Event;
import com.example.mateon.events.repository.EventRepository;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 북마크 등록의 경합 경로. UNIQUE 제약이 막아 준 뒤에도 호출은 성공으로 끝난다.
 *
 * <p>
 * 통합 테스트는 exists 가 먼저 true 를 돌려 이 catch 에 닿지 못하므로, 여기서만 재현한다.
 */
class BookmarkServiceTest {

    private EventBookmarkRepository bookmarkRepository;
    private EventRepository eventRepository;
    private UserRepository userRepository;
    private BookmarkService service;

    @BeforeEach
    void setUp() {
        bookmarkRepository = mock(EventBookmarkRepository.class);
        eventRepository = mock(EventRepository.class);
        userRepository = mock(UserRepository.class);
        service = new BookmarkService(bookmarkRepository, eventRepository, userRepository);
    }

    @Test
    @DisplayName("exists 와 INSERT 사이 경합이면 새로 생긴 게 아니라도 실패하지 않는다")
    void uniqueViolationIsTreatedAsAlreadyBookmarked() {
        Event event = new Event();
        event.setId(5L);
        User user = User.builder().id(1L).name("유저").build();
        when(eventRepository.findById(5L)).thenReturn(Optional.of(event));
        when(bookmarkRepository.existsByUserIdAndEventId(1L, 5L)).thenReturn(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(bookmarkRepository.saveAndFlush(any()))
          .thenThrow(new DataIntegrityViolationException("uq_event_bookmarks_pair"));

        assertThat(service.addBookmark(1L, 5L)).isFalse();
    }
}
