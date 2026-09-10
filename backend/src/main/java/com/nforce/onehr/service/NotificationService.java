package com.nforce.onehr.service;

import com.nforce.onehr.dto.NotificationDto;
import com.nforce.onehr.entity.Notification;
import com.nforce.onehr.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private static final Map<String, String> PRIORITY_BY_TYPE = buildPriorityMap();

    private static Map<String, String> buildPriorityMap() {
        Map<String, String> m = new HashMap<>();
        // HIGH — rejections, action-required for the recipient
        m.put("LEAVE_REJECTED",                   "HIGH");
        m.put("EXPENSE_SUBMITTED",                "HIGH");  // goes to manager: action needed
        m.put("EXPENSE_MANAGER_REJECTED",         "HIGH");
        m.put("EXPENSE_FINAL_REJECTED",           "HIGH");
        m.put("ASSET_REQUEST_SUBMITTED",          "HIGH");  // goes to manager: action needed
        m.put("ASSET_REQUEST_REJECTED",           "HIGH");
        m.put("DOCUMENT_REJECTED",                "HIGH");
        m.put("DOCUMENT_EXPIRY",                  "HIGH");
        m.put("ATTENDANCE",                       "HIGH");  // goes to approver: action needed
        m.put("ATTENDANCE_REQUEST_REJECTED",      "HIGH");
        m.put("OVERTIME_REJECTED",                "HIGH");
        m.put("REGULARIZATION_SUBMITTED",         "HIGH");  // goes to approver: action needed
        m.put("REGULARIZATION_REJECTED",          "HIGH");
        m.put("LEAVE_REQUEST_SUBMITTED",          "HIGH");  // goes to manager/HR/Super Admin: action needed
        m.put("HELP_CONTENT_SUBMITTED",           "HIGH");  // goes to approver: action needed
        m.put("HELP_CONTENT_REJECTED",            "HIGH");
        m.put("HELPDESK_TICKET_CREATED",          "HIGH");  // goes to HR: action needed
        // MEDIUM — attention needed, not urgent
        m.put("LEAVE_APPROVED",                   "MEDIUM");
        m.put("EXPENSE_MANAGER_APPROVED",         "MEDIUM");
        m.put("EXPENSE_FINAL_APPROVED",           "MEDIUM");
        m.put("EXPENSE_PAID",                     "MEDIUM");
        m.put("ASSET_REQUEST_APPROVED",           "MEDIUM");
        m.put("ASSET_ASSIGNED",                   "MEDIUM");
        m.put("ASSET_REQUEST_FULFILLED",          "MEDIUM");
        m.put("DOCUMENT_VERIFIED",                "MEDIUM");
        m.put("DOCUMENT_REMINDER",                "MEDIUM");
        m.put("ATTENDANCE_REQUEST_APPROVED",      "MEDIUM");
        m.put("OVERTIME_APPROVED",                "MEDIUM");
        m.put("REGULARIZATION_APPROVED",          "MEDIUM");
        m.put("REGULARIZATION_PARTIALLY_APPROVED","MEDIUM");
        m.put("POLICY_PUBLISHED",                 "MEDIUM");
        m.put("POLICY_REMINDER",                  "MEDIUM");
        m.put("HELP_CONTENT_APPROVED",            "MEDIUM");
        m.put("HELPDESK_TICKET_REPLIED",          "MEDIUM");
        m.put("HELPDESK_TICKET_STATUS_CHANGED",   "MEDIUM");
        m.put("HELPDESK_TICKET_ASSIGNED",         "MEDIUM");
        m.put("KUDOS",                            "MEDIUM");
        m.put("ACCOUNT",                          "MEDIUM");
        // LOW — purely informational
        m.put("SECURITY",                         "LOW");
        m.put("WEB_CLOCK_IN_NOTICE",               "LOW");  // goes to manager: informational only, no action
        return java.util.Collections.unmodifiableMap(m);
    }

    private final NotificationRepository notificationRepository;

    /**
     * Create a notification. Always silent — exceptions are caught so a notification
     * failure never aborts the calling transaction.
     */
    public void send(UUID userId, String type, String title, String message, String linkPath) {
        try {
            String priority = PRIORITY_BY_TYPE.getOrDefault(type, "MEDIUM");
            notificationRepository.save(Notification.builder()
                    .userId(userId)
                    .type(type)
                    .title(title)
                    .message(message)
                    .linkPath(linkPath)
                    .priority(priority)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to persist notification for user {}: {}", userId, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Page<NotificationDto> getNotifications(UUID userId, int page, int size) {
        return notificationRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size))
                .map(this::toDto);
    }

    /** Backs the notification bell — unread only, newest first. */
    @Transactional(readOnly = true)
    public Page<NotificationDto> getUnreadNotifications(UUID userId, int page, int size) {
        return notificationRepository
                .findByUserIdAndReadFalseOrderByCreatedAtDesc(userId, PageRequest.of(page, size))
                .map(this::toDto);
    }

    @Transactional(readOnly = true)
    public long getUnreadCount(UUID userId) {
        return notificationRepository.countByUserIdAndReadFalse(userId);
    }

    @Transactional
    public void markRead(Long notificationId, UUID userId) {
        notificationRepository.findById(notificationId).ifPresent(n -> {
            if (n.getUserId().equals(userId)) {
                n.setRead(true);
                notificationRepository.save(n);
            }
        });
    }

    @Transactional
    public void markAllRead(UUID userId) {
        notificationRepository.markAllReadByUserId(userId);
    }

    private NotificationDto toDto(Notification n) {
        return NotificationDto.builder()
                .id(n.getId())
                .type(n.getType())
                .title(n.getTitle())
                .message(n.getMessage())
                .linkPath(n.getLinkPath())
                .read(n.isRead())
                .priority(n.getPriority())
                .createdAt(n.getCreatedAt())
                .build();
    }
}
