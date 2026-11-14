package io.siggi.healthchecker.notify;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.siggi.healthchecker.web.Durations;
import io.siggi.healthchecker.web.Html;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Sends notifications as email through a SimpleMailer instance: {@code POST {baseUrl}/v1/messages}
 * with {@code {id, from, to, subject, text, html, requireTls}}.
 *
 * <ul>
 *   <li>{@code id} is the batch id, so every retry of a message carries the same id, and the
 *       payload depends only on the batch, so SimpleMailer's idempotency answers a repeat with
 *       {@code 200} instead of sending twice.</li>
 *   <li>{@code 2xx} ({@code 202} new, {@code 200} already known) is success. {@code 5xx},
 *       {@code 408}, {@code 429}, timeouts and connection failures are retried ({@code 429}'s
 *       {@code Retry-After} honoured). {@code 409} (same id, different payload) can only be a bug
 *       here: it is logged as one and not retried. {@code 400}, {@code 401} and every other status
 *       are permanent.</li>
 *   <li>Times are written in the configured timezone, since email can't localise them for each
 *       reader. Names and messages are HTML-escaped in the HTML body.</li>
 * </ul>
 * Error messages never contain the API key.
 */
public final class EmailNotifier implements Notifier {
    public static final String SUBJECT_PREFIX = "[HealthChecker] ";
    public static final Duration TIMEOUT = Duration.ofSeconds(30);
    static final int MAX_MESSAGE_TEXT = 2000;
    static final int MAX_SUBJECT = 200;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm", Locale.ENGLISH);

    private final URI endpoint;
    private final NotificationConfig.Email config;
    private final HttpClient client;
    private final ZoneId zone;
    private final System.Logger logger;

    public EmailNotifier(NotificationConfig.Email config, HttpClient client, ZoneId zone) {
        this(config, client, zone, System.getLogger(EmailNotifier.class.getName()));
    }

    public EmailNotifier(NotificationConfig.Email config, HttpClient client, ZoneId zone, System.Logger logger) {
        this.config = Objects.requireNonNull(config, "config");
        this.client = Objects.requireNonNull(client, "client");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.endpoint = messagesUrl(config.baseUrl());
    }

    /** {@code {baseUrl}/v1/messages}, with or without a trailing slash on the base URL. */
    static URI messagesUrl(URI baseUrl) {
        String base = baseUrl.toString();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return URI.create(base + "/v1/messages");
    }

    @Override
    public void send(NotificationBatch batch) throws NotifierException {
        post(batch.id().toString(), payload(batch));
    }

    // ---- payload -------------------------------------------------------------------------

    /** The request body for a batch. Depends only on the batch, so retries send exactly the same. */
    public JsonObject payload(NotificationBatch batch) {
        JsonObject o = new JsonObject();
        o.addProperty("id", batch.id().toString());
        o.addProperty("from", config.from());
        JsonArray to = new JsonArray();
        config.to().forEach(to::add);
        o.add("to", to);
        o.addProperty("subject", subject(batch.notifications()));
        List<Section> sections = batch.notifications().stream().map(this::section).toList();
        o.addProperty("text", text(sections));
        o.addProperty("html", html(sections, subject(batch.notifications())));
        o.addProperty("requireTls", config.requireTls());
        return o;
    }

