package io.siggi.healthchecker.web.view;

import io.siggi.healthchecker.config.Visibility;
import io.siggi.healthchecker.plugin.JsonEndpointConfig.Target;
import io.siggi.healthchecker.status.State;

import java.time.Instant;
import java.util.List;

/**
 * An endpoint as a viewer may see it. Fields the viewer may not see are {@code null}.
 * Uptime and day summaries aren't part of this view: they're shown for every visible
 * endpoint, so callers compute them only for endpoints that got a view.
 *
 * @param type                  {@code public} endpoints and privileged viewers only
 * @param since                 when the current state was entered ({@code null} while UNKNOWN)
 * @param lastCheck             {@code public} endpoints and privileged viewers only
 * @param recentChecks          oldest first; for {@code public-status} endpoints only time and pass/fail
 * @param visibility            privileged only
 * @param targets               declared targets; privileged only, never for anonymous viewers
 * @param notifyChannels        privileged only
 * @param reminders             privileged only
 * @param reminderRepeatMinutes privileged only
 */
public record EndpointView(String id, String name, String type, State state, Instant since, CheckView lastCheck,
                           List<CheckView> recentChecks, Visibility visibility, List<Target> targets,
                           List<String> notifyChannels, List<Integer> reminders, Integer reminderRepeatMinutes) {
    public EndpointView {
        recentChecks = List.copyOf(recentChecks);
        targets = targets == null ? null : List.copyOf(targets);
        notifyChannels = notifyChannels == null ? null : List.copyOf(notifyChannels);
        reminders = reminders == null ? null : List.copyOf(reminders);
    }
}
