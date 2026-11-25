package com.meterflow.portal.internal;

import com.meterflow.notifications.api.AuditLogFacade;
import com.meterflow.notifications.api.AuditLogRecord;
import com.meterflow.notifications.api.NotificationFacade;
import com.meterflow.notifications.api.NotificationRecord;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin REST adapter for persistent customer notifications and operator audit logs (FR9).
 */
@RestController
@RequestMapping("/api/v1")
class NotificationController {

    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationFacade notificationFacade;
    private final AuditLogFacade auditLogFacade;

    NotificationController(NotificationFacade notificationFacade, AuditLogFacade auditLogFacade) {
        this.notificationFacade = notificationFacade;
        this.auditLogFacade = auditLogFacade;
    }

    @GetMapping("/customer/{customerId}/notifications")
    PageResponse<NotificationRecord> listCustomerNotifications(
            @PathVariable UUID customerId,
            @RequestParam(required = false) Boolean unreadOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return PageResponse.from(notificationFacade.listCustomerNotifications(customerId, unreadOnly, pageable));
    }

    @GetMapping("/customer/{customerId}/notifications/unread-count")
    Map<String, Long> countUnread(@PathVariable UUID customerId) {
        return Map.of("unreadCount", notificationFacade.countUnreadNotifications(customerId));
    }

    @PostMapping("/customer/{customerId}/notifications/{notificationId}/read")
    NotificationRecord markNotificationRead(
            @PathVariable UUID customerId,
            @PathVariable UUID notificationId
    ) {
        return notificationFacade.markAsReadForCustomer(customerId, notificationId);
    }

    @PostMapping("/customer/{customerId}/notifications/read-all")
    Map<String, Integer> markAllRead(@PathVariable UUID customerId) {
        return Map.of("markedReadCount", notificationFacade.markAllAsRead(customerId));
    }

    @GetMapping("/audit-logs")
    PageResponse<AuditLogRecord> listAuditLogs(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size
    ) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return PageResponse.from(auditLogFacade.listAuditLogs(customerId, pageable));
    }
}
