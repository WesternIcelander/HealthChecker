package io.siggi.healthchecker.web.view;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * One check as a viewer may see it. Fields the viewer may not see are {@code null}.
 *
 * @param at      when the check started
 * @param success pass or fail
 * @param latency how long it took ({@code null} unless the endpoint is {@code public} or the viewer is privileged)
 * @param message the check message ({@code null} when not visible, or left out by the leak filter)
 * @param details visible details in their original order ({@code null} when not visible; entries
 *                left out by the leak filter are simply missing)
 */
public record CheckView(Instant at, boolean success, Duration latency, String message, Map<String, Object> details) {
}
