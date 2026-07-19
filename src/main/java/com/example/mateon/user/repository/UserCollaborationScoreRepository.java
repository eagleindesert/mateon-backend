package com.example.mateon.user.repository;

import com.example.mateon.user.domain.UserCollaborationScore;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface UserCollaborationScoreRepository extends JpaRepository<UserCollaborationScore, Long> {

    /**
     * 여러 유저의 온도를 한 번에. 팀 상세처럼 사람이 여러 명인 화면에서 유저당 조회를 돌면 N+1 이다.
     * 평가를 한 번도 안 받은 유저는 행이 없으므로 호출부에서 '비공개'로 채워야 한다.
     */
    List<UserCollaborationScore> findByUserIdIn(Collection<Long> userIds);
}
