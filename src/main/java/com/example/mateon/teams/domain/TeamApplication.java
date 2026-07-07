package com.example.mateon.teams.domain;

import com.example.mateon.user.domain.User;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "team_applications")
@Getter @Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class TeamApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User applicant; // 지원

    private String introduction;   // 간단 소개글

    @Column(columnDefinition = "TEXT")
    private String message;        // 지원 동기

    @Column(name = "contact_number", length = 20)
    private String contactNumber;  // 연락처

    @Column(name = "portfolio_url", length = 500)
    private String portfolioUrl;   // 포트폴리오/깃허브 링크

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApplicationStatus status; // PENDING, APPROVED, REJECTED

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;
}