package com.example.mateon.events.service;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.events.dto.EventRequestDTO;
import com.example.mateon.events.dto.EventResponseDTO;
import com.example.mateon.events.models.Event;
import com.example.mateon.events.models.Event.Category;
import com.example.mateon.events.models.Event.Field;
import com.example.mateon.events.repository.EventRepository;
import com.example.mateon.events.repository.EventSearchSpecs;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

    /**
     * 활동 시작일 최신순.
     *
     * <p>
     * DB 입력 시각(createdAt)이 아니라 공고가 가진 시작일로 줄 세운다. 크롤러가 과거 공고 수십 건을
     * 한 번에 넣으면 createdAt 이 전부 같은 시각이 되어 순서가 사실상 무작위가 되기 때문이다.
     * startDate 는 공고에서 온 값이라 언제 수집했는지와 무관하다.
     *
     * <p>
     * 시작일이 없는 활동은 뒤로 보내고, 시작일이 같으면 나중에 등록된 것(id 큰 쪽)을 앞에 둔다 —
     * 공모전은 시작일이 겹치는 경우가 흔한데, 2차 기준이 없으면 매 조회마다 순서가 달라진다.
     */
    private static final Sort BY_START_DATE
      = Sort.by(Sort.Order.desc("startDate").nullsLast(), Sort.Order.desc("id"));

    /**
     * 한 페이지 최대 건수. 목적이 과부하 방지이므로 클라이언트가 아무리 큰 size 를 보내도 여기서 자른다 —
     * 상한이 없으면 size=100000 한 방으로 전건 조회와 같아져 페이지네이션이 무의미해진다.
     */
    static final int MAX_PAGE_SIZE = 100;

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
     * 활동 검색. 시작일이 최근인 활동부터 내려준다({@link #BY_START_DATE}).
     *
     * <p>
     * 로그인 여부와 무관하게 순서가 같다. 한때는 로그인한 사용자에게 관련도 점수순으로 정렬해
     * 줬지만, 그 점수(EventMatchingService)는 공고문의 어휘를 반영할 뿐이라 순위를 신뢰할 수
     * 없었다 — /recommended 를 deprecated 처리한 이유와 같다. 신뢰할 수 없는 순서보다
     * 설명 가능한 순서가 낫다.
     *
     * <p>
     * 필터와 정렬은 전부 DB 에서 끝난다(EventSearchSpecs). 예전에는 목록 전체를 메모리에 올려
     * 걸렀는데, 그건 사용자별 점수 정렬 때문에 어차피 전건이 필요했던 시절의 구조다. 그 전제가
     * 사라졌으므로 테이블을 통째로 읽을 이유가 없다.
     *
     * <p>
     * 결과는 페이지 단위로 잘라 내려준다. 필터가 없으면 테이블 전체가 응답으로 나가 데이터가 쌓일수록
     * 트래픽이 무한정 커지기 때문이다. 정렬은 {@link #BY_START_DATE} 를 {@link Pageable} 에 실어
     * DB 에서 끝낸다 — 2차 기준(id desc)이 있어 페이지 경계가 매 조회마다 흔들리지 않는다.
     *
     * @param college 대상 단과대학. deprecated — school 로 전환 중이다.
     * @param school 대상 대학교
     * @param keyword 제목·설명·주최에 걸친 자유 검색어. 셋 중 하나라도 부분일치하면 잡힌다(OR). 비었거나 "전체"면 미적용.
     * @param page 0-기반 페이지 번호. 음수는 0 으로 취급한다.
     * @param size 페이지당 건수. {@link #MAX_PAGE_SIZE} 로 상한을 두고, 1 미만은 1 로 올린다.
     */
    @Transactional(readOnly = true)
    public List<EventResponseDTO> search(String college, String school, Category category, Field field,
      String keyword, int page, int size) {
        Pageable pageable = PageRequest.of(clampPage(page), clampSize(size), BY_START_DATE);
        return toResponse(
          eventRepository.findAll(EventSearchSpecs.of(college, school, category, field, keyword), pageable)
            .getContent());
    }

    private static int clampPage(int page) {
        return Math.max(page, 0);
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
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

    /**
     * 여러 카테고리가 섞여 보이도록 무작위 정렬한 표본을 size 건까지 돌려준다.
     *
     * <p>
     * offset 페이징은 걸지 않는다 — {@code ORDER BY RANDOM()} 은 호출마다 재정렬되므로 페이지를
     * 넘기면 중복·누락이 생긴다. 이 엔드포인트의 목적은 홈 화면용 '섞인 표본'이지 전건 순회가 아니라,
     * {@link #MAX_PAGE_SIZE} 로 상한을 둔 LIMIT 로 응답 크기만 묶는다.
     */
    @Transactional(readOnly = true)
    public List<EventResponseDTO> findAllRandomly(int size) {
        return toResponse(eventRepository.findAllRandomly(clampSize(size)));
    }

    /**
     * 카테고리마다 가장 앞순위인 활동 1개씩 고른 뒤, 그 대표들끼리 다시 줄 세운다.
     */
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
     * (검색은 DB 정렬(BY_START_DATE)을 쓴다. 이 비교자는 점수를 메모리에서 매기는 recommend 전용이다.)
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
