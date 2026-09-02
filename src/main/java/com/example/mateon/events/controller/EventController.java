package com.example.mateon.events.controller;

import com.example.mateon.common.dto.BaseResponse;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.events.dto.ContestSimilarityMapResponseDTO;
import com.example.mateon.events.dto.EventExtractionResponseDTO;
import com.example.mateon.events.dto.EventRequestDTO;
import com.example.mateon.events.dto.EventResponseDTO;
import com.example.mateon.events.models.Event.Category;
import com.example.mateon.events.models.Event.Field;
import com.example.mateon.events.service.EventExtractionService;
import com.example.mateon.events.service.EventService;
import com.example.mateon.events.service.EventSimilarityMapService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Tag(name = "활동/공모전", description = "활동 등록·검색·추천·유사도 지도. 포스터 이미지에서 AI 로 정보를 추출한다")
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
    private final EventExtractionService eventExtractionService;
    private final EventSimilarityMapService eventSimilarityMapService;

    /**
     * 활동(공모전 등) 등록 [인증 필수]
     * 인증 여부는 SecurityConfig 의 POST /api/events 매처가 강제한다.
     */
    @Operation(summary = "활동 등록",
      description = """
                    공모전·대외활동·교내 활동을 새로 올린다. 포스터 이미지가 있으면
                    `POST /api/events/extract-image` 로 초안을 받아 채운 뒤 여기로 보내면 된다.

                    등록된 활동은 팀 모집글(`POST /api/teams`)의 eventId 로 연결할 수 있다.

                    201 응답 계약(`EventResponseDTO`)은 그대로다. 임베딩은 커밋 뒤 비동기로
                    채워지므로, 방금 올린 활동으로 `GET /api/events/{eventId}/similarity-map`
                    을 치면 400 `EVENT_EMBEDDING_NOT_READY` 가 날 수 있다. 잠시 후 다시
                    호출하면 된다. 임베딩 실패가 등록 자체를 막지는 않는다.""")
    @PostMapping
    @ApiResponse(responseCode = "201", description = "등록된 활동. 팀 모집글의 eventId 로 연결할 수 있다.")
    public ResponseEntity<BaseResponse<EventResponseDTO>> createEvent(
      @Valid @RequestBody EventRequestDTO request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
          .body(BaseResponse.success(eventService.createEvent(request)));
    }

    /**
     * 공모전 포스터 이미지에서 활동 등록 초안을 추출한다 [인증 필수].
     * 요청은 multipart/form-data 이며 파트 이름은 {@code image} (jpg/jpeg/png, 10MB 이하).
     *
     * <p>
     * 저장은 하지 않는다 — 프론트가 이 초안을 사용자에게 보여주고 수정을 받은 뒤
     * POST /api/events 로 등록한다. 그래서 201 이 아니라 200 이다.
     * 인증 여부는 SecurityConfig 의 매처가 강제한다.
     */
    @Operation(summary = "포스터 이미지에서 활동 등록 초안 추출",
      description = """
                    `multipart/form-data` 로 보내고 **파트 이름은 `image`** 다
                    (jpg/jpeg/png, 10MB 이하).

                    **저장하지 않는다.** 응답은 AI 가 읽어 낸 초안일 뿐이라, 사용자에게 보여주고
                    고치게 한 뒤 `POST /api/events` 로 등록해야 실제 활동이 생긴다.
                    그래서 201 이 아니라 200 이다.

                    읽어 내지 못한 항목은 null 이고, category·field 는 판독이 애매하면 ETC 로 온다 —
                    빈칸 없이 다 채워질 거라고 가정하지 말 것.""")
    @ApiResponse(responseCode = "200", description = "포스터에서 뽑은 활동 등록 초안. 저장은 하지 않는다.")
    @ApiResponse(responseCode = "400",
      description = "INVALID_IMAGE_FILE — jpg, jpeg, png 형식의 이미지 파일만 업로드할 수 있습니다.")
    @ApiResponse(responseCode = "413",
      description = "IMAGE_TOO_LARGE — 이미지는 10MB 이하만 업로드할 수 있습니다.")
    @ApiResponse(responseCode = "502", description = """
            AI_SERVER_ERROR — AI 서버 응답 처리에 실패했습니다.
            IMAGE_UPLOAD_FAILED — 이미지 저장소 업로드에 실패했습니다.""")
    @ApiResponse(responseCode = "503",
      description = "AI_SERVER_UNAVAILABLE — AI 서버에 연결할 수 없습니다. 잠시 후 재시도하면 된다.")
    @ApiResponse(responseCode = "507",
      description = "STORAGE_QUOTA_EXCEEDED — 저장 공간이 가득 찼습니다.")
    @PostMapping(value = "/extract-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BaseResponse<EventExtractionResponseDTO> extractFromImage(
      @RequestPart("image") MultipartFile image
    ) {
        return BaseResponse.success(eventExtractionService.extractFromImage(image));
    }

    /**
     * 활동 검색. 필터(대학교/단과대/카테고리/분야)는 모두 선택이며, 결과는 활동 시작일 최신순이다.
     * 로그인 여부와 무관하게 순서가 같다(이유는 {@code EventService#search} 참고).
     *
     * @param college 대상 단과대학.
     * <b>deprecated</b> — 대상 범위는 {@code school} 로 일원화한다. 아직 보내는
     * 클라이언트가 있어 계속 받지만, 신규 사용은 하지 않는다.
     * @param school 대상 대학교. 부분일치이며 "전체"는 필터 미적용으로 취급한다.
     * @param keyword 제목·설명·주최를 아우르는 자유 검색어. 부분일치이며 "전체"/빈값은 필터 미적용으로 취급한다.
     *
     * <p>
     * 비로그인도 그대로 쓸 수 있다(permitAll). 토큰을 함께 보내면 각 활동에 내 북마크 여부가
     * {@code bookmarked} 로 실릴 뿐, 필터도 순서도 달라지지 않는다.
     */
    @Operation(summary = "활동 검색",
      description = """
                    필터는 모두 선택이고 자유롭게 조합된다(지정한 것끼리 AND). 아무것도 안 주면
                    전체 조회와 같다. 결과는 **활동 시작일 최신순**이며, 로그인 여부와 무관하게
                    순서가 같다.

                    문자열 필터는 부분일치다("단국대"로 "단국대학교"가 잡힌다). 값에 `"전체"` 를
                    보내면 그 필터를 안 준 것으로 취급한다 — 파라미터를 아예 빼도 결과는 같다.

                    페이징은 `page`(0-기반)·`size` 로 한다. **배열 길이가 요청한 size 와 같으면
                    다음 페이지가 있다**고 보면 된다. size 상한은 100 이라 그보다 크게 보내도 잘린다.

                    비로그인도 그대로 쓸 수 있다. 토큰을 함께 보내면 각 활동에 내 북마크 여부가
                    `bookmarked` 로 실릴 뿐, 필터도 순서도 달라지지 않는다(비로그인이면 전부 false).""")
    @Parameter(name = "college", description = "대상 단과대학. [폐기 예정] — 대상 범위는 school 로 일원화한다. 신규 사용 금지.")
    @Parameter(name = "school", description = "대상 대학교. 부분일치이며 \"전체\"는 필터 미적용.")
    @Parameter(name = "category", description = "활동 분류. 정확히 일치해야 하며 없는 값을 보내면 400 이다.")
    @Parameter(name = "field", description = "활동 분야. category 와 마찬가지로 정확 일치다.")
    @Parameter(name = "keyword", description = "제목·설명·주최를 아우르는 자유 검색어. 셋 중 하나라도 걸리면 잡힌다(OR).")
    @Parameter(name = "page", description = "0-기반 페이지 번호. 음수는 0 으로 취급한다.")
    @Parameter(name = "size", description = "페이지당 건수. 최대 100 이며 1 미만은 1 로 올린다.")
    @SecurityRequirement(name = "")  // 비로그인 허용
    @GetMapping("/search")
    public BaseResponse<List<EventResponseDTO>> searchEvents(
      @RequestParam(required = false) String college,
      @RequestParam(required = false) String school,
      @RequestParam(required = false) Category category,
      @RequestParam(required = false) Field field,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      Authentication authentication
    ) {
        // 지우기 전에 '아직 누가 보내고 있는지'를 알아야 한다. 파라미터 하나만 폐기 대상이라
        // /recommended 처럼 Deprecation 헤더를 붙이지는 않고 서버 로그로만 추적한다.
        if (college != null && !college.isBlank()) {
            log.warn("deprecated 검색 파라미터 사용: college={} (school 로 전환 필요)", college);
        }
        return BaseResponse.success(eventService.search(
          college, school, category, field, keyword, page, size, currentUserId(authentication)));
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
    @Operation(deprecated = true,
      summary = "[폐기 예정] 홈화면 맞춤 활동 추천",
      description = """
                    **신규 사용 금지.** 관련도 점수가 어휘 매칭이라 순위를 신뢰할 수 없다 —
                    활동 본문에 희망직무 문자열이 그대로 있어야 점수가 붙어서 "서버 운영자"와
                    "백엔드 개발자"는 0점인 반면, 부분 문자열 오탐(희망직무 "AI" ↔ 본문 "email")은
                    만점을 받는다. 즉 이 순서는 관련도가 아니라 공고문의 어휘·길이를 반영한다.
                    
                    category 를 주면 그 안에서 1건, 생략하면 카테고리마다 1건씩 내려온다.

                    호출하면 `Deprecation`/`Sunset` 응답 헤더가 붙고 서버에 경고 로그가 남는다
                    (누가 아직 쓰는지 파악해 걷어내기 위한 것). 대체 방식이 정해지기 전까지
                    동작은 그대로 둔다.""")
    @ApiResponse(responseCode = "200",
      description = "카테고리별 1건씩의 추천 활동. Deprecation/Sunset 헤더가 함께 붙는다.")
    @ApiResponse(responseCode = "404",
      description = "USER_NOT_FOUND — 사용자를 찾을 수 없습니다.")
    @Parameter(name = "category", description = "생략하면 모든 카테고리에서 각각 1건씩 반환한다.")
    @GetMapping("/recommended")
    public BaseResponse<List<EventResponseDTO>> getRecommendedEvents(
      @RequestParam(required = false) Category category,
      Authentication authentication,
      HttpServletResponse response
    ) {
        // SecurityConfig 가 이 경로를 authenticated 로 막고 있어 여기까지 익명이 오지는 않는다.
        // 매처가 바뀌어도 조용히 500 이 되지 않도록 방어만 남긴다.
        Long userId = currentUserId(authentication);
        if (userId == null) {
            throw new MateonException(ErrorCode.UNAUTHORIZED);
        }

        // 지우기 전에 '아직 누가 부르고 있는지'를 알아야 한다.
        // 헤더는 클라이언트가, 로그는 서버가 감지할 수 있는 경로다 (RFC 8594).
        response.setHeader("Deprecation", "true");
        response.setHeader("Sunset", RECOMMENDED_SUNSET_HEADER);
        log.warn("deprecated 엔드포인트 호출: GET /api/events/recommended (userId={}, category={})",
          userId, category);

        return BaseResponse.success(eventService.recommend(category, userId));
    }

    /**
     * 기본 조회 (무작위 정렬). 카테고리가 섞인 표본을 size 건까지 내려준다.
     * RANDOM 정렬이라 페이지 간 순서를 보장할 수 없어 page 는 받지 않고 size 로만 응답 크기를 묶는다.
     *
     * <p>
     * /search 와 마찬가지로 토큰을 보내면 {@code bookmarked} 가 채워진다.
     */
    @Operation(summary = "활동 목록 (무작위 표본)",
      description = """
                    홈 화면용으로 카테고리가 섞인 표본을 size 건까지 내려준다.

                    **정렬이 무작위라 page 파라미터가 없다** — 호출할 때마다 순서가 다시 섞여
                    페이지를 넘기면 중복·누락이 생기기 때문이다. 목록을 순회해야 하면
                    `GET /api/events/search` 를 쓴다(그쪽은 시작일 순이라 페이징이 안정적이다).

                    `/search` 와 마찬가지로 토큰을 보내면 `bookmarked` 가 채워진다.""")
    @Parameter(name = "size", description = "내려받을 건수. 최대 100 이며 1 미만은 1 로 올린다.")
    @SecurityRequirement(name = "")  // 비로그인 허용
    @GetMapping
    public BaseResponse<List<EventResponseDTO>> getAllEvents(
      @RequestParam(defaultValue = "20") int size,
      Authentication authentication
    ) {
        return BaseResponse.success(eventService.findAllRandomly(size, currentUserId(authentication)));
    }

    /**
     * 기준 활동과 다른 활동들의 유사도·방사형 그래프 좌표.
     *
     * <p>
     * 비로그인도 그대로 쓸 수 있다(permitAll). 임베딩은 등록 커밋 후 비동기로 채워지므로,
     * 방금 올린 활동을 바로 물으면 400 EVENT_EMBEDDING_NOT_READY 가 난다.
     */
    @Operation(summary = "공모전 유사도 지도",
      description = """
                    기준 활동과 임베딩이 있는 다른 활동들의 코사인 유사도·방사형 좌표를 내려준다.

                    `radius` 와 `x`/`y` 는 이번 후보군 안의 **상대 순위**다. 서로 다른 요청의 점
                    간 거리를 비교하면 안 된다. 색과 UI 는 `similarity` 또는 `rankPercentile` 로
                    결정하면 된다.

                    등록 직후처럼 기준 활동 임베딩이 아직이면 400 `EVENT_EMBEDDING_NOT_READY`.
                    잠시 후 다시 호출하면 된다. 후보가 없으면 200 에 `points` 가 빈 배열이다.

                    비로그인도 그대로 쓸 수 있다.""")
    @ApiResponse(responseCode = "200", description = "기준 공모전과 유사도 순 후보 좌표")
    @ApiResponse(responseCode = "400",
      description = "EVENT_EMBEDDING_NOT_READY — 공모전 정보 분석이 아직 완료되지 않았습니다.")
    @ApiResponse(responseCode = "404",
      description = "EVENT_NOT_FOUND — 활동을 찾을 수 없습니다.")
    @ApiResponse(responseCode = "502",
      description = "AI_SERVER_ERROR — AI 서버 응답 처리에 실패했습니다.")
    @ApiResponse(responseCode = "503",
      description = "AI_SERVER_UNAVAILABLE — AI 서버에 연결할 수 없습니다.")
    @Parameter(name = "eventId", description = "그래프 중심이 되는 기준 활동 ID")
    @Parameter(name = "topN",
      description = "반환할 최대 후보 수. 기본 500, 최소 1, 최대 500. 1 미만은 1 로, 500 초과는 500 으로 자른다.")
    @SecurityRequirement(name = "")
    @GetMapping("/{eventId}/similarity-map")
    public BaseResponse<ContestSimilarityMapResponseDTO> similarityMap(
      @PathVariable Long eventId,
      @RequestParam(defaultValue = "500") int topN
    ) {
        return BaseResponse.success(eventSimilarityMapService.map(eventId, topN));
    }

    /**
     * JWT 의 subject 는 userId 다(JwtAuthenticationFilter). 비로그인이면 null 을 돌려준다.
     *
     * <p>
     * null 검사만으로는 부족하다. 이 컨트롤러의 조회 경로들은 SecurityConfig 에서
     * {@code /api/events/**} permitAll 에 걸리는데, permitAll 이라고 authentication 이 비는 게
     * 아니라 Spring Security 가 AnonymousAuthenticationToken 을 채워 넣는다. 그 getName() 은
     * "anonymousUser" 라는 문자열이라, 걸러 내지 않으면 아래 Long.valueOf 에서
     * NumberFormatException 이 터져 비로그인 조회가 전부 500 이 된다.
     */
    private Long currentUserId(Authentication authentication) {
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return Long.valueOf(authentication.getName());
    }
}
