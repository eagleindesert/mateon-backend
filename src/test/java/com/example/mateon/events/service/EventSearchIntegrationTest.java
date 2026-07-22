package com.example.mateon.events.service;

import com.example.mateon.events.dto.EventResponseDTO;
import com.example.mateon.events.models.Event;
import com.example.mateon.events.models.Event.Category;
import com.example.mateon.events.models.Event.Field;
import com.example.mateon.events.repository.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 활동 검색 필터를 실제 DB 에 대고 확인한다.
 *
 * <p>
 * 필터가 Specification(EventSearchSpecs)으로 옮겨간 뒤로는 목으로 검증할 수 없다 —
 * 리포지토리를 목으로 두면 Specification 이 실행되지 않아 무엇을 걸렀는지 알 수 없기 때문이다.
 * 그래서 이 계층만 통합 테스트로 둔다.
 *
 * <p>
 * 개발 DB 에는 다른 활동이 이미 쌓여 있으므로, 검색 결과 전체를 세지 않고 이번 테스트가 심은
 * 활동만 골라 본다(제목에 넣은 tag 로 식별). @Transactional 이라 끝나면 전부 롤백된다.
 */
@SpringBootTest
@Transactional
class EventSearchIntegrationTest {

    @Autowired EventService eventService;
    @Autowired EventRepository eventRepository;

    /** 이번 실행이 심은 활동만 골라내기 위한 표식. 개발 DB 의 기존 데이터와 섞이면 안 된다. */
    private String tag;

