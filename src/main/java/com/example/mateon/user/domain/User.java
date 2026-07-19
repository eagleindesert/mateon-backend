package com.example.mateon.user.domain;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "users", uniqueConstraints = {
        @UniqueConstraint(name = "uk_users_provider_provider_id", columnNames = {"provider", "provider_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 계정/소셜 이메일. 로컬 유저는 학교 이메일과 동일하지만, 카카오 유저는
    // 이메일 미동의/미검증 시 null 일 수 있으므로 nullable. (unique 는 유지 → 다중 NULL 허용)
    @Column(unique = true, nullable = true, length = 100)
    private String email;

    // 소셜 로그인 유저는 비밀번호가 없으므로 nullable.
    @Column(nullable = true)
    private String password;

    // 가입 경로. 로컬 가입은 LOCAL, 소셜 가입은 해당 provider.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private AuthProvider provider = AuthProvider.LOCAL;

    // 소셜 provider 내 고유 식별자 (로컬 유저는 null).
    @Column(name = "provider_id", length = 100)
    private String providerId;

    // 학교 인증용 이메일(.ac.kr). 인증 전엔 null, 계정당 1개이므로 unique.
    @Column(name = "school_email", unique = true, length = 100)
    private String schoolEmail;

    // 학교(재학생) 인증 완료 여부. 소셜 로그인만 한 유저는 false.
    @Column(nullable = false)
    @Builder.Default
    private boolean schoolVerified = false;

    @Column(nullable = false, length = 50)
    private String name;

    // 학교/캠퍼스는 전국 확장을 위해 자유 입력 문자열이다. 값 표기가 안정화되면 표준화한다.
    @Column(length = 100)
    private String school;

    @Column(length = 50)
    private String campus;

    @Column(length = 100)
    private String college;

    @Column(length = 100)
    private String major;

    @Column(length = 10)
    private String grade;

    @Column(length = 100)
    private String interestJobPrimary;

    @Column(length = 100)
    private String interestJobSecondary;

    @Column(length = 100)
    private String interestJobTertiary;

    @Column(length = 200)
    private String tagline;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public void update(String name, String school, String campus, String college, String major, String grade,
                       String interestJobPrimary, String interestJobSecondary, String interestJobTertiary,
                       String tagline) {
        if (name != null) this.name = name;
        if (school != null) this.school = school;
        if (campus != null) this.campus = campus;
        if (college != null) this.college = college;
        if (major != null) this.major = major;
        if (grade != null) this.grade = grade;
        if (interestJobPrimary != null) this.interestJobPrimary = interestJobPrimary;
        if (interestJobSecondary != null) this.interestJobSecondary = interestJobSecondary;
        if (interestJobTertiary != null) this.interestJobTertiary = interestJobTertiary;
        if (tagline != null) this.tagline = tagline;
    }

    public void updatePassword(String encodedPassword) {
        this.password = encodedPassword;
    }

    // 학교 이메일 인증 완료 처리. 인증된 학교 이메일을 저장하고 재학생 상태로 전환한다.
    public void verifySchool(String schoolEmail) {
        this.schoolEmail = schoolEmail;
        this.schoolVerified = true;
    }

    // 기존 계정에 소셜 로그인을 연동한다. 재방문 시 (provider, providerId) 로 신원을
    // 조회할 수 있도록 provider 도 함께 전환한다. (로컬 비밀번호는 그대로 유지되어 병행 로그인 가능)
    public void linkSocial(AuthProvider provider, String providerId) {
        this.provider = provider;
        this.providerId = providerId;
    }
}

