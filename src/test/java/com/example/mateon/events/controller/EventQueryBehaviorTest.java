package com.example.mateon.events.controller;

import com.example.mateon.common.exception.GlobalExceptionHandler;
import com.example.mateon.events.models.Event;
import com.example.mateon.events.models.Event.Category;
import com.example.mateon.events.models.Event.Field;
import com.example.mateon.events.repository.EventRepository;
import com.example.mateon.events.service.EventMatchingService;
import com.example.mateon.events.service.EventService;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 활동 조회(/search, /recommended)의 '겉으로 보이는 동작'을 고정한다.
 *
 * <p>
 * 필터링·점수 정렬·카테고리별 선별 로직을 컨트롤러에서 서비스 계층으로 옮기기 전에 먼저 붙인
 * 특성화 테스트다. 검증을 HTTP 응답(순서와 id)으로만 하므로, 로직이 어느 클래스에 있든
 * 결과가 같다면 통과한다 — 즉 리팩터링이 동작을 바꿨는지를 그대로 잡아낸다.
 *
 * <p>
 * 점수는 EventMatchingService 를 목으로 두고 직접 지정한다. 실제 점수 계산 규칙은
 * EventMatchingServiceTest 의 몫이고, 여기서는 '점수가 주어졌을 때 어떻게 줄 세우는지'만 본다.
 */
class EventQueryBehaviorTest {

    private static final long USER_ID = 7L;

    private EventRepository eventRepository;
    private UserRepository userRepository;
    private EventMatchingService matchingService;
    private MockMvc mockMvc;

    private final User user = new User();

    @BeforeEach
    void setUp() {
        eventRepository = mock(EventRepository.class);
        userRepository = mock(UserRepository.class);
        matchingService = mock(EventMatchingService.class);

        EventService eventService = new EventService(eventRepository, matchingService, userRepository);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new EventController(eventService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        // 별도로 지정하지 않은 활동의 기본 점수
        when(matchingService.calculateRelevanceScore(any(), any())).thenReturn(0);
    }

    @Nested
    @DisplayName("GET /api/events/search")
    class Search {

        @Test
        @DisplayName("분야 필터를 주면 그 분야의 활동만 남는다")
        void filtersByField() throws Exception {
            Event it = event(1L, Category.CONTEST, Field.SCIENCE_ENGINEERING_TECH_IT);
            Event design = event(2L, Category.CONTEST, Field.DESIGN_PHOTO_ART_VIDEO);
            Event noField = event(3L, Category.CONTEST, null);
            when(eventRepository.findAll()).thenReturn(List.of(it, design, noField));

            mockMvc.perform(get("/api/events/search").param("field", "SCIENCE_ENGINEERING_TECH_IT"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(1));
        }

        @Test
        @DisplayName("비로그인이면 점수 정렬 없이 조회 순서를 그대로 유지한다")
        void keepsRepositoryOrderWhenAnonymous() throws Exception {
            when(eventRepository.findAll()).thenReturn(List.of(
                    event(2L, Category.CONTEST, null),
                    event(1L, Category.CONTEST, null)));

            mockMvc.perform(get("/api/events/search"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(2))
                    .andExpect(jsonPath("$.data[1].id").value(1));
        }

        @Test
        @DisplayName("로그인하면 관련도 점수가 높은 순으로 정렬한다")
        void sortsByScoreWhenAuthenticated() throws Exception {
            Event low = event(1L, Category.CONTEST, null);
            Event high = event(2L, Category.CONTEST, null);
            when(eventRepository.findAll()).thenReturn(List.of(low, high));
            score(low, 10);
            score(high, 30);

            mockMvc.perform(get("/api/events/search").principal(auth()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(2))
                    .andExpect(jsonPath("$.data[1].id").value(1));
        }

        @Test
        @DisplayName("점수가 같으면 최근에 등록된 활동이 앞에 온다")
        void breaksScoreTieByRecency() throws Exception {
            Event older = event(1L, Category.CONTEST, null, LocalDateTime.of(2026, 1, 1, 0, 0));
            Event newer = event(2L, Category.CONTEST, null, LocalDateTime.of(2026, 7, 1, 0, 0));
            when(eventRepository.findAll()).thenReturn(List.of(older, newer));
            score(older, 10);
            score(newer, 10);

            mockMvc.perform(get("/api/events/search").principal(auth()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(2))
                    .andExpect(jsonPath("$.data[1].id").value(1));
        }

        @Test
        @DisplayName("단과대학 필터를 주면 해당 전용 조회를 사용한다")
        void usesCollegeQueryWhenCollegeGiven() throws Exception {
            when(eventRepository.findByTargetCollegeName("SW융합대학"))
                    .thenReturn(List.of(event(9L, Category.SCHOOL, null)));

            mockMvc.perform(get("/api/events/search").param("college", "SW융합대학"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(9));
        }

        @Test
        @DisplayName("단과대학이 '전체'면 필터로 치지 않고 전체를 조회한다")
        void treatsAllCollegeAsNoFilter() throws Exception {
            when(eventRepository.findAll()).thenReturn(List.of(event(1L, Category.CONTEST, null)));

            mockMvc.perform(get("/api/events/search").param("college", "전체"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(1));
        }
    }

    @Nested
    @DisplayName("GET /api/events/recommended")
    class Recommended {

        @Test
        @DisplayName("카테고리를 안 주면 카테고리마다 점수가 가장 높은 활동 1개씩 반환한다")
        void returnsBestPerCategory() throws Exception {
            Event contestLow = event(1L, Category.CONTEST, null);
            Event contestHigh = event(2L, Category.CONTEST, null);
            Event external = event(3L, Category.EXTERNAL, null);
            when(eventRepository.findAll()).thenReturn(List.of(contestLow, contestHigh, external));
            score(contestLow, 10);
            score(contestHigh, 30);
            score(external, 20);

            mockMvc.perform(get("/api/events/recommended").principal(auth()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    // 카테고리별 대표끼리도 점수 높은 순으로 줄 세운다
                    .andExpect(jsonPath("$.data[0].id").value(2))
                    .andExpect(jsonPath("$.data[1].id").value(3));
        }

        @Test
        @DisplayName("카테고리를 주면 그 카테고리에서 점수가 가장 높은 1개만 반환한다")
        void returnsSingleBestWhenCategoryGiven() throws Exception {
            Event low = event(1L, Category.CONTEST, null);
            Event high = event(2L, Category.CONTEST, null);
            when(eventRepository.findByCategory(Category.CONTEST)).thenReturn(List.of(low, high));
            score(low, 10);
            score(high, 30);

            mockMvc.perform(get("/api/events/recommended")
                            .param("category", "CONTEST")
                            .principal(auth()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(2));
        }

        @Test
        @DisplayName("비로그인이면 거부한다")
        void rejectsAnonymous() throws Exception {
            mockMvc.perform(get("/api/events/recommended"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // --- 헬퍼 ---

    private Authentication auth() {
        return new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of());
    }

    private void score(Event event, int value) {
        when(matchingService.calculateRelevanceScore(eq(user), eq(event))).thenReturn(value);
    }

    private Event event(Long id, Category category, Field field) {
        return event(id, category, field, LocalDateTime.of(2026, 1, 1, 0, 0));
    }

    private Event event(Long id, Category category, Field field, LocalDateTime createdAt) {
        Event event = new Event();
        event.setId(id);
        event.setCategory(category);
        event.setField(field);
        event.setTitle("활동 " + id);
        event.setCreatedAt(createdAt);
        return event;
    }
}
