package com.example.mateon.portfolios.domain;

import com.example.mateon.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 포트폴리오 PDF 한 건의 요약. AI 호출 결과를 보관하는 캐시다.
 *
 * <p>
 * {@link #pdfId} 는 우리가 채번한 값이 아니라 <b>PDF 원본 바이트의 SHA-256</b> 이다.
 * AI 서버가 같은 규약으로 계산해 돌려주지만, 우리도 같은 바이트에서 계산할 수 있으므로
 * AI 를 부르기 전에 이 값으로 캐시를 조회한다 (PortfolioSummaryService 참고).
 *
 * <p>
 * PDF 원본은 저장하지 않는다. 개인 이력이라 공개 버킷에 둘 수 없고, 요약을 이미 갖고 있으면
 * 원본이 다시 필요한 경우가 없다.
 */
@Entity
@Table(
  name = "user_portfolios",
  uniqueConstraints = @UniqueConstraint(
    name = "uq_user_portfolios_pair", columnNames = {"user_id", "pdf_id"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class UserPortfolio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * PDF 원본 바이트의 SHA-256 (소문자 hex 64자). 생성 후 바뀌지 않는다 — 바뀌면 다른 파일이다.
     */
    @Column(name = "pdf_id", nullable = false, length = 64, updatable = false)
    private String pdfId;

    /**
     * AI 가 만든 마크다운 원문. 백엔드는 해석하지 않는다.
     */
    @Column(name = "summary", columnDefinition = "text", nullable = false)
    private String summary;

    /**
     * 사용자가 올린 원본 파일명. 표시/디버깅용이라 없어도 된다.
     */
    @Column(name = "filename", length = 255)
    private String filename;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public UserPortfolio(User user, String pdfId, String summary, String filename) {
        this.user = user;
        this.pdfId = pdfId;
        this.summary = summary;
        this.filename = filename;
    }

    /**
     * 같은 PDF 를 다시 요약했을 때 결과를 갱신한다.
     *
     * <p>
     * 지금 흐름에서는 캐시 히트면 AI 를 부르지 않으므로 호출되지 않는다. 프롬프트가 바뀌어
     * 강제 재요약을 넣게 될 때를 위해 열어 둔 자리이며, 그때도 행을 새로 만들지 않는 게 맞다
     * (pdf_id 가 같으면 같은 파일이고, uq_user_portfolios_pair 가 두 행을 허용하지 않는다).
     */
    public void updateSummary(String summary) {
        this.summary = summary;
    }
}
