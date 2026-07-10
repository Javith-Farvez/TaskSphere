package com.tasksphere.dto;

import com.tasksphere.entity.Notification;
import lombok.Data;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

public class NotificationDtos {

    // ── Response sent to frontend ──────────────────────────────────
    @Data
    public static class NotificationResponse {
        private Long    id;
        private String  type;
        private String  title;
        private String  message;
        private String  icon;
        private String  color;
        private Boolean isRead;
        private Long    referenceId;
        private String  referenceType;
        private String  createdAt;       // human-readable "2 min ago"
        private String  createdAtFull;   // full ISO timestamp

        public static NotificationResponse from(Notification n) {
            NotificationResponse r = new NotificationResponse();
            r.setId(n.getId());
            r.setType(n.getType().name());
            r.setTitle(n.getTitle());
            r.setMessage(n.getMessage());
            r.setIcon(n.getIcon());
            r.setColor(n.getColor().name().toLowerCase());
            r.setIsRead(n.getIsRead());
            r.setReferenceId(n.getReferenceId());
            r.setReferenceType(n.getReferenceType());
            r.setCreatedAt(timeAgo(n.getCreatedAt()));
            r.setCreatedAtFull(n.getCreatedAt() != null
                    ? n.getCreatedAt().format(DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a"))
                    : "");
            return r;
        }

        private static String timeAgo(LocalDateTime t) {
            if (t == null) return "";
            long mins  = ChronoUnit.MINUTES.between(t, LocalDateTime.now());
            long hours = ChronoUnit.HOURS.between(t, LocalDateTime.now());
            long days  = ChronoUnit.DAYS.between(t, LocalDateTime.now());
            if (mins  < 1)  return "Just now";
            if (mins  < 60) return mins + " min ago";
            if (hours < 24) return hours + " hr ago";
            if (days  < 7)  return days + " day" + (days > 1 ? "s" : "") + " ago";
            return t.format(DateTimeFormatter.ofPattern("dd MMM"));
        }
    }

    // ── Summary count response ─────────────────────────────────────
    @Data
    public static class NotificationSummary {
        private long   unreadCount;
        private long   totalCount;
        private List<NotificationResponse> recent;  // top 10

        public NotificationSummary(long unread, long total, List<NotificationResponse> recent) {
            this.unreadCount = unread;
            this.totalCount  = total;
            this.recent      = recent;
        }
    }

    // ── Admin: broadcast request ───────────────────────────────────
    @Data
    public static class BroadcastRequest {
        private String title;
        private String message;
        private String icon;
        private String targetRole;   // "ALL", "CUSTOMER", "PROVIDER"
        private String type;         // NotificationType name
    }
}
