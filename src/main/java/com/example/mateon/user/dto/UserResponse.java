package com.example.mateon.user.dto;

import com.example.mateon.user.domain.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {
    private Long id;
    private String email;
    private String schoolEmail;
    private boolean schoolVerified;
    private String name;
    private User.Campus campus;
    private String college;
    private String major;
    private String grade;
    private String interestJobPrimary;
    private String interestJobSecondary;
    private String interestJobTertiary;
    private String tagline;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static UserResponse from(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .schoolEmail(user.getSchoolEmail())
                .schoolVerified(user.isSchoolVerified())
                .name(user.getName())
                .campus(user.getCampus())
                .college(user.getCollege())
                .major(user.getMajor())
                .grade(user.getGrade())
                .interestJobPrimary(user.getInterestJobPrimary())
                .interestJobSecondary(user.getInterestJobSecondary())
                .interestJobTertiary(user.getInterestJobTertiary())
                .tagline(user.getTagline())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}

