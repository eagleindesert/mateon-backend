package com.example.mateon.events.controller;

import com.example.mateon.common.dto.ApiResponse;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.events.dto.EventRequestDTO;
import com.example.mateon.events.dto.EventResponseDTO;
import com.example.mateon.events.models.Event;
import com.example.mateon.events.models.Event.Category;
import com.example.mateon.events.models.Event.Field;
import com.example.mateon.events.repository.EventRepository;
import com.example.mateon.events.service.EventMatchingService;
import com.example.mateon.events.service.EventService;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventRepository eventRepository;
    private final EventMatchingService eventMatchingService;
    private final UserRepository userRepository;
    private final EventService eventService;

    /**
     * 활동(공모전 등) 등록 [인증 필수]
     * 인증 여부는 SecurityConfig 의 POST /api/events 매처가 강제한다.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<EventResponseDTO>> createEvent(
            @Valid @RequestBody EventRequestDTO request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(eventService.createEvent(request)));
    }

    @GetMapping("/search")
    public ApiResponse<List<EventResponseDTO>> searchEvents(
            @RequestParam(required = false) String college,
            @RequestParam(required = false) Category category,
            @RequestParam(required = false) Field field,
            Authentication authentication  // 인증된 사용자 정보 (선택적)
    ) {
        List<Event> events;

        boolean isCollegeFilterApplied = college != null && !college.trim().isEmpty() && !college.equalsIgnoreCase("전체");
        boolean isCategoryFilterApplied = category != null;

        // 1. 단과대학 필터 + 카테고리 필터 적용
        if (isCollegeFilterApplied && isCategoryFilterApplied) {
            events = eventRepository.findByCategoryAndTargetCollege(category.name(), college);
        }
        // 2. 단과대학 필터만 적용
        else if (isCollegeFilterApplied) {
            events = eventRepository.findByTargetCollegeName(college);
        }
        // 3. 카테고리 필터만 적용
        else if (isCategoryFilterApplied) {
            events = eventRepository.findByCategory(category);
        }
        // 4. 전체 조회
        else {
            events = eventRepository.findAll();
        }

        // 분야 필터는 메모리에서 적용한다. 위 4가지 분기에 분야까지 넣으면 native query 조합이
        // 8가지로 늘어나는데, 이 메서드는 어차피 아래에서 목록 전체를 메모리에 올려 점수 정렬을
        // 하므로 비용 구조는 그대로다.
        if (field != null) {
            events = events.stream()
                    .filter(e -> field == e.getField())
                    .collect(Collectors.toList());
        }

        // 인증된 사용자가 있으면 키워드 매칭으로 정렬
        if (authentication != null) {
            Long userId = Long.valueOf(authentication.getName());
            User user = userRepository.findById(userId).orElse(null);

            if (user != null) {
                // 관련도 점수 계산 및 정렬
                Map<Event, Integer> eventScores = new HashMap<>();
                for (Event event : events) {
                    int score = eventMatchingService.calculateRelevanceScore(user, event);
                    eventScores.put(event, score);
                }

                // 점수가 높은 순으로 정렬 (점수가 같으면 최신순)
                events = events.stream()
                        .sorted((e1, e2) -> {
                            int score1 = eventScores.getOrDefault(e1, 0);
                            int score2 = eventScores.getOrDefault(e2, 0);
                            if (score1 != score2) {
                                return Integer.compare(score2, score1); // 내림차순
                            }
                            // 점수가 같으면 최신순
                            if (e1.getCreatedAt() != null && e2.getCreatedAt() != null) {
                                return e2.getCreatedAt().compareTo(e1.getCreatedAt());
                            }
                            return 0;
                        })
                        .collect(Collectors.toList());
            }
        }

        List<EventResponseDTO> response = events.stream()
                .map(EventResponseDTO::new)
                .collect(Collectors.toList());

        return ApiResponse.success(response);
    }

    /**
     * 홈화면 맞춤 활동 추천 API
     * 각 카테고리별로 사용자와 가장 관련도가 높은 활동 1개씩 반환
     *
     * @param category 카테고리 (CONTEST, EXTERNAL, SCHOOL). null이면 모든 카테고리에서 각각 1개씩 반환
     * @param authentication 인증된 사용자 정보 (필수)
     * @return 추천 활동 목록
     */
    @GetMapping("/recommended")
    public ApiResponse<List<EventResponseDTO>> getRecommendedEvents(
            @RequestParam(required = false) Category category,
            Authentication authentication
    ) {
        if (authentication == null) {
            throw new MateonException(ErrorCode.UNAUTHORIZED);
        }

        Long userId = Long.valueOf(authentication.getName());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new MateonException(ErrorCode.USER_NOT_FOUND));

        List<Event> allEvents;

        // 카테고리가 지정되면 해당 카테고리만, 아니면 전체
        if (category != null) {
            allEvents = eventRepository.findByCategory(category);
        } else {
            allEvents = eventRepository.findAll();
        }

        // 모든 이벤트에 대해 관련도 점수 계산
        Map<Event, Integer> eventScores = new HashMap<>();
        for (Event event : allEvents) {
            int score = eventMatchingService.calculateRelevanceScore(user, event);
            eventScores.put(event, score);
        }

        // 카테고리별로 가장 점수가 높은 이벤트 1개씩 선택
        List<Event> recommendedEvents;

        if (category != null) {
            // 특정 카테고리만 요청한 경우: 점수가 높은 순으로 정렬 후 상위 1개
            recommendedEvents = allEvents.stream()
                    .sorted((e1, e2) -> {
                        int score1 = eventScores.getOrDefault(e1, 0);
                        int score2 = eventScores.getOrDefault(e2, 0);
                        if (score1 != score2) {
                            return Integer.compare(score2, score1);
                        }
                        if (e1.getCreatedAt() != null && e2.getCreatedAt() != null) {
                            return e2.getCreatedAt().compareTo(e1.getCreatedAt());
                        }
                        return 0;
                    })
                    .limit(1)
                    .collect(Collectors.toList());
        } else {
            // 모든 카테고리에서 각각 1개씩 선택
            Map<Category, Event> bestByCategory = new HashMap<>();

            for (Event event : allEvents) {
                Category eventCategory = event.getCategory();
                int currentScore = eventScores.getOrDefault(event, 0);

                Event bestEvent = bestByCategory.get(eventCategory);
                if (bestEvent == null) {
                    bestByCategory.put(eventCategory, event);
                } else {
                    int bestScore = eventScores.getOrDefault(bestEvent, 0);
                    if (currentScore > bestScore) {
                        bestByCategory.put(eventCategory, event);
                    } else if (currentScore == bestScore && event.getCreatedAt() != null && bestEvent.getCreatedAt() != null) {
                        // 점수가 같으면 최신순
                        if (event.getCreatedAt().isAfter(bestEvent.getCreatedAt())) {
                            bestByCategory.put(eventCategory, event);
                        }
                    }
                }
            }

            recommendedEvents = bestByCategory.values().stream()
                    .sorted((e1, e2) -> {
                        int score1 = eventScores.getOrDefault(e1, 0);
                        int score2 = eventScores.getOrDefault(e2, 0);
                        if (score1 != score2) {
                            return Integer.compare(score2, score1);
                        }
                        if (e1.getCreatedAt() != null && e2.getCreatedAt() != null) {
                            return e2.getCreatedAt().compareTo(e1.getCreatedAt());
                        }
                        return 0;
                    })
                    .collect(Collectors.toList());
        }

        List<EventResponseDTO> response = recommendedEvents.stream()
                .map(EventResponseDTO::new)
                .collect(Collectors.toList());

        return ApiResponse.success(response);
    }

    // 기본 전체 조회 (옵션) 역시 DTO를 반환하도록 변경
    @GetMapping
    public ApiResponse<List<EventResponseDTO>> getAllEvents() {
        List<EventResponseDTO> response = eventRepository.findAllRandomly().stream()
                .map(EventResponseDTO::new)
                .collect(Collectors.toList());
        return ApiResponse.success(response);
    }
}