package io.siggi.healthchecker.status;

import io.siggi.healthchecker.api.CheckResult;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * One completed check.
 *
 * @param time    when the check started
 * @param latency how long the check took, measured by the app
 * @param result  the plugin's result (a thrown exception or timeout becomes a failed result)
 */
public record CheckRecord(Instant time, Duration latency, CheckResult result) {
    public CheckRecord {
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(latency, "latency");
        Objects.requireNonNull(result, "result");
    }

    public boolean success() {
        return result.success();
    }

    public String message() {
        return result.message();
    }
}
