package com.example.mateon.aichat.repository;

import com.example.mateon.aichat.domain.AiChatRole;
import com.example.mateon.aichat.domain.AiChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AiChatMessageRepository extends JpaRepository<AiChatMessage, Long> {

    /**
     * 특정 도메인 작업의 대화 전체 (USER + ASSISTANT). 그 도메인의 복원 API 가 쓴다.
     *
     * <p>
     * 스레드 전체가 아니라 작업 소관만 돌려준다 — 매칭 세션 복원에 게이트웨이의 되묻기
     * 턴이나 다른 도메인 발화가 끼면 그 API 의 기존 계약이 달라진다.
     */
    List<AiChatMessage> findByTaskIdOrderBySeqAsc(Long taskId);

    /**
     * 특정 도메인 작업에 속한 발화만 순서대로. 도메인 AI 로 보낼 배열을 만들 때 쓴다.
     *
     * <p>
     * 작업으로 좁히는 게 핵심이다 — 도메인만으로 거르면 같은 스레드에서 완료된 옛 작업의
     * 발화까지 딸려 와 새 작업의 추출 결과를 오염시킨다.
     *
     * <p>
     * 문자열만 뽑는 이유는 이 값이 TX 밖에서 쓰이기 때문이다 (엔티티를 넘기면
     * LazyInitializationException). ConversationSnapshot 주석 참고.
     */
    @Query("SELECT m.content FROM AiChatMessage m "
      + "WHERE m.task.id = :taskId AND m.role = :role ORDER BY m.seq ASC")
    List<String> findContentsByTaskAndRole(@Param("taskId") Long taskId,
      @Param("role") AiChatRole role);

    /**
     * 스레드 전체를 시간순으로. 사이드바에서 옛 대화를 열 때 쓴다.
     *
     * <p>
     * 여기는 작업으로 거르지 않는다 — 게이트웨이의 되묻기 턴도 사용자 눈에는 대화라서,
     * 빼고 그리면 자기가 한 말이 사라진 것처럼 보인다.
     *
     * <p>
     * {@code LEFT JOIN FETCH} 인 이유는 응답에 도메인 이름이 실리기 때문이다. 안 하면 행마다
     * 작업을 다시 읽어 N+1 이 되고, TX 밖에서 조립하면 LazyInitializationException 이 난다.
     * LEFT 여야 한다 — 게이트웨이 턴은 task 가 null 이라 INNER 면 통째로 빠진다.
     */
    @Query("SELECT m FROM AiChatMessage m LEFT JOIN FETCH m.task "
      + "WHERE m.chatSession.id = :chatSessionId ORDER BY m.seq ASC")
    List<AiChatMessage> findByChatSessionIdOrderBySeqAsc(@Param("chatSessionId") Long chatSessionId);
}
