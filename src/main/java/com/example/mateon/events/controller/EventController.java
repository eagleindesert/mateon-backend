package com.example.mateon.events.controller;

import com.example.mateon.common.dto.ApiResponse;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.events.dto.EventRequestDTO;
import com.example.mateon.events.dto.EventResponseDTO;
import com.example.mateon.events.models.Event.Category;
import com.example.mateon.events.models.Event.Field;
import com.example.mateon.events.service.EventService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    // /recommended 종료 예정 시각. 프론트 전환 일정에 맞춰 조정한다.
    private static final ZonedDateTime RECOMMENDED_SUNSET_AT
      = ZonedDateTime.of(2026, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC);
    private static final String RECOMMENDED_SUNSET_HEADER
      = DateTimeFormatter.RFC_1123_DATE_TIME.format(RECOMMENDED_SUNSET_AT);

    private final EventService eventService;

    /**
     * 활동(공모전 등) 등록 [인증 필수]
     * 인증 여부는 SecurityConfig 의 POST /api/events 매처가 강제한다.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<EventResponseDTO>> createEvent(
      @Valid @RequestBody EventRequestDTO request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
          .body(ApiResponse.success(eventService.createEvent(request)));
    }

    /**
     * 활동 검색. 필터(대학교/단과대/카테고리/분야)는 모두 선택이며, 결과는 활동 시작일 최신순이다.
     * 로그인 여부와 무관하게 순서가 같다(이유는 {@code EventService#search} 참고).
     *
     * @param college 대상 단과대학.
     * <b>deprecated</b> — 대상 범위는 {@code school} 로 일원화한다. 아직 보내는
     * 클라이언트가 있어 계속 받지만, 신규 사용은 하지 않는다.
     * @param school 대상 대학교. 부분일치이며 "전체"는 필터 미적용으로 취급한다.
     */
    @GetMapping("/search")
    public ApiResponse<List<EventResponseDTO>> searchEvents(
      @RequestParam(required = false) String college,
      @RequestParam(required = false) String school,
      @RequestParam(required = false) Category category,
      @RequestParam(required = false) Field field,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size
    ) {
        // 지우기 전에 '아직 누가 보내고 있는지'를 알아야 한다. 파라미터 하나만 폐기 대상이라
        // /recommended 처럼 Deprecation 헤더를 붙이지는 않고 서버 로그로만 추적한다.
        if (college != null && !college.isBlank()) {
            log.warn("deprecated 검색 파라미터 사용: college={} (school 로 전환 필요)", college);
        }
        return ApiResponse.success(eventService.search(college, school, category, field, page, size));
    }

    /**
     * 홈화면 맞춤 활동 추천 API [인증 필수]
     * 각 카테고리별로 사용자와 가장 관련도가 높은 활동 1개씩 반환한다.
     *
     * @param category 카테고리 (CONTEST, EXTERNAL, SCHOOL). null이면 모든 카테고리에서 각각 1개씩 반환
     * @deprecated 관련도 점수(EventMatchingService)가 어휘 매칭이라 순위를 신뢰할 수 없다.
     * 활동 본문에 희망직무 문자열이 그대로 들어있어야 점수가 붙어서 "서버 운영자"와
     * "백엔드 개발자"는 0점이 되는 반면, 부분 문자열 오탐(희망직무 "AI" ↔ 본문 "email")은
     * 만점을 받는다. 즉 이 API 의 순서는 관련도가 아니라 공고문의 어휘/길이를 반영한다.
     * 대체 방식이 정해지기 전까지 동작은 그대로 두되 신규 사용은 하지 않는다.
     * 호출하면 Deprecation/Sunset 응답 헤더와 서버 경고 로그가 남는다.
     */
    @Deprecated
    @GetMapping("/recommended")
    public ApiResponse<List<EventResponseDTO>> getRecommendedEvents(
      @RequestParam(required = false) Category category,
      Authentication authentication,
      HttpServletResponse response
    ) {
        if (authentication == null) {
            throw new MateonException(ErrorCode.UNAUTHORIZED);
        }
        Long userId = currentUserId(authentication);

        // 지우기 전에 '아직 누가 부르고 있는지'를 알아야 한다.
        // 헤더는 클라이언트가, 로그는 서버가 감지할 수 있는 경로다 (RFC 8594).
        response.setHeader("Deprecation", "true");
        response.setHeader("Sunset", RECOMMENDED_SUNSET_HEADER);
        log.warn("deprecated 엔드포인트 호출: GET /api/events/recommended (userId={}, category={})",
          userId, category);

        return ApiResponse.success(eventService.recommend(category, userId));
    }

    /**
     * 기본 조회 (무작위 정렬). 카테고리가 섞인 표본을 size 건까지 내려준다.
     * RANDOM 정렬이라 페이지 간 순서를 보장할 수 없어 page 는 받지 않고 size 로만 응답 크기를 묶는다.
     */
    @GetMapping
    public ApiResponse<List<EventResponseDTO>> getAllEvents(
      @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.success(eventService.findAllRandomly(size));
    }

    /**
     * JWT 의 subject 는 userId 다(JwtAuthenticationFilter). 비로그인이면 null 을 돌려준다.
     */
    private Long currentUserId(Authentication authentication) {
        return authentication == null ? null : Long.valueOf(authentication.getName());
    }
}
