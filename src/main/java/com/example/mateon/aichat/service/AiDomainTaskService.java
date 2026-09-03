package com.example.mateon.aichat.service;

import com.example.mateon.aichat.domain.AiChatSession;
import com.example.mateon.aichat.domain.AiDomainTask;
import com.example.mateon.aichat.domain.AiDomainTaskStatus;
import com.example.mateon.aichat.domain.RoutableDomain;
import com.example.mateon.aichat.domain.TaskCloseReason;
import com.example.mateon.aichat.repository.AiChatSessionRepository;
import com.example.mateon.aichat.repository.AiDomainTaskRepository;
import com.example.mateon.common.ai.AiServerProperties;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 도메인 작업의 수명을 전담한다. 열고, 이어가고, 방치되면 만료시키고, 끝나면 닫는다.
 *
 * <p>
 * 이 로직이 도메인이 아니라 여기 있는 이유는 <b>게이트웨이가 도메인을 몰라야 하기 때문</b>
 * 이다. "이 대화 세션에 살아 있는 작업이 있나"는 라우팅 판단에 필요한 정보인데, 도메인마다 물으면
 * 도메인이 늘 때마다 게이트웨이에 호출이 하나씩 붙는다. 만료 규칙도 도메인마다 복제된다.
 *
 * <p>
 * V30 까지는 이 로직이 {@code MatchingIntentSessionService.resolveActiveSession} 안에만
 * 있었다. 그대로 옮겨 왔고, {@code flush()} 로 CLOSED 를 먼저 반영한 뒤 새 행을 넣는 부분까지
 * 유지한다 — 부분 유니크 인덱스(uk_ai_domain_tasks_active)와 충돌하기 때문이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AiDomainTaskService {

    private final AiDomainTaskRepository taskRepository;
    private final AiChatSessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final AiServerProperties properties;

    /**
     * 이 대화 세션에서 그 도메인 작업을 이어가거나 새로 연다.
     *
     * <p>
     * 세 갈래다:
     * <ul>
     * <li>이 대화 세션에 살아 있고 방치되지 않은 작업 → 그대로 이어간다 (touch).</li>
     * <li>방치된 작업 → EXPIRED 로 닫고 새로 연다. 배치 잡 없이, 사용자가 메시지를 보낸 이
     * 순간에 판정한다 — 만료 여부에 관심 있는 건 본인의 다음 요청뿐이다.</li>
     * <li><b>다른 대화 세션에 살아 있는 작업</b> → ABANDONED 로 닫고 여기서 새로 연다. 사용자당
     * 도메인당 1건이라 둘을 동시에 살릴 수 없고, 그냥 반환하면 이 대화 세션의 발화가 다른
     * 대화 세션의 작업에 쌓여 대화가 뒤섞인다.</li>
     * </ul>
     */
    public AiDomainTask openOrResume(Long chatSessionId, Long userId, RoutableDomain domain) {
        LocalDateTime threshold = LocalDateTime.now().minus(properties.getSessionTtl());

        Optional<AiDomainTask> active = taskRepository.findByUserIdAndDomainAndStatus(
          userId, domain, AiDomainTaskStatus.ACTIVE);

        if (active.isPresent()) {
            AiDomainTask task = active.get();

            if (!task.belongsTo(chatSessionId)) {
                log.info("다른 대화 세션의 진행 중인 작업을 정리하고 새로 연다: taskId={}, from={}, to={}",
                  task.getId(), task.getChatSession().getId(), chatSessionId);
                task.close(TaskCloseReason.ABANDONED);
                taskRepository.flush();
            } else if (task.isStaleAt(threshold)) {
                log.info("방치된 도메인 작업 만료 처리: taskId={}, domain={}, updatedAt={}",
                  task.getId(), domain, task.getUpdatedAt());
                task.close(TaskCloseReason.EXPIRED);
                taskRepository.flush();
            } else {
                task.touch();
                return task;
            }
        }

        AiChatSession session = sessionRepository.findById(chatSessionId)
          .orElseThrow(ErrorCode.AI_CHAT_SESSION_NOT_FOUND::toException);
        User user = userRepository.findById(userId)
          .orElseThrow(ErrorCode.USER_NOT_FOUND::toException);

        return taskRepository.save(new AiDomainTask(session, user, domain));
    }

    /**
     * 진행 중인 작업. 도메인의 복원·재시작 API 가 쓴다.
     */
    @Transactional(readOnly = true)
    public Optional<AiDomainTask> findActive(Long userId, RoutableDomain domain) {
        return taskRepository.findByUserIdAndDomainAndStatus(
          userId, domain, AiDomainTaskStatus.ACTIVE);
    }

    /**
     * 이 대화 세션에서 지금 살아 있는 도메인들. 게이트웨이가 라우터를 부를지 정할 때 쓴다.
     *
     * <p>
     * 방치된 작업은 빠진다 — 만료가 지연 처리라 아직 ACTIVE 로 남아 있어도, 라우팅 판단에서는
     * 죽은 것으로 봐야 한다. 그러지 않으면 어제 하던 이야기를 이어가는 것처럼 군다.
     */
    @Transactional(readOnly = true)
    public List<RoutableDomain> findLiveDomains(Long chatSessionId) {
        LocalDateTime threshold = LocalDateTime.now().minus(properties.getSessionTtl());
        return taskRepository.findLiveDomains(chatSessionId, AiDomainTaskStatus.ACTIVE, threshold);
    }

    /**
     * 작업을 끝낸다. 이미 닫혀 있으면 사유를 덮어쓰지 않는다.
     */
    public void close(Long taskId, TaskCloseReason reason) {
        taskRepository.findById(taskId)
          .orElseThrow(ErrorCode.RESOURCE_NOT_FOUND::toException)
          .close(reason);
    }
}
