package com.example.mateon.portfolios.service;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.portfolios.client.PortfolioSummaryClient;
import com.example.mateon.portfolios.client.PortfolioSummaryResponse;
import com.example.mateon.portfolios.domain.UserPortfolio;
import com.example.mateon.portfolios.dto.PortfolioSummaryResponseDTO;
import com.example.mateon.portfolios.repository.UserPortfolioRepository;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * 포트폴리오 PDF → 마크다운 요약.
 *
 * <p>단순 중계가 아니라 <b>해시 기반 캐시</b>다. AI 서버가 돌려주는 pdf_id 는 채번값이 아니라
 * PDF 원본 바이트의 SHA-256 이고, 그 값은 우리도 같은 바이트에서 계산할 수 있다. 그래서
 * <b>AI 를 부르기 전에</b> 캐시를 조회한다 — AI 응답의 pdf_id 를 받아 본 뒤에 조회하면
 * 이미 15페이지 Vision 호출을 태운 뒤라 절감이 0이 된다.
 *
 * <p>클래스에 {@code @Transactional} 을 걸지 않는 이유: 이 메서드 한가운데에 최대 수십 초짜리
 * AI 호출이 있다. 트랜잭션으로 감싸면 그동안 DB 커넥션이 풀로 돌아오지 않는데, 이 레포는
 * 이미 같은 이유로 커넥션 풀이 고갈된 적이 있다(application.properties 의 open-in-view 주석).
 * 조회와 저장은 각각 리포지토리 호출 자신의 트랜잭션으로 충분하고, 둘을 원자적으로 묶을
 * 이유도 없다 — 경합은 uq_user_portfolios_pair 가 막는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioSummaryService {

    /** 멀티파트 설정(전역) 뒤의 2차 방어선. 이미지(10MB)와 달리 PDF 는 페이지가 많아 더 크다. */
    private static final long MAX_PDF_BYTES = 20L * 1024 * 1024;

    private static final String ALLOWED_EXTENSION = "pdf";

    /** 모든 PDF 는 "%PDF-1.x" 로 시작한다. 확장자만 바꾼 파일을 AI 까지 보내지 않으려고 본다. */
    private static final byte[] PDF_MAGIC = "%PDF".getBytes(StandardCharsets.US_ASCII);

    /** user_portfolios.filename 의 길이. 넘치면 INSERT 가 실패해 캐시가 조용히 비게 된다. */
    private static final int MAX_FILENAME_LENGTH = 255;

    private final PortfolioSummaryClient summaryClient;
    private final UserPortfolioRepository portfolioRepository;
    private final UserRepository userRepository;

    /**
     * @return 마크다운 요약. 같은 사용자가 같은 PDF 를 다시 올리면 저장된 요약을 그대로 돌려주며
     * 이때 AI 호출은 일어나지 않는다.
     * @throws MateonException INVALID_PDF_FILE(400) / PDF_TOO_LARGE(413) — 업로드 파일 문제,
     *                         AI_SERVER_UNAVAILABLE(503) / AI_SERVER_ERROR(502) — AI 서버 문제
     */
    public PortfolioSummaryResponseDTO summarize(Long userId, MultipartFile pdfFile) {
        // 검증이 AI 호출보다 먼저다. 순서를 뒤집으면 잘못된 요청 하나하나가 LLM 비용이 된다
        // (포스터 이미지 추출에서 세운 원칙과 같다).
        validate(pdfFile);

        // 바이트는 한 번만 읽는다. 해시 계산과 AI 전송이 같은 배열을 쓴다 —
        // MultipartFile 의 스트림을 두 번 여는 방식은 임시 파일이 이미 정리된 뒤에 터질 수 있다.
        byte[] bytes = readBytes(pdfFile);
        requirePdfStructure(bytes);

        String pdfId = sha256Hex(bytes);
        String filename = truncateFilename(pdfFile.getOriginalFilename());

        Optional<UserPortfolio> cached = portfolioRepository.findByUserIdAndPdfId(userId, pdfId);
        if (cached.isPresent()) {
            log.debug("포트폴리오 요약 캐시 히트 — AI 호출 생략 (userId={}, pdfId={})", userId, pdfId);
            return new PortfolioSummaryResponseDTO(cached.get().getSummary());
        }

        PortfolioSummaryResponse ai = summaryClient.summarize(bytes, filename);
        warnIfHashMismatch(pdfId, ai.getPdfId());
        save(userId, pdfId, ai.getResponse(), filename);

        return new PortfolioSummaryResponseDTO(ai.getResponse());
    }

    /** 형식/크기 문제는 전부 여기서 걸러진다. */
    private void validate(MultipartFile pdfFile) {
        if (pdfFile == null || pdfFile.isEmpty()) {
            throw new MateonException(ErrorCode.INVALID_PDF_FILE);
        }
        if (pdfFile.getSize() > MAX_PDF_BYTES) {
            // 멀티파트 한도가 먼저 걸리는 게 정상이지만, 한도를 올려 잡은 환경에서도
            // AI 가 거절하기 전에 우리가 먼저 안내한다.
            throw new MateonException(ErrorCode.PDF_TOO_LARGE);
        }

        String filename = pdfFile.getOriginalFilename();
        if (!StringUtils.hasText(filename)) {
            throw new MateonException(ErrorCode.INVALID_PDF_FILE);
        }
        // 확장자를 1차 근거로 삼는다. content-type 은 클라이언트가 주는 값이라
        // 브라우저·OS 에 따라 application/octet-stream 으로도 오기 때문이다.
        String extension = StringUtils.getFilenameExtension(filename);
        if (extension == null || !ALLOWED_EXTENSION.equals(extension.toLowerCase(Locale.ROOT))) {
            throw new MateonException(ErrorCode.INVALID_PDF_FILE);
        }
    }

    /**
     * 확장자만 .pdf 로 바꾼 파일을 여기서 거른다.
     *
     * <p>이미지 쪽에는 없는 검사인데, PDF 는 시그니처가 하나뿐이라 값싸게 확인되고 왕복 비용이
     * 크기 때문이다. 구조 전체의 유효성은 AI 서버가 렌더링하며 판단한다 — 여기서 보는 건
     * "PDF 인 척하는 다른 파일"까지다.
     */
    private void requirePdfStructure(byte[] bytes) {
        if (bytes.length < PDF_MAGIC.length) {
            throw new MateonException(ErrorCode.INVALID_PDF_FILE);
        }
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (bytes[i] != PDF_MAGIC[i]) {
                throw new MateonException(ErrorCode.INVALID_PDF_FILE);
            }
        }
    }

    private byte[] readBytes(MultipartFile pdfFile) {
        try {
            return pdfFile.getBytes();
        } catch (IOException e) {
            log.warn("업로드 파일을 읽지 못했습니다: {}", pdfFile.getOriginalFilename(), e);
            throw new MateonException(ErrorCode.INVALID_PDF_FILE);
        }
    }

    /** 소문자 hex 64자. AI 서버가 pdf_id 로 쓰는 것과 같은 규약이다. */
    private String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 모든 JVM 이 제공해야 하는 JCA 표준 알고리즘이다. 여기 오면 런타임이 망가진 것이라
            // 사용자에게 안내할 것이 없다.
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다", e);
        }
    }

    /**
     * AI 가 다른 pdf_id 를 보냈다면 양쪽의 해시 규약이 어긋난 것이다. 요약 자체는 멀쩡하므로
     * 요청을 실패시키지 않고, <b>우리가 계산한 값으로 저장한다</b> — 캐시 키는 다음 요청에서도
     * 우리가 계산할 수 있는 값이어야 조회가 성립한다.
     */
    private void warnIfHashMismatch(String ourPdfId, String aiPdfId) {
        if (StringUtils.hasText(aiPdfId) && !ourPdfId.equalsIgnoreCase(aiPdfId)) {
            log.warn("AI 가 보낸 pdf_id 가 우리 SHA-256 과 다릅니다 (ours={}, ai={}). " +
                    "AI 서버의 해시 대상이 바뀌었는지 확인이 필요합니다. 캐시 키로는 우리 값을 씁니다.",
                    ourPdfId, aiPdfId);
        }
    }

    private void save(Long userId, String pdfId, String summary, String filename) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new MateonException(ErrorCode.USER_NOT_FOUND));
        try {
            portfolioRepository.saveAndFlush(new UserPortfolio(user, pdfId, summary, filename));
        } catch (DataIntegrityViolationException e) {
            // 같은 파일을 동시에 두 번 올린 경합 → uq_user_portfolios_pair 가 막아 준다.
            // 다른 요청이 이미 같은 (userId, pdfId) 의 요약을 넣었다는 뜻이고 같은 PDF 의 요약이므로,
            // 실패시키지 않고 이번 호출이 받아 온 요약을 그대로 돌려준다.
            log.debug("포트폴리오 요약 저장 경합 — 이미 저장된 건이 있습니다 (userId={}, pdfId={})", userId, pdfId);
        }
    }

    /** DB 컬럼 길이를 넘는 파일명 때문에 INSERT 가 실패해 캐시가 비는 일이 없도록 잘라 둔다. */
    private String truncateFilename(String filename) {
        if (filename == null || filename.length() <= MAX_FILENAME_LENGTH) {
            return filename;
        }
        return filename.substring(0, MAX_FILENAME_LENGTH);
    }
}
