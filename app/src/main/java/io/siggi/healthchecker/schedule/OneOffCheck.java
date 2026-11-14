package io.siggi.healthchecker.schedule;

import io.siggi.healthchecker.api.CheckResult;
import io.siggi.healthchecker.api.ConfigException;
import io.siggi.healthchecker.config.EndpointDefinition;
import io.siggi.healthchecker.status.CheckRecord;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs a single check for an endpoint definition that isn't registered anywhere ("Test now"): a
 * fresh checker is created, checked once with the scheduler's rules (own virtual thread, timeout
 * plus grace period, the same failure messages) and closed again. Nothing is recorded.
 */
public final class OneOffCheck {
    private static final System.Logger LOGGER = System.getLogger(OneOffCheck.class.getName());

    private final EndpointFactory factory;
    private final Duration grace;
    private final Clock clock;

    public OneOffCheck(EndpointFactory factory) {
        this(factory, CheckScheduler.DEFAULT_GRACE, Clock.systemUTC());
    }

    public OneOffCheck(EndpointFactory factory, Duration grace, Clock clock) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.grace = Objects.requireNonNull(grace, "grace");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Creates a checker for {@code definition}, runs one check and closes the checker (when the
     * check returns, or after the grace period at the latest if it ignores the interrupt).
     *
     * @throws ConfigException      if the checker can't be created
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public CheckRecord run(EndpointDefinition definition) throws ConfigException, InterruptedException {
        Endpoint endpoint = factory.create(definition);
        AtomicBoolean closed = new AtomicBoolean();
        CompletableFuture<CheckResult> future = new CompletableFuture<>();
        Instant time = clock.instant();
        long start = System.nanoTime();
        Thread thread = Thread.ofVirtual().name("test-check-" + endpoint.id()).start(() -> {
            CheckResult result;
            try {
                result = endpoint.checker().check(endpoint.timeout());
                if (result == null) result = CheckResult.fail("check returned no result");
            } catch (Throwable t) {
                if (!(t instanceof InterruptedException)) {
                    LOGGER.log(System.Logger.Level.WARNING, "Test check for " + endpoint.id() + " threw", t);
                }
                result = CheckResult.fail("check failed (" + t.getClass().getSimpleName() + ")");
            }
            future.complete(result);
            close(endpoint, closed);
        });
        CheckResult result;
        Duration latency;
        try {
            result = future.get(endpoint.timeout().plus(grace).toNanos(), TimeUnit.NANOSECONDS);
            latency = Duration.ofNanos(System.nanoTime() - start);
        } catch (TimeoutException e) {
            latency = Duration.ofNanos(System.nanoTime() - start);
            result = CheckResult.fail("timeout after " + endpoint.timeout().toMillis() + " ms");
            thread.interrupt();
            if (!thread.join(grace)) {
                LOGGER.log(System.Logger.Level.WARNING, "Test check for " + endpoint.id()
                        + " ignores interrupts; closing its checker anyway");
            }
            close(endpoint, closed);
        } catch (InterruptedException e) {
            thread.interrupt();
            close(endpoint, closed);
            throw e;
        } catch (ExecutionException e) {
            throw new IllegalStateException(e); // never completed exceptionally
        }
        return new CheckRecord(time, latency, result);
    }

    private static void close(Endpoint endpoint, AtomicBoolean closed) {
        if (closed.compareAndSet(false, true)) EndpointFactory.closeQuietly(endpoint.checker());
    }
}
