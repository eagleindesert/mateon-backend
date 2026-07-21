package com.example.mateon.events.controller;

import com.example.mateon.common.dto.ApiResponse;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.events.dto.EventRequestDTO;
import com.example.mateon.events.dto.EventResponseDTO;
import com.example.mateon.events.models.Event.Category;
import com.example.mateon.events.models.Event.Field;
import com.example.mateon.events.service.EventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

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
     * 활동 검색. 필터(단과대/카테고리/분야)는 모두 선택이며, 로그인 상태면 관련도순으로 정렬된다.
     */
    @GetMapping("/search")
    public ApiResponse<List<EventResponseDTO>> searchEvents(
      @RequestParam(required = false) String college,
      @RequestParam(required = false) Category category,
      @RequestParam(required = false) Field field,
      Authentication authentication // 인증된 사용자 정보 (선택적)
    ) {
        return ApiResponse.success(
          eventService.search(college, category, field, currentUserId(authentication)));
    }

    /**
     * 홈화면 맞춤 활동 추천 API [인증 필수]
     * 각 카테고리별로 사용자와 가장 관련도가 높은 활동 1개씩 반환한다.
     *
     * @param category 카테고리 (CONTEST, EXTERNAL, SCHOOL). null이면 모든 카테고리에서 각각 1개씩 반환
     */
    @GetMapping("/recommended")
    public ApiResponse<List<EventResponseDTO>> getRecommendedEvents(
      @RequestParam(required = false) Category category,
      Authentication authentication
    ) {
        if (authentication == null) {
            throw new MateonException(ErrorCode.UNAUTHORIZED);
        }
        return ApiResponse.success(eventService.recommend(category, currentUserId(authentication)));
    }

    /**
     * 기본 전체 조회 (무작위 정렬)
     */
    @GetMapping
    public ApiResponse<List<EventResponseDTO>> getAllEvents() {
        return ApiResponse.success(eventService.findAllRandomly());
    }

    /**
     * JWT 의 subject 는 userId 다(JwtAuthenticationFilter). 비로그인이면 null 을 돌려준다.
     */
    private Long currentUserId(Authentication authentication) {
        return authentication == null ? null : Long.valueOf(authentication.getName());
    }
}
