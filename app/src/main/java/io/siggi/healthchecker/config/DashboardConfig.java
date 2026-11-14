package io.siggi.healthchecker.config;

/**
 * Dashboard settings. Day segments are yellow when uptime is below {@code yellowBelow}
 * (and there is downtime), orange below {@code orangeBelow}, red below {@code redBelow}.
 */
public record DashboardConfig(String title, double yellowBelow, double orangeBelow, double redBelow) {
}
