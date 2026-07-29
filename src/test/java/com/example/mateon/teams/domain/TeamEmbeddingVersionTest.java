package com.example.mateon.teams.domain;

import com.example.mateon.support.IntegrationTestBase;
import com.example.mateon.teams.repository.TeamEmbeddingRepository;
import com.example.mateon.teams.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * team_embeddings.version(V26) 이 실제로 증가하는지 실 DB 로 확인한다.
 *
 * <p>낙관적 락은 "버전이 올라간다"가 아니라 "안 올라가면 조용히 아무것도 못 막는다"가 무서운
 * 장치다. 매핑이 잘못돼 version 이 0 에 머물면 UPDATE 조건이 항상 참이 되어, 경합 방어가
 * 있는 것처럼 보이면서 실제로는 없는 상태가 된다.
 */
class TeamEmbeddingVersionTest extends IntegrationTestBase {

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private TeamEmbeddingRepository teamEmbeddingRepository;

    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("insert 는 version 0, 이후 저장마다 1씩 오른다")
    void versionIncrementsOnEachSave() {
        Team team = new Team();
        team.setTitle("버전 확인용 팀");
        team.setCapacity(3);
        teamRepository.save(team);

        TeamEmbedding created = new TeamEmbedding();
        created.setTeamId(team.getId());
        created.setSourceUpdatedAt(LocalDateTime.now());
        created.setRequiredSkills(List.of("Spring Boot"));
        teamEmbeddingRepository.save(created);
        flushAndDetach();

        TeamEmbedding afterInsert = teamEmbeddingRepository.findById(team.getId()).orElseThrow();
        assertThat(afterInsert.getVersion()).isZero();

        // 서비스와 같은 경로: 분리된 엔티티를 고쳐 save → merge → UPDATE
        afterInsert.setRequiredSkills(List.of("Java", "Redis"));
        teamEmbeddingRepository.save(afterInsert);
        flushAndDetach();

        TeamEmbedding afterUpdate = teamEmbeddingRepository.findById(team.getId()).orElseThrow();
        assertThat(afterUpdate.getVersion()).isEqualTo(1L);
        assertThat(afterUpdate.getRequiredSkills()).containsExactly("Java", "Redis");
    }

    /** 영속성 컨텍스트를 비워, 매번 DB 를 실제로 읽고 쓰게 만든다 (프로덕션의 트랜잭션 경계 흉내). */
    private void flushAndDetach() {
        em.flush();
        em.clear();
    }
}
