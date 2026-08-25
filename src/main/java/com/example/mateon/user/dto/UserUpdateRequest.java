package com.example.mateon.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Schema(description = "프로필 수정 요청. **보낸 필드만 바뀐다** — 생략하거나 null 로 둔 항목은 기존 값이 유지되므로 "
  + "값을 비우는 용도로는 쓸 수 없다.")
@Getter
@Setter
public class UserUpdateRequest {

    @Size(max = 50, message = "이름은 50자 이하여야 합니다.")
    private String name;

    @Size(max = 100, message = "학교는 100자 이하여야 합니다.")
    private String school;

    @Size(max = 50, message = "캠퍼스는 50자 이하여야 합니다.")
    private String campus;

    @Size(max = 100, message = "단과대는 100자 이하여야 합니다.")
    private String college;

    @Size(max = 100, message = "전공은 100자 이하여야 합니다.")
    private String major;

    @Size(max = 10, message = "학년은 10자 이하여야 합니다.")
    private String grade;

    @Size(max = 100, message = "희망직무는 100자 이하여야 합니다.")
    private String interestJobPrimary;

    @Size(max = 100, message = "희망직무는 100자 이하여야 합니다.")
    private String interestJobSecondary;

    @Size(max = 100, message = "희망직무는 100자 이하여야 합니다.")
    private String interestJobTertiary;

    @Size(max = 200, message = "태그라인은 200자 이하여야 합니다.")
    private String tagline;

    /**
     * 포트폴리오 서술.
     *
     * <p>
     * 위 필드들의 {@code @Size} 는 컬럼 길이를 그대로 옮긴 값이지만 이건 다르다 —
     * {@code users.portfolio} 는 {@code text} 라 무제한이고, 5000 은 요청 본문 상한일 뿐이다.
     * 그래서 이 숫자는 마이그레이션 없이 조정할 수 있다. 대신 DB 가 대신 막아주지 않으므로
     * 이 검증이 유일한 방어선이다.
     */
    @Schema(description = "사용자가 직접 쓰는 포트폴리오 서술. PDF 업로드(/api/portfolios/summarize)와는 별개 항목이다.")
    @Size(max = 5000, message = "포트폴리오는 5000자 이하여야 합니다.")
    private String portfolio;
}
