package io.siggi.healthchecker.notify;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Notifications for one channel combined into one message.
 *
 * @param id            stable for this message across retries (e.g. SimpleMailer's idempotency id)
 * @param channel       the channel name
 * @param notifications in the order they were raised; never empty
 */
public record NotificationBatch(UUID id, String channel, List<Notification> notifications) {
    public NotificationBatch {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(channel, "channel");
        notifications = List.copyOf(notifications);
        if (notifications.isEmpty()) {
            throw new IllegalArgumentException("empty batch");
        }
    }
}
