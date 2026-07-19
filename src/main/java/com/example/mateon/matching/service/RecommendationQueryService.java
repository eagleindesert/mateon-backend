package com.example.mateon.matching.service;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.events.models.Event;
import com.example.mateon.events.repository.EventRepository;
import com.example.mateon.matching.domain.MatchingIntentSlot;
import com.example.mateon.matching.dto.snapshot.RecommendationSnapshot;
import com.example.mateon.matching.dto.snapshot.TeamDisplayInfo;
import com.example.mateon.matching.repository.MatchingIntentSlotRepository;
import com.example.mateon.teams.domain.Team;
import com.example.mateon.teams.domain.TeamEmbedding;
import com.example.mateon.teams.repository.TeamApplicationRepository;
import com.example.mateon.teams.repository.TeamEmbeddingRepository;
import com.example.mateon.teams.repository.TeamMemberRepository;
import com.example.mateon.teams.repository.TeamRepository;
import com.example.mateon.user.domain.UserEmbedding;
import com.example.mateon.user.repository.UserEmbeddingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 추천에 필요한 DB 조회만 담당한다. FastAPI 호출은 여기서 하지 않는다 (RecommendationService 가 TX 밖에서
 * 호출한다 — 이유는 그쪽 주석 참고).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendationQueryService {

    /**
     * AI 로 보낼 후보 수 상한.
     *
     * <p>
     * 후보 하나당 1536 차원 벡터를 통째로 실어 보내므로 후보 수가 곧 페이로드 크기다. 현재 서비스 규모에서는 닿을 일이 없는
     * 값이고, 여기 걸리기 시작하면 pgvector HNSW 로 상위 후보만 1차 선별하도록 바꿔야 한다는 신호다 (그래서 조용히 자르지
     * 않고 warn 을 남긴다).
     */
    private static final int MAX_CANDIDATES = 200;

    private final UserEmbeddingRepository userEmbeddingRepository;
    private final MatchingIntentSlotRepository slotRepository;
    private final TeamRepository teamRepository;
    private final TeamEmbeddingRepository teamEmbeddingRepository;
    private final TeamApplicationRepository teamApplicationRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final EventRepository eventRepository;

    /**
     * 질의(사용자)와 후보(팀)를 모아 detach 된 스냅샷으로 돌려준다.
     *
     * @param eventId 지정 시 해당 활동의 팀만 후보로 삼는다. null 이면 전역.
     */
    public RecommendationSnapshot gather(Long userId, Long eventId) {
        // ── 질의 쪽: 의도 추출이 완료돼야 벡터와 슬롯이 둘 다 생긴다 ──────────────
        // 하나만 있는 상태는 정상 흐름에서 나오지 않지만(같은 트랜잭션에서 함께 저장된다),
        // 어느 쪽이 없든 사용자가 할 일은 "의도 추출 완료" 하나뿐이라 같은 에러로 묶는다.
        UserEmbedding userEmbedding = userEmbeddingRepository.findById(userId)
          .orElseThrow(() -> new MateonException(ErrorCode.MATCHING_INTENT_REQUIRED));
        MatchingIntentSlot slot = slotRepository.findByUserId(userId)
          .orElseThrow(() -> new MateonException(ErrorCode.MATCHING_INTENT_REQUIRED));

        // ── 후보 쪽 ────────────────────────────────────────────────────────────
        List<Team> teams = eventId != null
          ? teamRepository.findByEventIdAndIsRecruitingTrue(eventId)
          : teamRepository.findByIsRecruitingTrue();

        Set<Long> excluded = excludedTeamIds(userId);
        List<Team> filtered = teams.stream()
          .filter(team -> !excluded.contains(team.getId()))
          .toList();

        // 임베딩이 없는 팀은 후보가 될 수 없다 (팀 생성 직후 비동기 갱신이 아직 안 끝났거나 실패).
        // V10 부터 행이 있어도 벡터가 null 일 수 있다 — 갱신에 한 번도 성공 못 한 팀의 실패 상태만
        // 기록된 행이다. 여기서 걸러 두면 아래 containsKey 가 "쓸 수 있는 임베딩"을 뜻하게 된다.
        Map<Long, TeamEmbedding> embeddings = teamEmbeddingRepository
          .findAllById(filtered.stream().map(Team::getId).toList())
          .stream()
          .filter(embedding -> embedding.getEmbedding() != null)
          .collect(Collectors.toMap(TeamEmbedding::getTeamId, Function.identity()));

        // 상한을 임베딩 확인 뒤에 적용하는 이유: 먼저 자르면 임베딩 없는 팀이 자리를 차지해
        // 실제 후보가 상한보다 적어진다.
        List<Team> withEmbedding = filtered.stream()
          .filter(team -> embeddings.containsKey(team.getId()))
          .toList();

        // 일부만 누락된 경우는 아래 "후보 0건" 분기에 걸리지 않아 그냥 묻힌다. 50팀 중 3팀이 계속
        // 추천에서 빠져도 알 수 없으므로 여기서 남긴다 (비동기 갱신 실패의 조기 신호).
        // 상한 적용 전에 세야 한다 — candidates 는 MAX_CANDIDATES 로 잘려 차이가 부풀려진다.
        int missingEmbedding = filtered.size() - withEmbedding.size();
        if (missingEmbedding > 0) {
            log.warn("임베딩이 없어 추천 후보에서 제외된 팀 {}건 (모집 중 {}건 중). "
              + "team_embeddings.refresh_status 로 갱신 실패 여부를 확인하세요. userId={}, eventId={}",
              missingEmbedding, filtered.size(), userId, eventId);
        }

        List<RecommendationSnapshot.Candidate> candidates = withEmbedding.stream()
          // 상한에 걸릴 때 무엇이 남는지가 결정적이어야 한다 — 최신 팀 우선.
          .sorted(Comparator.comparing(Team::getId).reversed())
          .limit(MAX_CANDIDATES)
          .map(team -> new RecommendationSnapshot.Candidate(team, embeddings.get(team.getId())))
          .toList();

        // 후보 0건은 정상(모집 중인 팀이 없음)일 수도, 사고(팀은 있는데 임베딩이 하나도 없음)일
        // 수도 있다. 응답은 둘 다 빈 배열이라 밖에서는 구분이 안 되므로 여기서 갈라 남긴다.
        if (candidates.isEmpty()) {
            if (filtered.isEmpty()) {
                log.info("추천 후보 없음 - 모집 중인 팀이 없습니다. userId={}, eventId={}, 조회={}건, 제외={}건",
                  userId, eventId, teams.size(), teams.size() - filtered.size());
            } else {
                log.warn("추천 후보 없음 - 모집 중인 팀 {}건이 있으나 임베딩이 하나도 없습니다. "
                  + "팀 임베딩 비동기 갱신(TeamEmbeddingRefreshListener) 실패를 의심하세요. userId={}, eventId={}",
                  filtered.size(), userId, eventId);
            }
        }

        if (candidates.size() == MAX_CANDIDATES) {
            log.warn("추천 후보가 상한({})에 도달했습니다. 일부 팀이 추천에서 제외됩니다. "
              + "pgvector 기반 1차 선별 도입을 검토하세요. userId={}, eventId={}",
              MAX_CANDIDATES, userId, eventId);
        }

        return new RecommendationSnapshot(
          userEmbedding.getEmbedding(),
          slot.getDesiredRoles(), slot.getSkills(),
          slot.getActivityStyle(), slot.getExperienceLevel(),
          candidates);
    }

    /**
     * 응답에 실을 팀들의 표시 정보(연결된 활동, 확정 인원)를 한 번에 모아 온다.
     *
     * <p>
     * AI 가 점수를 매기고 상위 N 건으로 자른 뒤에 호출한다 — 후보 전체(최대 200)가 아니라 실제로 내려보낼 것만 조회하면 되기
     * 때문이다.
     *
     * <p>
     * 팀마다 findById/count 를 도는 대신 활동 1회 + 인원 집계 1회로 끝낸다 (TeamService.getTeams 는
     * 팀당 두 번씩 조회하는 N+1 이라 그 방식은 따르지 않는다).
     */
    public Map<Long, TeamDisplayInfo> loadDisplayInfo(List<Long> teamIds, Map<Long, Team> teamsById) {
        if (teamIds.isEmpty()) {
            return Map.of();
        }

        // 자율 프로젝트(eventId=null)는 조회할 활동이 없다.
        List<Long> eventIds = teamIds.stream()
          .map(teamId -> teamsById.get(teamId).getEventId())
          .filter(Objects::nonNull)
          .distinct()
          .toList();

        Map<Long, Event> eventsById = eventIds.isEmpty() ? Map.of()
          : eventRepository.findAllById(eventIds).stream()
            .collect(Collectors.toMap(Event::getId, Function.identity()));

        // 멤버가 없는 팀은 집계 결과에 아예 없다 → 아래에서 0 으로 채운다.
        Map<Long, Long> memberCounts = teamMemberRepository
          .countGroupedByTeamId(teamIds)
          .stream()
          .collect(Collectors.toMap(
            TeamMemberRepository.TeamMemberCount::getTeamId,
            TeamMemberRepository.TeamMemberCount::getMemberCount));

        Map<Long, TeamDisplayInfo> result = new HashMap<>();
        for (Long teamId : teamIds) {
            Long eventId = teamsById.get(teamId).getEventId();
            // 활동이 삭제됐으면 eventId 는 남아 있어도 조회가 비는데, 그건 정상 처리(null)한다.
            Event event = eventId != null ? eventsById.get(eventId) : null;
            // team_members 에는 팀장도 들어 있으므로 보정 없이 그대로 쓴다 — /api/teams 와 같은 숫자다.
            result.put(teamId, new TeamDisplayInfo(event,
              memberCounts.getOrDefault(teamId, 0L).intValue()));
        }
        return result;
    }

    /**
     * 내가 팀장이거나 이미 지원서를 낸 팀 — 추천해 봐야 지원할 수 없다.
     */
    private Set<Long> excludedTeamIds(Long userId) {
        Set<Long> excluded = new HashSet<>();
        teamRepository.findByLeaderUserId(userId).forEach(team -> excluded.add(team.getId()));
        // team 은 LAZY 지만 getId() 는 프록시에서 바로 읽혀 초기화가 일어나지 않는다.
        teamApplicationRepository.findByApplicantId(userId)
          .forEach(application -> excluded.add(application.getTeam().getId()));
        return excluded;
    }
}
