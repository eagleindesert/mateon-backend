package com.example.mateon.aichat.repository;

import com.example.mateon.aichat.domain.AiDomainTask;
import com.example.mateon.aichat.domain.AiDomainTaskStatus;
import com.example.mateon.aichat.domain.RoutableDomain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AiDomainTaskRepository extends JpaRepository<AiDomainTask, Long> {

    /**
     * 진행 중인 작업을 찾는다. 사용자당 도메인당 ACTIVE 는 최대 1개라
     * (V31 의 uk_ai_domain_tasks_active) Optional 로 받는다.
     */
    Optional<AiDomainTask> findByUserIdAndDomainAndStatus(Long userId, RoutableDomain domain,
                                                         AiDomainTaskStatus status);

    /**
     * 이 스레드에서 지금 살아 있는 도메인들. 게이트웨이가 라우터를 부를지 정할 때 쓴다.
     *
     * <p><b>도메인을 모르고도 답할 수 있다는 게 이 조회의 요점이다.</b> 예전에는 매칭 서비스에
     * 직접 물었는데, 그러면 도메인이 늘 때마다 게이트웨이에 호출이 하나씩 붙는다.
     *
     * <p>{@code threshold} 로 방치된 작업을 빼는 게 중요하다. 만료는 지연 처리라 다음 발화
     * 시점에야 CLOSED 로 바뀌는데, 그전까지는 status 만 보면 살아 있어 보여서 라우터를 잘못
     * 건너뛴다 — 24 시간 전에 하던 이야기를 이어가는 것처럼 굴게 된다.
     */
    @Query("SELECT t.domain FROM AiDomainTask t "
           + "WHERE t.chatSession.id = :chatSessionId AND t.status = :status "
           + "AND t.updatedAt >= :threshold")
    List<RoutableDomain> findLiveDomains(@Param("chatSessionId") Long chatSessionId,
                                         @Param("status") AiDomainTaskStatus status,
                                         @Param("threshold") LocalDateTime threshold);
}
