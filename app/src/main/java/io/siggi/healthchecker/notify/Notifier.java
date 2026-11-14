package io.siggi.healthchecker.notify;

/**
 * Delivers messages to one configured channel (an email recipient list, a Discord webhook, …).
 * Called from one thread at a time per channel, never concurrently.
 */
public interface Notifier {
    /**
     * Delivers one message. Returning means it was accepted.
     *
     * @throws NotifierException if it wasn't; {@link NotifierException#permanent()} says
     *                           whether retrying could help
     */
    void send(NotificationBatch batch) throws NotifierException;
}
