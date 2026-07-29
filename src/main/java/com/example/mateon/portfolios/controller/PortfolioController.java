package com.example.mateon.portfolios.controller;

import com.example.mateon.common.dto.ApiResponse;
import com.example.mateon.portfolios.dto.PortfolioSummaryResponseDTO;
import com.example.mateon.portfolios.service.PortfolioSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/portfolios")
@RequiredArgsConstructor
public class PortfolioController {

    private final PortfolioSummaryService portfolioSummaryService;

    /**
     * 포트폴리오 PDF 를 요약한다 [인증 필수].
     * 요청은 multipart/form-data 이며 파트 이름은 {@code pdf_file} (20MB 이하).
     *
     * <p>응답은 마크다운 문자열 하나다(불릿 목록 + "요약" 문단). 저장된 자원을 만드는 게 아니라
     * 요약 결과를 계산해 돌려주는 것이라 201 이 아니라 200 이다 — 요약을 DB 에 남기긴 하지만
     * 그건 같은 PDF 를 다시 올렸을 때 AI 호출을 건너뛰기 위한 캐시이지 사용자가 조회할 자원이 아니다.
     *
     * <p>인증 여부는 SecurityConfig 의 매처가 강제한다.
     */
    @PostMapping(value = "/summarize", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<PortfolioSummaryResponseDTO> summarize(
      Authentication authentication,
      @RequestPart("pdf_file") MultipartFile pdfFile
    ) {
        // JWT 의 subject 가 userId 다(JwtAuthenticationFilter). 이 경로는 비인증 접근이 차단되므로
        // EventController.currentUserId 처럼 익명 토큰을 걸러 낼 필요가 없다.
        Long userId = Long.valueOf(authentication.getName());
        return ApiResponse.success(portfolioSummaryService.summarize(userId, pdfFile));
    }
}
