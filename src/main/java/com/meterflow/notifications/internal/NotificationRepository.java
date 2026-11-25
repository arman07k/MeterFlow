package com.meterflow.notifications.internal;

import com.meterflow.notifications.api.NotificationType;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface NotificationRepository extends JpaRepository<NotificationEntity, UUID> {

    boolean existsByCustomerIdAndNotificationTypeAndSourceEventId(
            UUID customerId,
            NotificationType notificationType,
            UUID sourceEventId
    );

    Page<NotificationEntity> findByCustomerIdOrderByCreatedAtDesc(UUID customerId, Pageable pageable);

    Page<NotificationEntity> findByCustomerIdAndReadFalseOrderByCreatedAtDesc(UUID customerId, Pageable pageable);

    @Modifying
    @Query("UPDATE NotificationEntity n SET n.read = true, n.readAt = :readAt WHERE n.customerId = :customerId AND n.read = false")
    int markAllUnreadAsRead(@Param("customerId") UUID customerId, @Param("readAt") Instant readAt);

    long countByCustomerIdAndReadFalse(UUID customerId);
}
