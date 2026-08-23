package com.example.mateon.portfolios.controller;

import com.example.mateon.common.dto.BaseResponse;
import com.example.mateon.portfolios.dto.PortfolioSummaryResponseDTO;
import com.example.mateon.portfolios.service.PortfolioSummaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "포트폴리오", description = "PDF 업로드와 AI 요약")
@RestController
@RequestMapping("/api/portfolios")
@RequiredArgsConstructor
public class PortfolioController {

    private final PortfolioSummaryService portfolioSummaryService;

    /**
     * 포트폴리오 PDF 를 요약한다 [인증 필수].
     * 요청은 multipart/form-data 이며 파트 이름은 {@code pdf_file} (20MB 이하).
     *
     * <p>
     * 응답은 마크다운 문자열 하나다(불릿 목록 + "요약" 문단). 저장된 자원을 만드는 게 아니라
     * 요약 결과를 계산해 돌려주는 것이라 201 이 아니라 200 이다 — 요약을 DB 에 남기긴 하지만
     * 그건 같은 PDF 를 다시 올렸을 때 AI 호출을 건너뛰기 위한 캐시이지 사용자가 조회할 자원이 아니다.
     *
     * <p>
     * 인증 여부는 SecurityConfig 의 매처가 강제한다.
     */
    @Operation(summary = "포트폴리오 PDF 요약",
      description = """
                    `multipart/form-data` 로 보내고 **파트 이름은 `pdf_file`** 이다 (20MB 이하).
                    파트 이름을 틀리면 400 이다.

                    응답은 **마크다운 문자열 하나**다(불릿 목록 + "요약" 문단). 화면에 그대로
                    렌더링하면 된다.

                    같은 PDF 를 다시 올리면 저장된 요약을 그대로 돌려준다(AI 재호출 없음).
                    저장된 자원을 만드는 게 아니라 요약을 계산해 돌려주는 것이라 201 이 아니라
                    200 이다 — 그 저장은 캐시일 뿐 조회용 자원이 아니다.

                    확장자가 .pdf 가 아니거나 내용이 PDF 가 아니면 둘 다 INVALID_PDF_FILE 이다.""")
    @ApiResponse(responseCode = "200", description = "마크다운 요약 문자열. 같은 PDF 면 저장된 요약을 그대로 돌려준다.")
    @ApiResponse(responseCode = "400",
      description = "INVALID_PDF_FILE — pdf 형식의 파일만 업로드할 수 있습니다.")
    @ApiResponse(responseCode = "413",
      description = "PDF_TOO_LARGE — 포트폴리오 PDF는 20MB 이하만 업로드할 수 있습니다.")
    @ApiResponse(responseCode = "404",
      description = "USER_NOT_FOUND — 사용자를 찾을 수 없습니다.")
    @ApiResponse(responseCode = "502",
      description = "AI_SERVER_ERROR — AI 서버 응답 처리에 실패했습니다.")
    @ApiResponse(responseCode = "503",
      description = "AI_SERVER_UNAVAILABLE — AI 서버에 연결할 수 없습니다. 잠시 후 재시도하면 된다.")
    @PostMapping(value = "/summarize", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BaseResponse<PortfolioSummaryResponseDTO> summarize(
      Authentication authentication,
      @RequestPart("pdf_file") MultipartFile pdfFile
    ) {
        // JWT 의 subject 가 userId 다(JwtAuthenticationFilter). 이 경로는 비인증 접근이 차단되므로
        // EventController.currentUserId 처럼 익명 토큰을 걸러 낼 필요가 없다.
        Long userId = Long.valueOf(authentication.getName());
        return BaseResponse.success(portfolioSummaryService.summarize(userId, pdfFile));
    }
}