    /**
     * E.g. {@code [HealthChecker] Website down} for one notification, or
     * {@code [HealthChecker] 2 down, 1 recovered} for several.
     */
    static String subject(List<Notification> notifications) {
        String summary;
        if (notifications.size() == 1) {
            Notification n = notifications.getFirst();
            String name = name(n);
            summary = switch (n.kind()) {
                case DOWN -> name + " down";
                case REMINDER -> name + " still down" + n.outageDuration().map(d -> " (" + Durations.format(d) + ")")
                        .orElse("");
                case RECOVERED -> name + " recovered" + n.outageDuration()
                        .map(d -> " after " + Durations.format(d)).orElse("");
                case TEST -> "Test notification";
                case ANNOUNCEMENT -> n.endpointName() != null ? n.endpointName() : "Announcement";
            };
        } else {
            Map<Notification.Kind, Integer> counts = new EnumMap<>(Notification.Kind.class);
            for (Notification n : notifications) counts.merge(n.kind(), 1, Integer::sum);
            List<String> parts = new ArrayList<>();
            counts.forEach((kind, count) -> parts.add(count + " " + switch (kind) {
                case DOWN -> "down";
                case REMINDER -> "still down";
                case RECOVERED -> "recovered";
                case TEST -> count == 1 ? "test notification" : "test notifications";
                case ANNOUNCEMENT -> count == 1 ? "announcement" : "announcements";
            }));
            summary = String.join(", ", parts);
        }
        return truncate(SUBJECT_PREFIX + oneLine(summary), MAX_SUBJECT);
    }

    /** One notification as a heading, lines of plain text, and an optional link. */
    record Section(Notification.Kind kind, String heading, List<String> lines, String link) {
    }

    Section section(Notification n) {
        String name = name(n);
        String heading;
        List<String> lines = new ArrayList<>();
        switch (n.kind()) {
            case DOWN -> {
                heading = name + " is down";
                lines.add("Down since " + time(n.outageStart() != null ? n.outageStart() : n.time()));
            }
            case REMINDER -> {
                heading = name + " is still down";
                lines.add(n.outageDuration().map(d -> "Down for " + Durations.format(d) + ", since "
                        + time(n.outageStart())).orElse("Still down"));
            }
            case RECOVERED -> {
                heading = name + " recovered";
                lines.add(n.outageDuration().map(d -> "Back up after " + Durations.format(d) + " (down since "
                        + time(n.outageStart()) + ")").orElse("Back up at " + time(n.time())));
            }
            case TEST -> {
                heading = "Test notification";
                lines.add("Sent " + time(n.time()));
            }
            case ANNOUNCEMENT -> heading = n.endpointName() != null ? n.endpointName() : "Announcement";
            default -> throw new IllegalStateException();
        }
        if (n.failureMessage() != null && !n.failureMessage().isEmpty() && n.kind() != Notification.Kind.RECOVERED) {
            lines.add("Latest failure: " + truncate(n.failureMessage(), MAX_MESSAGE_TEXT));
        }
        if (n.text() != null && !n.text().isEmpty()) {
            lines.addAll(truncate(n.text(), MAX_MESSAGE_TEXT).lines().toList());
        }
        String link = n.link() != null && (n.link().startsWith("https://") || n.link().startsWith("http://"))
                ? n.link() : null;
        return new Section(n.kind(), oneLine(heading), lines, link);
    }

    static String text(List<Section> sections) {
        StringBuilder out = new StringBuilder();
        for (Section s : sections) {
            out.append(s.heading()).append('\n');
            for (String line : s.lines()) out.append(line).append('\n');
            if (s.link() != null) out.append("Details: ").append(s.link()).append('\n');
            out.append('\n');
        }
        out.append("-- \nSent by HealthChecker\n");
        return out.toString();
    }

