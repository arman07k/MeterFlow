package com.meterflow.notifications.api;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface NotificationFacade {

    Page<NotificationRecord> listCustomerNotifications(UUID customerId, Boolean unreadOnly, Pageable pageable);

    long countUnreadNotifications(UUID customerId);

    NotificationRecord markAsRead(UUID notificationId);

    NotificationRecord markAsReadForCustomer(UUID customerId, UUID notificationId);

    int markAllAsRead(UUID customerId);
}
