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

    @Column(unique = true, nullable = false, length = 100)
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

    // 학교 인증용 이메일(@dankook.ac.kr). 인증 전엔 null, 계정당 1개이므로 unique.
    @Column(name = "school_email", unique = true, length = 100)
    private String schoolEmail;

    // 학교(재학생) 인증 완료 여부. 소셜 로그인만 한 유저는 false.
    @Column(nullable = false)
    @Builder.Default
    private boolean schoolVerified = false;

    @Column(nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Campus campus;

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

    @Column(name = "dreamy_report", columnDefinition = "TEXT")
    private String dreamyReport; // OpenAI 리포트 JSON 문자열 저장

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public void update(String name, Campus campus, String college, String major, String grade,
                       String interestJobPrimary, String interestJobSecondary, String interestJobTertiary,
                       String tagline) {
        if (name != null) this.name = name;
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

    public enum Campus {
        JUKJEON, CHEONAN
    }
}

