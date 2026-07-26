package com.example.mateon.events.service;

import com.example.mateon.common.PageLimits;
import com.example.mateon.events.dto.EventResponseDTO;
import com.example.mateon.events.models.Event;
import com.example.mateon.events.models.Event.Category;
import com.example.mateon.events.models.Event.Field;
import com.example.mateon.events.repository.EventRepository;
import com.example.mateon.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;

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
 * DB 는 {@link IntegrationTestBase} 가 띄우는 빈 컨테이너라, 검색 결과에는 이 테스트가 심은
 * 활동만 들어 있다. 덕분에 결과 전건을 그대로 단정할 수 있다 — "이것만 나온다"와 "이 순서로
 * 나온다"가 실제로 검증된다. @Transactional 이라 끝나면 전부 롤백된다.
 */
class EventSearchIntegrationTest extends IntegrationTestBase {

    @Autowired EventService eventService;
    @Autowired EventRepository eventRepository;

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
    @DisplayName("키워드는 제목·설명·주최 중 하나만 맞아도 잡고, 어디에도 없으면 빠진다")
    void filtersByKeywordAcrossTitleDescriptionOrganizer() {
        Long inTitle = saveText("인공지능 공모전", null, null);
        Long inDescription = saveText(null, "설명에 인공지능 포함", null);
        Long inOrganizer = saveText(null, null, "인공지능재단");
        saveText(null, null, null); // 키워드가 어디에도 없음 — 안 잡힌다

        assertThat(searchIds(null, null, null, null, "인공지능"))
                .containsExactlyInAnyOrder(inTitle, inDescription, inOrganizer);
    }

    @Test
    @DisplayName("키워드가 대소문자만 다르면 그대로 잡는다")
    void keywordIsCaseInsensitive() {
        Long e = saveText("AiToken 대회", null, null);

        assertThat(searchIds(null, null, null, null, "aitoken")).containsExactly(e);
    }

    @Test
    @DisplayName("키워드가 비었으면 필터를 걸지 않는다 (required=false 라 null/빈 문자열로 들어온다)")
    void treatsBlankKeywordAsNoFilter() {
        Long a = saveText("A", null, null);
        Long b = saveText(null, "B", null);

        assertThat(searchIds(null, null, null, null, (String) null)).containsExactlyInAnyOrder(a, b);
        assertThat(searchIds(null, null, null, null, "")).containsExactlyInAnyOrder(a, b);
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
     * 검색 결과의 활동 id 를 순서 그대로 뽑는다.
     * 이 테스트들은 필터·정렬 결과 전건을 검증하므로, 페이지 크기는 상한(MAX_PAGE_SIZE)까지 열어
     * 심은 활동이 한 페이지 안에 다 들어오게 한다(한 테스트가 심는 건 많아야 몇 건이다).
     */
    private List<Long> searchIds(String college, String school, Category category, Field field) {
        return searchIds(college, school, category, field, null);
    }

    private List<Long> searchIds(String college, String school, Category category, Field field, String keyword) {
<<<<<<< HEAD
        // userId 는 null — 이 테스트는 필터/정렬만 본다. 북마크 여부는 응답 필드일 뿐 검색 결과를 바꾸지 않는다.
        return eventService.search(college, school, category, field, keyword, 0, PageLimits.MAX_PAGE_SIZE, null)
                .stream()
                .filter(dto -> dto.getTitle() != null && dto.getTitle().contains(tag))
=======
        return eventService.search(college, school, category, field, keyword, 0, EventService.MAX_PAGE_SIZE).stream()
>>>>>>> main
                .map(EventResponseDTO::getId)
                .toList();
    }

    private Long save(Category category, Field field, String targetColleges, String targetSchool) {
        return save(category, field, targetColleges, targetSchool, null);
    }

    /**
     * 키워드 검색용 텍스트를 심는다. 제목은 NOT NULL 이라 비워둘 수 없으므로,
     * 검색어를 넣지 않는 행에는 어느 키워드에도 걸리지 않는 제목을 채운다.
     */
    private Long saveText(String title, String description, String organizer) {
        Event event = new Event();
        event.setCategory(Category.CONTEST);
        event.setField(Field.ETC);
        event.setTitle(title != null ? title : "검색어 없는 활동");
        event.setDescription(description);
        event.setOrganizer(organizer);
        return eventRepository.saveAndFlush(event).getId();
    }

    @SuppressWarnings("deprecation") // 단과대 필터가 살아 있는 동안은 이 축도 계속 검증한다.
    private Long save(Category category, Field field, String targetColleges, String targetSchool,
                      LocalDate startDate) {
        Event event = new Event();
        event.setCategory(category);
        // field 는 이제 NOT NULL 이다. 분야가 관심사가 아닌 케이스는 null 로 넘어오므로
        // ETC 로 채워 저장만 성립시킨다(테스트가 검색하는 분야와 겹치지 않는다).
        event.setField(field != null ? field : Field.ETC);
        event.setTitle("검색 테스트 활동");
        event.setTarget_colleges(targetColleges);
        event.setTargetSchool(targetSchool);
        event.setStartDate(startDate);
        return eventRepository.saveAndFlush(event).getId();
    }
}
