package io.siggi.healthchecker.notify;

import io.siggi.healthchecker.config.EndpointDefinition;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Routes notifications to channels, batches them and delivers them asynchronously with
 * retry and backoff. Never blocks the caller (checks, state listeners).
 *
 * <ul>
 *   <li><b>Routing</b>: an endpoint notifies the channels in its
 *       {@link EndpointDefinition#notifyChannels()}, which the config loader has already
 *       resolved ({@code notify} omitted = {@code defaults.notify}; {@code false} or
 *       {@code []} = none). Empty means the endpoint never notifies.</li>
 *   <li><b>Batching</b>: the first notification for a channel opens a window of
 *       {@code batchWindow}; everything raised for that channel until it closes is sent as
 *       one {@link NotificationBatch}.</li>
 *   <li><b>Delivery</b>: per channel, batches are sent one at a time in order. A temporary
 *       failure is retried after {@link RetryPolicy#delayAfter} (or the receiver's
 *       {@code retryAfter}, if later), with the same batch id; later batches wait behind it.
 *       Permanent failures, and the last allowed attempt failing, give the batch up.</li>
 *   <li>Every failure is logged and kept as the channel's last error ({@link #status}).</li>
 * </ul>
 * Thread-safe.
 */
public final class NotificationDispatcher implements AutoCloseable {
    public static final Duration DEFAULT_BATCH_WINDOW = Duration.ofSeconds(30);

    private final Map<String, Channel> channels;
    private final Duration batchWindow;
    private final RetryPolicy retry;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final Logger logger;
    private volatile boolean closed;

    /**
     * @param notifiers   channel name → its notifier
     * @param batchWindow how long a channel collects notifications before sending them
     * @param scheduler   runs timers and deliveries; closed with the dispatcher
     */
    public NotificationDispatcher(Map<String, ? extends Notifier> notifiers, Duration batchWindow, RetryPolicy retry,
                                  TaskScheduler scheduler, Clock clock, Logger logger) {
        Objects.requireNonNull(batchWindow, "batchWindow");
        if (batchWindow.isNegative()) {
            throw new IllegalArgumentException("negative batch window");
        }
        this.batchWindow = batchWindow;
        this.retry = Objects.requireNonNull(retry, "retry");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        Map<String, Channel> map = new LinkedHashMap<>();
        notifiers.forEach((name, notifier) -> map.put(name, new Channel(name, Objects.requireNonNull(notifier, name))));
        this.channels = Collections.unmodifiableMap(map);
    }

    /** A dispatcher with the default retry policy, the system clock and a real scheduler. */
    public static NotificationDispatcher create(Map<String, ? extends Notifier> notifiers, Duration batchWindow) {
        return new NotificationDispatcher(notifiers, batchWindow, RetryPolicy.DEFAULT, TaskScheduler.create(),
                Clock.systemUTC(), System.getLogger(NotificationDispatcher.class.getName()));
    }

    /** The configured channel names, in configuration order. */
    public Set<String> channels() {
        return channels.keySet();
    }

    /** The channels {@code endpoint} notifies (unknown names are skipped). */
    public List<String> route(EndpointDefinition endpoint) {
        List<String> out = new ArrayList<>();
        for (String name : endpoint.notifyChannels()) {
            if (channels.containsKey(name) && !out.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    /**
     * Queues {@code notification} for every channel {@code endpoint} notifies; nothing if it
     * notifies none.
     *
     * @return the channels it was queued for
     */
    public List<String> notify(EndpointDefinition endpoint, Notification notification) {
        Objects.requireNonNull(notification, "notification");
        List<String> route = route(endpoint);
        for (String name : route) {
            enqueue(channels.get(name), notification);
        }
        return route;
    }

    /**
     * Queues {@code notification} for the named channels directly, bypassing endpoint routing
     * (maintenance announcements, test notifications).
     *
     * @throws IllegalArgumentException if a name isn't a configured channel
     */
    public void send(Collection<String> channelNames, Notification notification) {
        Objects.requireNonNull(notification, "notification");
        List<Channel> targets = new ArrayList<>();
        for (String name : channelNames) {
            Channel ch = channels.get(name);
            if (ch == null) {
                throw new IllegalArgumentException("unknown notification channel \"" + name + "\"");
            }
            if (!targets.contains(ch)) {
                targets.add(ch);
            }
        }
        targets.forEach(ch -> enqueue(ch, notification));
    }

    /**
     * Queues {@code notification} for one channel as a message of its own, without waiting for
     * the batch window ("Send test notification"). It's delivered and retried like any batch, in
     * order behind batches already queued.
     *
     * @throws IllegalArgumentException if {@code channel} isn't configured
     */
    public void sendNow(String channel, Notification notification) {
        Objects.requireNonNull(notification, "notification");
        Channel ch = channels.get(channel);
        if (ch == null) {
            throw new IllegalArgumentException("unknown notification channel \"" + channel + "\"");
        }
        if (closed) {
            logger.log(Level.WARNING, "Notification dispatcher closed, dropping " + notification.kind()
                    + " notification for channel " + ch.name);
            return;
        }
        synchronized (ch) {
            ch.queue.add(new NotificationBatch(UUID.randomUUID(), ch.name, List.of(notification)));
            if (!ch.sending) {
                ch.sending = true;
                scheduler.execute(() -> attempt(ch, 1));
            }
        }
    }

    /** Delivery state of a channel, or empty if it isn't configured. */
    public Optional<ChannelStatus> status(String channel) {
        Channel ch = channels.get(channel);
        if (ch == null) {
            return Optional.empty();
        }
        synchronized (ch) {
            int pending = ch.queue.size() + (ch.collecting == null ? 0 : 1);
            return Optional.of(new ChannelStatus(ch.lastSuccess, ch.lastErrorTime, ch.lastError, pending));
        }
    }

    /** Stops timers and deliveries; anything still pending is dropped. */
    @Override
    public void close() {
        closed = true;
        scheduler.close();
    }

    // ---- internals -----------------------------------------------------------------------

    private static final class Channel {
        final String name;
        final Notifier notifier;
        /** The batch being collected, or {@code null} if no window is open. */
        List<Notification> collecting;
        /** Batches waiting; the head is the one being sent or retried. */
        final ArrayDeque<NotificationBatch> queue = new ArrayDeque<>();
        boolean sending;
        Instant lastSuccess;
        Instant lastErrorTime;
        String lastError;

        Channel(String name, Notifier notifier) {
            this.name = name;
            this.notifier = notifier;
        }
    }

    private void enqueue(Channel ch, Notification notification) {
        if (closed) {
            logger.log(Level.WARNING, "Notification dispatcher closed, dropping " + notification.kind()
                    + " notification for channel " + ch.name);
            return;
        }
        synchronized (ch) {
            if (ch.collecting == null) {
                ch.collecting = new ArrayList<>();
                scheduler.schedule(() -> closeWindow(ch), batchWindow);
            }
            ch.collecting.add(notification);
        }
    }

    private void closeWindow(Channel ch) {
        synchronized (ch) {
            if (ch.collecting == null) {
                return;
            }
            ch.queue.add(new NotificationBatch(UUID.randomUUID(), ch.name, ch.collecting));
            ch.collecting = null;
            if (!ch.sending) {
                ch.sending = true;
                scheduler.execute(() -> attempt(ch, 1));
            }
        }
    }

    private void attempt(Channel ch, int attempt) {
        if (closed) {
            return;
        }
        NotificationBatch batch;
        synchronized (ch) {
            batch = ch.queue.peek();
        }
        if (batch == null) {
            synchronized (ch) {
                ch.sending = false;
            }
            return;
        }
        NotifierException failure;
        try {
            ch.notifier.send(batch);
            failure = null;
        } catch (NotifierException e) {
            failure = e;
        } catch (RuntimeException e) {
            failure = new NotifierException(e.toString(), false, null, e);
        }
        Instant now = clock.instant();
        synchronized (ch) {
            if (failure == null) {
                ch.lastSuccess = now;
                ch.queue.poll();
                next(ch);
                return;
            }
            ch.lastErrorTime = now;
            ch.lastError = failure.getMessage();
            boolean giveUp = failure.permanent() || attempt >= retry.maxAttempts();
            if (giveUp) {
                logger.log(Level.ERROR, "Notification to channel " + ch.name + " failed"
                        + (failure.permanent() ? " permanently" : " after " + attempt + " attempts")
                        + ", giving up (" + batch.notifications().size() + " notification(s)): "
                        + failure.getMessage());
                ch.queue.poll();
                next(ch);
                return;
            }
            Duration delay = retry.delayAfter(attempt);
            if (failure.retryAfter() != null && failure.retryAfter().compareTo(delay) > 0) {
                delay = failure.retryAfter();
            }
            logger.log(Level.WARNING, "Notification to channel " + ch.name + " failed (attempt " + attempt
                    + "), retrying in " + delay.toMillis() + " ms: " + failure.getMessage());
            if (!closed) {
                scheduler.schedule(() -> attempt(ch, attempt + 1), delay);
            }
        }
    }

    /** Starts the next queued batch, or marks the channel idle. Holds the channel's lock. */
    private void next(Channel ch) {
        if (ch.queue.isEmpty() || closed) {
            ch.sending = false;
        } else {
            scheduler.execute(() -> attempt(ch, 1));
        }
    }
}
