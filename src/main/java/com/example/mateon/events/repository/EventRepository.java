package com.example.mateon.events.repository;// EventRepository.java

import com.example.mateon.events.models.Event;
import com.example.mateon.events.models.Event.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

/**
 * 검색 필터(카테고리/단과대/대학교/분야)는 메서드를 따로 만들지 않고
 * {@link JpaSpecificationExecutor} 로 조합한다 — 축마다 전용 쿼리를 두면 조합 분기가 축 수의
 * 거듭제곱으로 늘어난다(4축이면 16가지). Specification 은 지정된 조건만 AND 로 붙이므로
 * 축이 늘어도 조립 코드 한 줄만 추가하면 되고, 필터·정렬이 전부 DB 에서 끝난다.
 * 조립은 EventSearchSpecs 참고.
 */
@Repository
public interface EventRepository extends JpaRepository<Event, Long>, JpaSpecificationExecutor<Event> {

    // Category로 조회 (예: '공모전', '대외활동' 탭). /recommended 의 후보 추출에 쓴다.
    List<Event> findByCategory(Category category);

    // 모든 이벤트를 반환할 때 여러 카테고리들이 섞여서 보이도록
    @Query(value = "SELECT * FROM events ORDER BY RANDOM()", nativeQuery = true)
    List<Event> findAllRandomly();
}