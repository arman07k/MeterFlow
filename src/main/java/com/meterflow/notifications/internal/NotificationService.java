package com.meterflow.notifications.internal;

import com.meterflow.identity.api.CustomerAccessGuard;
import com.meterflow.identity.api.UnauthorizedCustomerAccessException;
import com.meterflow.notifications.api.NotificationFacade;
import com.meterflow.notifications.api.NotificationNotFoundException;
import com.meterflow.notifications.api.NotificationRecord;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class NotificationService implements NotificationFacade {

    private final NotificationRepository notificationRepository;
    private final CustomerAccessGuard accessGuard;
    private final Clock clock;

    NotificationService(
            NotificationRepository notificationRepository,
            CustomerAccessGuard accessGuard,
            Clock clock
    ) {
        this.notificationRepository = notificationRepository;
        this.accessGuard = accessGuard;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationRecord> listCustomerNotifications(UUID customerId, Boolean unreadOnly, Pageable pageable) {
        accessGuard.requireCustomerAccess(customerId);
        Page<NotificationEntity> page = Boolean.TRUE.equals(unreadOnly)
                ? notificationRepository.findByCustomerIdAndReadFalseOrderByCreatedAtDesc(customerId, pageable)
                : notificationRepository.findByCustomerIdOrderByCreatedAtDesc(customerId, pageable);
        return page.map(NotificationService::toRecord);
    }

    @Override
    @Transactional(readOnly = true)
    public long countUnreadNotifications(UUID customerId) {
        accessGuard.requireCustomerAccess(customerId);
        return notificationRepository.countByCustomerIdAndReadFalse(customerId);
    }

    @Override
    @Transactional
    public NotificationRecord markAsRead(UUID notificationId) {
        NotificationEntity entity = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new NotificationNotFoundException("Notification not found"));
        accessGuard.requireCustomerAccess(entity.getCustomerId());
        entity.markRead(nowUtc());
        return toRecord(notificationRepository.saveAndFlush(entity));
    }

    @Override
    @Transactional
    public NotificationRecord markAsReadForCustomer(UUID customerId, UUID notificationId) {
        accessGuard.requireCustomerAccess(customerId);
        NotificationEntity entity = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new NotificationNotFoundException("Notification not found"));
        if (!entity.getCustomerId().equals(customerId)) {
            throw new UnauthorizedCustomerAccessException("Notification does not belong to customer");
        }
        entity.markRead(nowUtc());
        return toRecord(notificationRepository.saveAndFlush(entity));
    }

    @Override
    @Transactional
    public int markAllAsRead(UUID customerId) {
        accessGuard.requireCustomerAccess(customerId);
        return notificationRepository.markAllUnreadAsRead(customerId, nowUtc());
    }

    private Instant nowUtc() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    static NotificationRecord toRecord(NotificationEntity entity) {
        return new NotificationRecord(
                entity.getId(),
                entity.getCustomerId(),
                entity.getNotificationType(),
                entity.getTitle(),
                entity.getMessage(),
                entity.getResourceType(),
                entity.getResourceId(),
                entity.getSourceEventId(),
                entity.isRead(),
                entity.getReadAt(),
                entity.getCreatedAt()
        );
    }
}