    static String html(List<Section> sections, String subject) {
        StringBuilder out = new StringBuilder();
        out.append("<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"><title>").append(Html.escape(subject))
                .append("</title></head>\n<body style=\"font-family:-apple-system,'Segoe UI',Helvetica,Arial,"
                        + "sans-serif;font-size:15px;line-height:1.4;color:#222222\">\n");
        for (Section s : sections) {
            out.append("<div style=\"border-left:4px solid ").append(color(s.kind()))
                    .append(";padding:4px 12px;margin:0 0 16px\">\n<h2 style=\"margin:0 0 6px;font-size:18px\">")
                    .append(Html.escape(s.heading())).append("</h2>\n");
            if (!s.lines().isEmpty()) {
                out.append("<p style=\"margin:0\">");
                for (int i = 0; i < s.lines().size(); i++) {
                    if (i > 0) out.append("<br>\n");
                    out.append(Html.escape(s.lines().get(i)));
                }
                out.append("</p>\n");
            }
            if (s.link() != null) {
                out.append("<p style=\"margin:6px 0 0\"><a href=\"").append(Html.escape(s.link()))
                        .append("\">Details</a></p>\n");
            }
            out.append("</div>\n");
        }
        out.append("<p style=\"color:#78909c;font-size:12px\">Sent by HealthChecker</p>\n</body></html>\n");
        return out.toString();
    }

    private static String color(Notification.Kind kind) {
        return switch (kind) {
            case DOWN -> "#d32f2f";
            case REMINDER -> "#f57c00";
            case RECOVERED -> "#388e3c";
            case ANNOUNCEMENT -> "#1e88e5";
            case TEST -> "#78909c";
        };
    }

    private static String name(Notification n) {
        return n.endpointName() != null ? n.endpointName() : n.endpointId() != null ? n.endpointId() : "Endpoint";
    }

    /** "Sat 1 Jan 2026 12:00 (UTC)" in the configured timezone. */
    private String time(Instant t) {
        return DATE_TIME.format(t.atZone(zone)) + " (" + zone.getId() + ")";
    }

    /** Control characters (line breaks included) become spaces: for the subject and headings. */
    private static String oneLine(String s) {
        return s.replaceAll("\\p{Cntrl}+", " ").strip();
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        int end = max - 1;
        if (Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end) + "…";
    }

    // ---- HTTP ----------------------------------------------------------------------------

    private void post(String id, JsonObject payload) throws NotifierException {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .header("User-Agent", "HealthChecker")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw NotifierException.temporary("SimpleMailer didn't answer in time");
        } catch (IOException e) {
            throw NotifierException.temporary("couldn't reach SimpleMailer (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw NotifierException.temporary("interrupted");
        }
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return;
        }
        String detail = errorDetail(response.body());
        if (status == 409) {
            String message = "SimpleMailer answered 409: message " + id
                    + " was already submitted with a different payload" + detail
                    + ". This is a bug in HealthChecker; the message isn't retried.";
            logger.log(System.Logger.Level.ERROR, message);
            throw NotifierException.permanent(message);
        }
        if (status == 401) {
            throw NotifierException.permanent("SimpleMailer rejected the API key (401)" + detail);
        }
        if (status == 429) {
            Duration wait = response.headers().firstValue("Retry-After").map(EmailNotifier::seconds).orElse(null);
            throw NotifierException.retryAfter("rate limited by SimpleMailer (429)" + detail, wait);
        }
        if (status >= 500 || status == 408) {
            throw NotifierException.temporary("SimpleMailer answered " + status + detail);
        }
        throw NotifierException.permanent("SimpleMailer rejected the message with " + status + detail);
    }

    private static Duration seconds(String header) {
        try {
            long s = Long.parseLong(header.trim());
            return s >= 0 && s <= 86400 ? Duration.ofSeconds(s) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** SimpleMailer's {@code error}, {@code message} and validation {@code errors}, e.g. {@code " (invalid: …)"}. */
    static String errorDetail(String body) {
        try {
            if (JsonParser.parseString(body) instanceof JsonObject o) {
                List<String> parts = new ArrayList<>();
                String error = o.get("error") instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
                String message = o.get("message") instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
                if (message != null) parts.add(message);
                if (o.get("errors") instanceof JsonArray errors) {
                    for (JsonElement e : errors) {
                        if (e instanceof JsonPrimitive p && p.isString()) parts.add(p.getAsString());
                    }
                }
                if (error == null && parts.isEmpty()) return "";
                String text = (error != null ? error + (parts.isEmpty() ? "" : ": ") : "") + String.join("; ", parts);
                return " (" + truncate(oneLine(text), 300) + ")";
            }
        } catch (RuntimeException ignored) {
            // not JSON
        }
        return "";
    }
}
