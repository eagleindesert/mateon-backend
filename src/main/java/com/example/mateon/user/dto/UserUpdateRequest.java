package com.example.mateon.user.dto;

import com.example.mateon.user.domain.User;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserUpdateRequest {
    @Size(max = 50, message = "이름은 50자 이하여야 합니다.")
    private String name;

    private User.Campus campus;

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
}

