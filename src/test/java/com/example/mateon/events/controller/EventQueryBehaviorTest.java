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
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

    /**
     * 필터·정렬 자체는 여기서 검증하지 않는다 — Specification(EventSearchSpecs)으로 옮겨가서
     * 리포지토리를 목으로 두면 실행되지 않기 때문이다. 그쪽은 EventSearchIntegrationTest 의 몫이고,
     * 여기서는 응답이 어떤 모양으로 나가는지만 본다.
     */
    @Nested
    @DisplayName("GET /api/events/search")
    class Search {

        @Test
        @DisplayName("응답에 새 필드(organizer/targetSchool)가 실리고 기존 필드도 그대로 남는다")
        void responseKeepsExistingContractAndAddsNewFields() throws Exception {
            Event event = college(school(event(1L, Category.CONTEST, Field.PLANNING_IDEA), "단국대학교"), "SW융합대학");
            event.setOrganizer("업스테이지");
            campus(event, "죽전");
            when(eventRepository.findAll(ArgumentMatchers.<Specification<Event>>any(), any(Sort.class)))
                    .thenReturn(List.of(event));

            mockMvc.perform(get("/api/events/search"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].organizer").value("업스테이지"))
                    .andExpect(jsonPath("$.data[0].targetSchool").value("단국대학교"))
                    // 아래 넷은 폐기 예정이지만 프론트가 아직 읽는다. 응답에서 사라지면 안 된다.
                    .andExpect(jsonPath("$.data[0].campusScope").value("죽전"))
                    .andExpect(jsonPath("$.data[0].targetColleges").value("SW융합대학"))
                    .andExpect(jsonPath("$.data[0].field").value("PLANNING_IDEA"))
                    .andExpect(jsonPath("$.data[0].fieldLabel").value("기획/아이디어"));
        }

        @Test
        @DisplayName("시작일 최신순 정렬을 DB 에 맡긴다")
        void delegatesSortingToDatabase() throws Exception {
            when(eventRepository.findAll(ArgumentMatchers.<Specification<Event>>any(), any(Sort.class)))
                    .thenReturn(List.of());

            mockMvc.perform(get("/api/events/search")).andExpect(status().isOk());

            ArgumentCaptor<Sort> sort = ArgumentCaptor.forClass(Sort.class);
            verify(eventRepository).findAll(ArgumentMatchers.<Specification<Event>>any(), sort.capture());
            assertThat(sort.getValue().getOrderFor("startDate")).isNotNull();
            assertThat(sort.getValue().getOrderFor("startDate").isDescending()).isTrue();
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

        @Test
        @DisplayName("deprecated 신호(Deprecation/Sunset 헤더)를 함께 내려준다")
        void announcesDeprecation() throws Exception {
            when(eventRepository.findAll()).thenReturn(List.of(event(1L, Category.CONTEST, null)));

            mockMvc.perform(get("/api/events/recommended").principal(auth()))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Deprecation", "true"))
                    .andExpect(header().exists("Sunset"));
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

    private Event school(Event event, String targetSchool) {
        event.setTargetSchool(targetSchool);
        return event;
    }

    @SuppressWarnings("deprecation") // 단과대 필터가 살아 있는 동안은 이 축도 계속 검증한다.
    private Event college(Event event, String targetColleges) {
        event.setTarget_colleges(targetColleges);
        return event;
    }

    @SuppressWarnings("deprecation") // 응답에서 사라지지 않았는지 확인해야 하므로 계속 채운다.
    private Event campus(Event event, String campusScope) {
        event.setCampusScope(campusScope);
        return event;
    }

    private Event external(Event event, String externalId) {
        event.setExternalId(externalId);
        return event;
    }
}
