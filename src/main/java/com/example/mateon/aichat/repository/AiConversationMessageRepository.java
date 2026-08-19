package com.example.mateon.aichat.repository;

import com.example.mateon.aichat.domain.AiChatRole;
import com.example.mateon.aichat.domain.AiConversationMessage;
import com.example.mateon.aichat.domain.RoutableDomain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AiConversationMessageRepository extends JpaRepository<AiConversationMessage, Long> {

    /**
     * 특정 도메인 세션의 대화 전체 (USER + ASSISTANT). 그 도메인의 복원 API 가 쓴다.
     *
     * <p>대화 전체가 아니라 도메인 소관만 돌려준다 — 매칭 세션 복원에 게이트웨이의 되묻기
     * 턴이나 다른 도메인 발화가 끼면 그 API 의 기존 계약이 달라진다.
     */
    List<AiConversationMessage> findByDomainAndDomainRefIdOrderBySeqAsc(RoutableDomain domain,
                                                                       Long domainRefId);

    /**
     * 특정 도메인 세션에 속한 발화만 순서대로. 도메인 AI 로 보낼 배열을 만들 때 쓴다.
     *
     * <p>{@code domainRefId} 로 좁히는 게 핵심이다 — 도메인만으로 거르면 같은 대화에서
     * 완료된 옛 세션의 발화까지 딸려 와 새 세션의 추출 결과를 오염시킨다.
     *
     * <p>문자열만 뽑는 이유는 이 값이 TX 밖에서 쓰이기 때문이다 (엔티티를 넘기면
     * LazyInitializationException). ConversationSnapshot 주석 참고.
     */
    @Query("SELECT m.content FROM AiConversationMessage m "
           + "WHERE m.domain = :domain AND m.domainRefId = :domainRefId AND m.role = :role "
           + "ORDER BY m.seq ASC")
    List<String> findContentsByDomainRefAndRole(@Param("domain") RoutableDomain domain,
                                                @Param("domainRefId") Long domainRefId,
                                                @Param("role") AiChatRole role);

    /** 다음 seq 계산용. */
    int countByConversationId(Long conversationId);
}
