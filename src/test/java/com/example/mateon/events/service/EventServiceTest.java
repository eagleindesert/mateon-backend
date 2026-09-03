package com.example.mateon.events.service;

import com.example.mateon.bookmarks.repository.EventBookmarkRepository;
import com.example.mateon.events.dto.EventRequestDTO;
import com.example.mateon.events.dto.EventResponseDTO;
import com.example.mateon.events.event.EventEmbeddingRefreshRequestedEvent;
import com.example.mateon.events.models.Event;
import com.example.mateon.events.repository.EventRepository;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 활동 서비스에서 {@code EventQueryBehaviorTest} 가 못 보는 두 축만 다룬다.
 *
 * <p>
 * 정렬·페이지 상한·추천 선정은 그 컨트롤러 테스트가 이미 전선까지 고정했으므로 되풀이하지 않는다.
 *
 * <p>
 * <b>하나, 등록 시 매핑의 조용한 규칙들.</b> {@code campusScope} 는 비면 {@code "ALL"} 로
 * 채워지는데(제한 없음), 여기가 {@code null} 로 남으면 대상 필터가 {@code NULL} 비교로 빠져
 * 그 활동이 <b>어떤 필터에도 안 걸리는</b> 유령이 된다. 반대로 {@code targetSchool} 과
 * {@code externalId} 는 공백을 {@code null} 로 눕혀야 한다 — 빈 문자열이 남으면 "대상 학교가
 * ''인 활동" 이 되어 역시 조회에서 빠진다. 셋 다 등록은 성공하고 목록에서만 사라진다.
 *
 * <p>
 * <b>둘, 북마크 여부의 벌크 조회.</b> 활동마다 exists 를 부르면 페이지 크기만큼 쿼리가 나간다.
 * 목록·검색·추천·무작위 네 경로가 이 한 메서드를 공유하므로 되돌아오면 전부 같이 느려진다.
 * 비로그인일 때 조회 자체를 건너뛰는 것도 함께 확인한다 — {@code userId} 가 null 인 채로
 * 쿼리에 들어가면 결과가 비는 정도가 아니라 조건이 통째로 무의미해진다.
 */
// campusScope 는 school 로 전환 중이라 deprecated 지만, 아직 살아 있는 매핑이라 검증한다.
@SuppressWarnings("deprecation")
class EventServiceTest {

    private static final long USER_ID = 1L;

    private EventRepository eventRepository;
    private EventMatchingService eventMatchingService;
    private UserRepository userRepository;
    private EventBookmarkRepository bookmarkRepository;
    private ApplicationEventPublisher eventPublisher;
    private EventService service;

