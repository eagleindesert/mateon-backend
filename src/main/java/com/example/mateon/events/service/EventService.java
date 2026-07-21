package com.example.mateon.events.service;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.events.dto.EventRequestDTO;
import com.example.mateon.events.dto.EventResponseDTO;
import com.example.mateon.events.models.Event;
import com.example.mateon.events.models.Event.Category;
import com.example.mateon.events.models.Event.Field;
import com.example.mateon.events.repository.EventRepository;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final EventMatchingService eventMatchingService;
    private final UserRepository userRepository;

    /**
     * 활동(공모전 등) 등록.
     * 중복 검사는 하지 않는다 — external_id 의 UNIQUE 제약을 V18 에서 해제했고,
     * 같은 활동을 두 번 올리는 것은 등록자가 판단할 문제다.
     * embeddingVector 는 채우지 않는다. 추천 점수(EventMatchingService)는 키워드/전공/캠퍼스
     * 문자열 매칭만 쓰므로 비어 있어도 정상 동작한다.
     */
    @Transactional
    public EventResponseDTO createEvent(EventRequestDTO request) {
        return new EventResponseDTO(eventRepository.save(request.toEntity()));
    }

    /**
     * 활동 검색. 로그인한 사용자면(userId != null) 관련도가 높은 순으로 정렬해 돌려준다.
     *
     * @param userId 비로그인이면 null
     */
    @Transactional(readOnly = true)
    public List<EventResponseDTO> search(String college, Category category, Field field, Long userId) {
        List<Event> events = findByCollegeAndCategory(college, category);

        // 분야 필터는 메모리에서 적용한다. 위 조회는 native query 조합이라 분야까지 넣으면 분기가
        // 8가지로 늘어나는데, 어차피 아래 정렬에서 목록 전체를 메모리에 올리므로 비용 구조는 같다.
        if (field != null) {
            events = events.stream()
                    .filter(event -> field == event.getField())
                    .collect(Collectors.toList());
        }

        User user = userId == null ? null : userRepository.findById(userId).orElse(null);
        if (user != null) {
            events = events.stream()
                    .sorted(byRelevance(scoreAll(events, user)))
                    .collect(Collectors.toList());
        }

        return toResponse(events);
    }

    /**
     * 홈화면 맞춤 활동 추천.
     * category 를 주면 그 안에서 1개, 안 주면 카테고리마다 1개씩 뽑는다.
     */
    @Transactional(readOnly = true)
    public List<EventResponseDTO> recommend(Category category, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new MateonException(ErrorCode.USER_NOT_FOUND));

        List<Event> candidates = category != null
                ? eventRepository.findByCategory(category)
                : eventRepository.findAll();

        // 점수는 여기서 한 번만 계산해 비교자에 넘긴다. 비교자 안에서 계산하면 정렬 중
        // 같은 활동의 점수를 O(n log n) 번 다시 구하게 된다.
        Comparator<Event> byRelevance = byRelevance(scoreAll(candidates, user));

        List<Event> recommended = category != null
                ? candidates.stream().sorted(byRelevance).limit(1).collect(Collectors.toList())
                : bestPerCategory(candidates, byRelevance);

        return toResponse(recommended);
    }

    /** 여러 카테고리가 섞여 보이도록 무작위 정렬해 전체를 돌려준다. */
    @Transactional(readOnly = true)
    public List<EventResponseDTO> findAllRandomly() {
        return toResponse(eventRepository.findAllRandomly());
    }

    private List<Event> findByCollegeAndCategory(String college, Category category) {
        // '전체'는 필터를 걸지 않겠다는 뜻이라 미지정과 같이 취급한다.
        boolean hasCollege = college != null && !college.trim().isEmpty() && !college.equalsIgnoreCase("전체");

        if (hasCollege && category != null) {
            return eventRepository.findByCategoryAndTargetCollege(category.name(), college);
        }
        if (hasCollege) {
            return eventRepository.findByTargetCollegeName(college);
        }
        if (category != null) {
            return eventRepository.findByCategory(category);
        }
        return eventRepository.findAll();
    }

    /** 카테고리마다 가장 앞순위인 활동 1개씩 고른 뒤, 그 대표들끼리 다시 줄 세운다. */
    private List<Event> bestPerCategory(List<Event> events, Comparator<Event> byRelevance) {
        Map<Category, Event> best = new HashMap<>();
        for (Event event : events) {
            best.merge(event.getCategory(), event,
                    (current, candidate) -> byRelevance.compare(candidate, current) < 0 ? candidate : current);
        }
        return best.values().stream().sorted(byRelevance).collect(Collectors.toList());
    }

    private Map<Event, Integer> scoreAll(List<Event> events, User user) {
        Map<Event, Integer> scores = new HashMap<>();
        for (Event event : events) {
            scores.put(event, eventMatchingService.calculateRelevanceScore(user, event));
        }
        return scores;
    }

    /**
     * 관련도 점수 내림차순, 같으면 최신순.
     * 등록일이 없는 활동끼리는 순서를 정하지 않는다(0 반환) — 정렬이 안정적이라 원래 순서가 유지된다.
     */
    private static Comparator<Event> byRelevance(Map<Event, Integer> scores) {
        return (e1, e2) -> {
            int score1 = scores.getOrDefault(e1, 0);
            int score2 = scores.getOrDefault(e2, 0);
            if (score1 != score2) {
                return Integer.compare(score2, score1);
            }
            if (e1.getCreatedAt() != null && e2.getCreatedAt() != null) {
                return e2.getCreatedAt().compareTo(e1.getCreatedAt());
            }
            return 0;
        };
    }

    private List<EventResponseDTO> toResponse(List<Event> events) {
        return events.stream()
                .map(EventResponseDTO::new)
                .collect(Collectors.toList());
    }
}
