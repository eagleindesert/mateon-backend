package com.example.mateon.events.repository;// EventRepository.java

import com.example.mateon.events.models.Event;
import com.example.mateon.events.models.Event.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
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

    // 여러 카테고리가 섞여 보이도록 무작위 정렬하되, 응답 크기를 묶기 위해 size 건까지만 가져온다.
    // (RANDOM() 은 호출마다 재정렬되므로 offset 페이징은 의미가 없다 — LIMIT 로 표본만 자른다.)
    @Query(value = "SELECT * FROM events ORDER BY RANDOM() LIMIT :size", nativeQuery = true)
    List<Event> findAllRandomly(@Param("size") int size);

    /**
     * 임베딩 벡터가 없는 활동만 가져온다. 한 틱에 처리할 상한만 자른다.
     *
     * <p>
     * 벡터가 있으면 후보가 아니다 — 성공이든, 성공 후 실패한 낡은 값이든. 재임베딩이 아니다.
     * 행이 없으면 아직 시도 전이라 무조건 넣는다. 행은 있는데 embedding 이 NULL 이면 한 번도
     * 성공하지 못한 것이고, 연속 실패가 한도 미만이며 마지막 시도가 retryBefore 이전이면
     * 다시 집어 채운다. 한도를 넘긴 행은 여기서 끝나 뒤 id 가 LIMIT 안으로 들어온다.
     */
    @Query(value = """
      SELECT e.id FROM events e
      LEFT JOIN event_embeddings emb ON emb.event_id = e.id
      WHERE (emb.event_id IS NULL OR emb.embedding IS NULL)
        AND (
          emb.event_id IS NULL
          OR (
            emb.consecutive_failures < :maxFailures
            AND (emb.last_attempted_at IS NULL
              OR emb.last_attempted_at < :retryBefore)
          )
        )
      ORDER BY e.id
      LIMIT :limit
      """, nativeQuery = true)
    List<Long> findIdsNeedingEmbedding(
      @Param("limit") int limit,
      @Param("maxFailures") int maxFailures,
      @Param("retryBefore") LocalDateTime retryBefore);
}