    @BeforeEach
    void setUp() {
        eventRepository = mock(EventRepository.class);
        eventMatchingService = mock(EventMatchingService.class);
        userRepository = mock(UserRepository.class);
        bookmarkRepository = mock(EventBookmarkRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new EventService(eventRepository, eventMatchingService, userRepository,
          bookmarkRepository, eventPublisher);
    }

    @Nested
    @DisplayName("등록 — 비면 안 되는 값과 비어야 하는 값이 갈린다")
    class CreateEvent {

        @Test
        @DisplayName("요청 필드가 그대로 엔티티에 옮겨진다")
        void mapsFields() {
            when(eventRepository.save(any())).thenAnswer(call -> call.getArgument(0));

            service.createEvent(request());

            Event saved = capturedEvent();
            assertThat(saved.getTitle()).isEqualTo("교내 해커톤");
            assertThat(saved.getCategory()).isEqualTo(Event.Category.CONTEST);
            assertThat(saved.getField()).isEqualTo(Event.Field.EDUCATION);
            assertThat(saved.getOrganizer()).isEqualTo("학생회");
            assertThat(saved.getStartDate()).isEqualTo(LocalDate.of(2026, 9, 1));
            assertThat(saved.getSummarizedDescription()).isEqualTo("이틀간 진행");
        }

        /**
         * 이 값이 {@code null} 로 남으면 대상 필터의 비교가 {@code NULL} 과 맞물려 늘 거짓이 된다 —
         * 등록은 성공하는데 목록에는 영영 안 뜬다.
         */
        @Test
        @DisplayName("campusScope 를 비워 보내면 \"ALL\"(제한 없음)로 채운다 — null 로 두면 유령 활동이 된다")
        void blankCampusScopeBecomesAll() {
            when(eventRepository.save(any())).thenAnswer(call -> call.getArgument(0));
            EventRequestDTO request = request();
            request.setCampusScope("   ");

            service.createEvent(request);

            assertThat(capturedEvent().getCampusScope()).isEqualTo(Event.CAMPUS_SCOPE_ALL);
        }

        @Test
        @DisplayName("targetSchool 과 externalId 는 반대로 공백을 null 로 눕힌다 (''는 값으로 취급된다)")
        void blankStringsBecomeNull() {
            when(eventRepository.save(any())).thenAnswer(call -> call.getArgument(0));
            EventRequestDTO request = request();
            request.setTargetSchool("  ");
            request.setExternalId("");

            service.createEvent(request);

            Event saved = capturedEvent();
            assertThat(saved.getTargetSchool()).isNull();
            assertThat(saved.getExternalId()).isNull();
        }

        @Test
        @DisplayName("값이 있으면 그대로 남는다")
        void presentValuesSurvive() {
            when(eventRepository.save(any())).thenAnswer(call -> call.getArgument(0));
            EventRequestDTO request = request();
            request.setTargetSchool("메이트대");
            request.setExternalId("ext-42");
            request.setCampusScope("서울캠퍼스");

            service.createEvent(request);

            Event saved = capturedEvent();
            assertThat(saved.getTargetSchool()).isEqualTo("메이트대");
            assertThat(saved.getExternalId()).isEqualTo("ext-42");
            assertThat(saved.getCampusScope()).isEqualTo("서울캠퍼스");
        }

        /**
         * V18 에서 {@code external_id} 의 UNIQUE 를 풀었다. 같은 공고를 두 번 올리는 것은
         * 등록자가 판단할 문제이므로 서비스가 막지 않는다 — 막는 코드를 "되살리는" 변경이
         * 크롤러 재수집을 통째로 실패시키지 않도록 고정한다.
         */
        @Test
        @DisplayName("중복 검사를 하지 않는다 (external_id UNIQUE 는 V18 에서 풀렸다)")
        void noDuplicateCheck() {
            when(eventRepository.save(any())).thenAnswer(call -> call.getArgument(0));
            EventRequestDTO request = request();
            request.setExternalId("ext-42");

            service.createEvent(request);

            verify(eventRepository).save(any());
            verify(eventRepository, never()).findAll();
        }

        @Test
        @DisplayName("임베딩 벡터는 채우지 않는다 (추천 점수는 문자열 매칭만 쓴다)")
        void doesNotFillEmbedding() {
            when(eventRepository.save(any())).thenAnswer(call -> call.getArgument(0));

            service.createEvent(request());

            assertThat(capturedEvent().getEmbeddingVector()).isNull();
        }

        @Test
        @DisplayName("커밋 후 임베딩 갱신 이벤트를 발행한다 (응답은 벡터를 기다리지 않는다)")
        void publishesEmbeddingRefreshEvent() {
            when(eventRepository.save(any())).thenAnswer(call -> {
                Event event = call.getArgument(0);
                event.setId(42L);
                return event;
            });

            service.createEvent(request());

            ArgumentCaptor<EventEmbeddingRefreshRequestedEvent> captor =
              ArgumentCaptor.forClass(EventEmbeddingRefreshRequestedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().eventId()).isEqualTo(42L);
        }

        @Test
        @DisplayName("등록 응답에는 저장된 활동이 그대로 담긴다")
        void returnsSavedEvent() {
            when(eventRepository.save(any())).thenAnswer(call -> {
                Event event = call.getArgument(0);
                event.setId(42L);
                return event;
            });

            EventResponseDTO created = service.createEvent(request());

            assertThat(created.getId()).isEqualTo(42L);
            assertThat(created.getTitle()).isEqualTo("교내 해커톤");
        }

        private Event capturedEvent() {
            ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
            verify(eventRepository).save(captor.capture());
            return captor.getValue();
        }
    }

    @Nested
    @DisplayName("북마크 여부 — 네 조회 경로가 공유하는 한 메서드다")
    class BookmarkFlag {

        @Test
        @DisplayName("페이지의 id 를 한 번에 넘겨 조회한다 (활동마다 exists 를 부르지 않는다)")
        void batchesLookup() {
            givenSearchResult(event(10L), event(11L), event(12L));
            when(bookmarkRepository.findBookmarkedEventIds(anyLong(), any())).thenReturn(List.of(11L));

            service.search(null, null, null, null, null, 0, 10, USER_ID);

            ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
            verify(bookmarkRepository, times(1)).findBookmarkedEventIds(org.mockito.ArgumentMatchers.eq(USER_ID),
              ids.capture());
            assertThat(ids.getValue()).containsExactly(10L, 11L, 12L);
        }

        @Test
        @DisplayName("찜한 활동만 bookmarked=true 다")
        void marksOnlyBookmarked() {
            givenSearchResult(event(10L), event(11L));
            when(bookmarkRepository.findBookmarkedEventIds(anyLong(), any())).thenReturn(List.of(11L));

            List<EventResponseDTO> events = service.search(null, null, null, null, null, 0, 10, USER_ID);

            assertThat(events).extracting(EventResponseDTO::getId, EventResponseDTO::isBookmarked)
              .containsExactly(
                org.assertj.core.groups.Tuple.tuple(10L, false),
                org.assertj.core.groups.Tuple.tuple(11L, true));
        }

        @Test
        @DisplayName("비로그인이면 북마크 조회를 아예 하지 않고 전부 false 다")
        void anonymousSkipsLookup() {
            givenSearchResult(event(10L));

            List<EventResponseDTO> events = service.search(null, null, null, null, null, 0, 10, null);

            assertThat(events).singleElement()
              .extracting(EventResponseDTO::isBookmarked).isEqualTo(false);
            verifyNoInteractions(bookmarkRepository);
        }

        @Test
        @DisplayName("결과가 0건이면 로그인 상태여도 조회하지 않는다 (빈 IN 절을 만들지 않는다)")
        void emptyResultSkipsLookup() {
            givenSearchResult();

            assertThat(service.search(null, null, null, null, null, 0, 10, USER_ID)).isEmpty();

            verifyNoInteractions(bookmarkRepository);
        }

        @Test
        @DisplayName("무작위 조회 경로에서도 같은 규칙이 적용된다")
        void appliesToRandomPath() {
            when(eventRepository.findAllRandomly(anyInt())).thenReturn(List.of(event(10L)));
            when(bookmarkRepository.findBookmarkedEventIds(anyLong(), any())).thenReturn(List.of(10L));

            assertThat(service.findAllRandomly(5, USER_ID)).singleElement()
              .extracting(EventResponseDTO::isBookmarked).isEqualTo(true);
        }
    }

    @Nested
    @DisplayName("추천 정렬 — 점수가 같으면 등록일, 없으면 원래 순서")
    class RecommendTieBreak {

        @Test
        @DisplayName("점수가 같고 한쪽만 등록일이 있으면 순서를 바꾸지 않는다")
        void equalScoreWithOneMissingCreatedAtKeepsOrder() {
            User user = User.builder().id(USER_ID).build();
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(eventMatchingService.calculateRelevanceScore(any(), any())).thenReturn(10);

            Event dated = event(1L);
            dated.setCreatedAt(LocalDateTime.of(2020, 1, 1, 0, 0));
            Event undated = event(2L);
            when(eventRepository.findByCategory(Event.Category.CONTEST))
              .thenReturn(List.of(dated, undated));

            assertThat(service.recommend(Event.Category.CONTEST, USER_ID))
              .extracting(EventResponseDTO::getId)
              .containsExactly(1L);

            when(eventRepository.findByCategory(Event.Category.CONTEST))
              .thenReturn(List.of(undated, dated));

            assertThat(service.recommend(Event.Category.CONTEST, USER_ID))
              .extracting(EventResponseDTO::getId)
              .containsExactly(2L);
        }
    }

    // --- 픽스처 -------------------------------------------------------------
    @SuppressWarnings("unchecked")
    private void givenSearchResult(Event... events) {
        when(eventRepository.findAll(any(Specification.class), any(Pageable.class)))
          .thenReturn(new PageImpl<>(List.of(events)));
    }

    private Event event(long id) {
        Event event = new Event();
        event.setId(id);
        event.setTitle("활동 " + id);
        event.setCategory(Event.Category.CONTEST);
        return event;
    }

    private EventRequestDTO request() {
        EventRequestDTO dto = new EventRequestDTO();
        dto.setCategory(Event.Category.CONTEST);
        dto.setField(Event.Field.EDUCATION);
        dto.setTitle("교내 해커톤");
        dto.setDescription("설명");
        dto.setOrganizer("학생회");
        dto.setStartDate(LocalDate.of(2026, 9, 1));
        dto.setEndDate(LocalDate.of(2026, 9, 3));
        dto.setSummarizedDescription("이틀간 진행");
        return dto;
    }
}
