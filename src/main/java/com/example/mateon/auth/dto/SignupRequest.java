package com.example.mateon.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Schema(description = "회원가입 요청. 학교 정보 아래는 모두 선택이며 나중에 프로필 수정으로 채울 수 있다.")
@Getter
@Setter
public class SignupRequest {
    @Schema(description = "가입 이메일. 학교 이메일(.ac.kr)이어야 하며, 이 주소로 학교 인증까지 끝난 것으로 처리된다.")
    @NotBlank(message = "이메일을 입력해주세요.")
    @Email(message = "올바른 이메일 형식이 아닙니다.")
    // 도메인(.ac.kr) 검증은 AuthService 에서 처리한다. DTO 는 이메일 형식만 검사.
    private String email;

    // 이메일 인증(/email/verify) 응답으로 받은 일회용 티켓. 인증 주체 확인용.
    @Schema(description = "POST /api/auth/email/verify 응답의 verificationToken 을 그대로 넣는다. 발급 후 30분간 유효하다.")
    @NotBlank(message = "이메일 인증을 먼저 완료해주세요.")
    private String verificationToken;

    @NotBlank(message = "비밀번호를 입력해주세요.")
    @Size(min = 10, max = 20, message = "비밀번호는 10-20자리 이내로 입력해주세요.")
    private String password;

    @Schema(description = "password 와 같아야 한다. 다르면 400 PASSWORD_MISMATCH.")
    @NotBlank(message = "비밀번호 확인을 입력해주세요.")
    private String passwordConfirm;

    @NotBlank(message = "이름을 입력해주세요.")
    @Size(max = 50, message = "이름은 50자 이하여야 합니다.")
    private String name;

    // 학교는 선택사항
    @Size(max = 100, message = "학교는 100자 이하여야 합니다.")
    private String school;

    // 캠퍼스는 선택사항 (화면에서 선택할 수 있음)
    @Size(max = 50, message = "캠퍼스는 50자 이하여야 합니다.")
    private String campus;

    // 단과대학은 선택사항
    @Size(max = 100, message = "단과대는 100자 이하여야 합니다.")
    private String college;

    // 학과는 선택사항
    @Size(max = 100, message = "전공은 100자 이하여야 합니다.")
    private String major;

    // 학년은 선택사항
    @Size(max = 10, message = "학년은 10자 이하여야 합니다.")
    private String grade;

    // 희망직무는 선택사항
    @Schema(description = "1~3순위 희망직무. 매칭 추천에서 쓰이므로 채울수록 추천이 정확해진다.")
    @Size(max = 100, message = "희망직무는 100자 이하여야 합니다.")
    private String interestJobPrimary;

    @Size(max = 100, message = "희망직무는 100자 이하여야 합니다.")
    private String interestJobSecondary;

    @Size(max = 100, message = "희망직무는 100자 이하여야 합니다.")
    private String interestJobTertiary;

    // 한 줄 소개는 선택사항 (화면에도 "선택"으로 표시됨)
    @Schema(description = "한 줄 소개. 프로필과 추천 카드에 노출된다.")
    @Size(max = 200, message = "태그라인은 200자 이하여야 합니다.")
    private String tagline;
}