    @BeforeEach
    void setUp() {
        tag = "search-test-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("분야 필터를 주면 그 분야의 활동만 남는다")
    void filtersByField() {
        Long it = save(Category.CONTEST, Field.SCIENCE_ENGINEERING_TECH_IT, null, null);
        save(Category.CONTEST, Field.DESIGN_PHOTO_ART_VIDEO, null, null);
        save(Category.CONTEST, null, null, null);

        assertThat(searchIds(null, null, null, Field.SCIENCE_ENGINEERING_TECH_IT)).containsExactly(it);
    }

    @Test
    @DisplayName("분야가 여럿인 공고는 행을 나눠 등록하므로 각 분야 검색에 각자 잡힌다")
    void splitRowsAppearInEachFieldSearch() {
        Long planning = save(Category.CONTEST, Field.PLANNING_IDEA, null, null);
        Long science = save(Category.CONTEST, Field.SCIENCE_ENGINEERING_TECH_IT, null, null);

        assertThat(searchIds(null, null, null, Field.PLANNING_IDEA)).containsExactly(planning);
        assertThat(searchIds(null, null, null, Field.SCIENCE_ENGINEERING_TECH_IT)).containsExactly(science);
    }

    @Test
    @DisplayName("카테고리 필터를 주면 그 카테고리의 활동만 남는다")
    void filtersByCategory() {
        Long contest = save(Category.CONTEST, null, null, null);
        save(Category.EXTERNAL, null, null, null);

        assertThat(searchIds(null, null, Category.CONTEST, null)).containsExactly(contest);
    }

    @Test
    @DisplayName("대학교 필터를 주면 그 학교를 대상으로 하는 활동만 남는다")
    void filtersBySchool() {
        Long dankook = save(Category.SCHOOL, null, null, "단국대학교");
        save(Category.SCHOOL, null, null, "고려대학교");
        save(Category.CONTEST, null, null, null); // 전국 대상 — 학교로 좁힌 검색에는 안 잡힌다

        assertThat(searchIds(null, "단국대학교", null, null)).containsExactly(dankook);
    }

    @Test
    @DisplayName("대학교 필터는 부분일치라 표기가 짧아도, 콤마로 여러 학교가 들어와도 잡는다")
    void matchesSchoolPartially() {
        Long several = save(Category.SCHOOL, null, null, "단국대학교,고려대학교");

        assertThat(searchIds(null, "단국대", null, null)).containsExactly(several);
        assertThat(searchIds(null, "고려대학교", null, null)).containsExactly(several);
    }

    @Test
    @DisplayName("단과대학 필터도 그대로 동작한다 (deprecated 지만 아직 살아 있다)")
    void filtersByCollege() {
        Long sw = save(Category.SCHOOL, null, "SW융합대학", null);
        save(Category.SCHOOL, null, "문과대학", null);

        assertThat(searchIds("SW융합대학", null, null, null)).containsExactly(sw);
    }

    @Test
    @DisplayName("'전체'는 필터를 걸지 않겠다는 뜻이라 미지정과 같이 취급한다")
    void treatsAllAsNoFilter() {
        Long a = save(Category.SCHOOL, null, "SW융합대학", "단국대학교");
        Long b = save(Category.SCHOOL, null, "문과대학", "고려대학교");

        assertThat(searchIds("전체", "전체", null, null)).containsExactlyInAnyOrder(a, b);
    }

    @Test
    @DisplayName("필터를 여러 개 주면 전부 만족하는 활동만 남는다")
    void combinesFilters() {
        Long match = save(Category.CONTEST, Field.PLANNING_IDEA, "SW융합대학", "단국대학교");
        save(Category.CONTEST, Field.PLANNING_IDEA, "SW융합대학", "고려대학교"); // 학교 불일치
        save(Category.CONTEST, Field.DESIGN_PHOTO_ART_VIDEO, "SW융합대학", "단국대학교"); // 분야 불일치
        save(Category.SCHOOL, Field.PLANNING_IDEA, "SW융합대학", "단국대학교"); // 카테고리 불일치

        assertThat(searchIds("SW융합대학", "단국대학교", Category.CONTEST, Field.PLANNING_IDEA))
                .containsExactly(match);
    }

    @Test
    @DisplayName("시작일이 최근인 활동이 앞에 온다")
    void sortsByStartDate() {
        Long oldest = save(Category.CONTEST, null, null, null, LocalDate.of(2026, 1, 1));
        Long newest = save(Category.CONTEST, null, null, null, LocalDate.of(2026, 7, 1));
        Long middle = save(Category.CONTEST, null, null, null, LocalDate.of(2026, 4, 1));

        assertThat(searchIds(null, null, Category.CONTEST, null)).containsExactly(newest, middle, oldest);
    }

    @Test
    @DisplayName("시작일이 없는 활동은 맨 뒤로 밀린다")
    void putsEventsWithoutStartDateLast() {
        Long noDate = save(Category.EXTERNAL, null, null, null, null);
        Long dated = save(Category.EXTERNAL, null, null, null, LocalDate.of(2020, 1, 1));

        assertThat(searchIds(null, null, Category.EXTERNAL, null)).containsExactly(dated, noDate);
    }

    @Test
    @DisplayName("시작일이 같으면 나중에 등록된 활동이 앞에 온다")
    void breaksStartDateTieByNewestRow() {
        LocalDate sameDay = LocalDate.of(2026, 3, 2);
        Long earlier = save(Category.SCHOOL, null, null, null, sameDay);
        Long later = save(Category.SCHOOL, null, null, null, sameDay);

        assertThat(searchIds(null, null, Category.SCHOOL, null)).containsExactly(later, earlier);
    }

    // --- 헬퍼 ---

    /**
     * 검색 결과에서 이번 테스트가 심은 활동의 id 만 순서대로 뽑는다.
     * 이 테스트들은 필터·정렬 결과 전건을 검증하므로, 페이지 크기는 상한(MAX_PAGE_SIZE)까지 열어
     * 심은 활동이 한 페이지 안에 다 들어오게 한다.
     */
    private List<Long> searchIds(String college, String school, Category category, Field field) {
        return eventService.search(college, school, category, field, 0, EventService.MAX_PAGE_SIZE).stream()
                .filter(dto -> dto.getTitle() != null && dto.getTitle().contains(tag))
                .map(EventResponseDTO::getId)
                .toList();
    }

    private Long save(Category category, Field field, String targetColleges, String targetSchool) {
        return save(category, field, targetColleges, targetSchool, null);
    }

    @SuppressWarnings("deprecation") // 단과대 필터가 살아 있는 동안은 이 축도 계속 검증한다.
    private Long save(Category category, Field field, String targetColleges, String targetSchool,
                      LocalDate startDate) {
        Event event = new Event();
        event.setCategory(category);
        event.setField(field);
        event.setTitle(tag + " " + UUID.randomUUID());
        event.setTarget_colleges(targetColleges);
        event.setTargetSchool(targetSchool);
        event.setStartDate(startDate);
        return eventRepository.saveAndFlush(event).getId();
    }
}
