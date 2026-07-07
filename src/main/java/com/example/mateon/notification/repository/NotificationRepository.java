package com.example.mateon.notification.repository;

import com.example.mateon.notification.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    // 내 알림을 최신순(내림차순)으로 조회
    List<Notification> findAllByReceiverIdOrderByCreatedAtDesc(Long receiverId);
}