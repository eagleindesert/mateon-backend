package com.example.mateon.events.repository;// EventRepository.java

import com.example.mateon.events.models.Event;
import com.example.mateon.events.models.Event.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface EventRepository extends JpaRepository<Event, Long> {

    // 1. Category로 조회 (예: '공모전', '대외활동' 탭)
    // Field: Event.category
    List<Event> findByCategory(Category category);

    // 2. target_colleges (대상 단과대학)로 조회 (예: '문과대학', '법과대학' 탭)
    // JSON 필드를 직접 쿼리해야 하므로 @Query를 사용할 수 있습니다.
    // (target_colleges 필드가 JSON 타입인 경우 복잡해지므로, 문자열 포함으로 가정합니다.)
    @Query(value = "SELECT * FROM events e WHERE e.target_colleges LIKE CONCAT('%', :collegeName, '%')", nativeQuery = true)
    List<Event> findByTargetCollegeName(@Param("collegeName") String collegeName);

    // 3. Category와 target_colleges를 모두 사용한 조회 (가장 복합적인 탭)
    @Query(value = "SELECT * FROM events e WHERE e.category = :category AND e.target_colleges LIKE CONCAT('%', :collegeName, '%')", nativeQuery = true)
    List<Event> findByCategoryAndTargetCollege(@Param("category") String category, @Param("collegeName") String collegeName);

    // 4. 모든 이벤트를 반환할 때 여러 카테고리들이 섞여서 보이도록
    @Query(value = "SELECT * FROM events ORDER BY RANDOM()", nativeQuery = true)
    List<Event> findAllRandomly();
}