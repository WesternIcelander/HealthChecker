package io.siggi.healthchecker.notify;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.siggi.healthchecker.web.Durations;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Posts notifications to a Discord webhook as embeds: red for down, orange for reminders,
 * green for recovered, blue for announcements, grey for tests.
 *
 * <ul>
 *   <li>A message holds at most {@value #MAX_EMBEDS} embeds and {@value #MAX_MESSAGE_CHARS} characters
 *       of embed text; a larger batch is split into several messages, sent in order. If a later part
 *       fails, a retry of the same batch continues with that part (parts already accepted aren't
 *       sent twice).</li>
 *   <li>The configured {@code mention} goes into the content of the first message with a down or
 *       reminder embed, never for recoveries alone. {@code allowed_mentions} is set on every message,
 *       so only that mention can ping anyone (not an {@code @everyone} inside a name or failure
 *       message).</li>
 *   <li>{@code 2xx} is success; {@code 429} is retried after Discord's {@code retry_after} (body, else
 *       the {@code Retry-After} header); {@code 5xx}, timeouts and connection failures are retried;
 *       any other status is permanent.</li>
 * </ul>
 * Error messages never contain the webhook URL.
 */
public final class DiscordNotifier implements Notifier {
    public static final int MAX_EMBEDS = 10;
    public static final int MAX_MESSAGE_CHARS = 6000;
    static final int MAX_TITLE = 256;
    static final int MAX_MESSAGE_TEXT = 1000;
    static final int MAX_DESCRIPTION = 4096;
    public static final Duration TIMEOUT = Duration.ofSeconds(10);

    public static final int RED = 0xD32F2F;
    public static final int ORANGE = 0xF57C00;
    public static final int GREEN = 0x388E3C;
    public static final int BLUE = 0x1E88E5;
    public static final int GREY = 0x78909C;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final URI webhookUrl;
    private final String username;
    private final String mention;
    private final HttpClient client;
    /** The batch whose first {@link #partsSent} messages were accepted, while it isn't complete. */
    private UUID partialBatch;
    private int partsSent;

    public DiscordNotifier(NotificationConfig.Discord config, HttpClient client) {
        this.webhookUrl = config.webhookUrl();
        this.username = config.username();
        this.mention = config.mention();
        this.client = Objects.requireNonNull(client, "client");
    }

    /** An HTTP client for webhooks: HTTP/1.1, no redirects, {@link #TIMEOUT} to connect. */
    public static HttpClient httpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(TIMEOUT)
                .build();
    }

    @Override
    public synchronized void send(NotificationBatch batch) throws NotifierException {
        List<JsonObject> parts = payloads(batch, username, mention);
        int start = batch.id().equals(partialBatch) ? partsSent : 0;
        for (int i = start; i < parts.size(); i++) {
            post(parts.get(i), i, parts.size());
            partialBatch = batch.id();
            partsSent = i + 1;
        }
        partialBatch = null;
        partsSent = 0;
    }

    // ---- payloads ------------------------------------------------------------------------

    /** The webhook messages for a batch, in order. Depends only on the batch, so retries send the same. */
    public static List<JsonObject> payloads(NotificationBatch batch, String username, String mention) {
        List<List<JsonObject>> groups = new ArrayList<>();
        List<Boolean> pings = new ArrayList<>();
        List<JsonObject> current = new ArrayList<>();
        int chars = 0;
        boolean ping = false;
        for (Notification n : batch.notifications()) {
            JsonObject embed = embed(n);
            int size = size(embed);
            if (!current.isEmpty() && (current.size() >= MAX_EMBEDS || chars + size > MAX_MESSAGE_CHARS)) {
                groups.add(current);
                pings.add(ping);
                current = new ArrayList<>();
                chars = 0;
                ping = false;
            }
            current.add(embed);
            chars += size;
            ping |= n.kind() == Notification.Kind.DOWN || n.kind() == Notification.Kind.REMINDER;
        }
        groups.add(current);
        pings.add(ping);

        List<JsonObject> out = new ArrayList<>();
        boolean mentioned = false;
        for (int i = 0; i < groups.size(); i++) {
            JsonObject message = new JsonObject();
            boolean withMention = mention != null && pings.get(i) && !mentioned;
            if (withMention) {
                message.addProperty("content", mention);
                mentioned = true;
            }
            if (username != null) message.addProperty("username", username);
            JsonArray embeds = new JsonArray();
            groups.get(i).forEach(embeds::add);
            message.add("embeds", embeds);
            message.add("allowed_mentions", allowedMentions(withMention ? mention : null));
            out.add(message);
        }
        return out;
    }

    /** Only what {@code mention} names may ping; with {@code null}, nothing. */
    static JsonObject allowedMentions(String mention) {
        Set<String> parse = new LinkedHashSet<>();
        Set<String> roles = new LinkedHashSet<>();
        Set<String> users = new LinkedHashSet<>();
        if (mention != null) {
            for (String token : mention.split("\\s+")) {
                if (token.equals("@everyone") || token.equals("@here")) {
                    parse.add("everyone");
                } else if (token.startsWith("<@&")) {
                    roles.add(token.substring(3, token.length() - 1));
                } else if (token.startsWith("<@!")) {
                    users.add(token.substring(3, token.length() - 1));
                } else if (token.startsWith("<@")) {
                    users.add(token.substring(2, token.length() - 1));
                }
            }
        }
        JsonObject o = new JsonObject();
        o.add("parse", array(parse));
        if (!roles.isEmpty()) o.add("roles", array(roles));
        if (!users.isEmpty()) o.add("users", array(users));
        return o;
    }

    static JsonObject embed(Notification n) {
        String name = n.endpointName() != null ? escape(n.endpointName())
                : n.endpointId() != null ? escape(n.endpointId()) : null;
        String title;
        int color;
        List<String> lines = new ArrayList<>();
        switch (n.kind()) {
            case DOWN -> {
                title = name + " is down";
                color = RED;
                lines.add("Down since " + timestamp(n.outageStart() != null ? n.outageStart() : n.time()));
            }
            case REMINDER -> {
                title = name + " is still down";
                color = ORANGE;
                lines.add(n.outageDuration().map(d -> "Down for " + Durations.format(d) + ", since "
                        + timestamp(n.outageStart())).orElse("Still down"));
            }
            case RECOVERED -> {
                title = name + " recovered";
                color = GREEN;
                lines.add(n.outageDuration().map(d -> "Back up after " + Durations.format(d) + " (down since "
                        + timestamp(n.outageStart()) + ")").orElse("Back up"));
            }
            case TEST -> {
                title = "Test notification";
                color = GREY;
            }
            case ANNOUNCEMENT -> {
                title = name != null ? name : "Announcement";
                color = BLUE;
            }
            default -> throw new IllegalStateException();
        }
        if (n.failureMessage() != null && !n.failureMessage().isEmpty()
                && n.kind() != Notification.Kind.RECOVERED) {
            lines.add("Latest failure: " + escape(truncate(n.failureMessage(), MAX_MESSAGE_TEXT)));
        }
        if (n.text() != null && !n.text().isEmpty()) {
            lines.add(escape(truncate(n.text(), MAX_MESSAGE_TEXT)));
        }
        JsonObject embed = new JsonObject();
        embed.addProperty("title", truncate(title, MAX_TITLE));
        if (!lines.isEmpty()) {
            embed.addProperty("description", truncate(String.join("\n", lines), MAX_DESCRIPTION));
        }
        embed.addProperty("color", color);
        embed.addProperty("timestamp", n.time().toString());
        if (n.link() != null && (n.link().startsWith("https://") || n.link().startsWith("http://"))) {
            embed.addProperty("url", n.link());
        }
        return embed;
    }

    /** Discord's characters-per-message count: title and description. */
    private static int size(JsonObject embed) {
        int size = 0;
        for (String key : List.of("title", "description")) {
            JsonElement e = embed.get(key);
            if (e != null) size += e.getAsString().length();
        }
        return size;
    }

    /** A Discord timestamp, shown in each reader's own timezone. */
    private static String timestamp(java.time.Instant t) {
        return "<t:" + t.getEpochSecond() + ":f>";
    }

    /** Escapes Discord markdown, so names and messages show as written. */
    static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ("\\*_~`|>[]()#-<:@".indexOf(c) >= 0) out.append('\\');
            out.append(c);
        }
        return out.toString();
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        int end = max - 1;
        if (Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end) + "…";
    }

    private static JsonArray array(Set<String> values) {
        JsonArray a = new JsonArray();
        values.forEach(a::add);
        return a;
    }

    // ---- HTTP ----------------------------------------------------------------------------

    private void post(JsonObject payload, int index, int total) throws NotifierException {
        String part = total > 1 ? " (message " + (index + 1) + " of " + total + ")" : "";
        HttpRequest request = HttpRequest.newBuilder(webhookUrl)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("User-Agent", "HealthChecker")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new NotifierException("Discord didn't answer in time" + part, false, null, null);
        } catch (IOException e) {
            throw new NotifierException("couldn't reach Discord (" + e.getClass().getSimpleName() + ")" + part,
                    false, null, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NotifierException("interrupted" + part, false, null, null);
        }
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return;
        }
        if (status == 429) {
            Duration wait = retryAfter(response).orElse(null);
            throw new NotifierException("rate limited by Discord (429)" + part, false, wait, null);
        }
        String detail = errorDetail(response.body());
        if (status >= 500) {
            throw new NotifierException("Discord answered " + status + detail + part, false, null, null);
        }
        throw new NotifierException("Discord rejected the message with " + status + detail + part, true, null, null);
    }

    /** {@code retry_after} (seconds, may be fractional) from the body, else the {@code Retry-After} header. */
    static Optional<Duration> retryAfter(HttpResponse<String> response) {
        try {
            JsonElement body = JsonParser.parseString(response.body());
            if (body instanceof JsonObject o && o.get("retry_after") instanceof JsonPrimitive p && p.isNumber()) {
                return seconds(p.getAsDouble());
            }
        } catch (RuntimeException ignored) {
            // not JSON: fall back to the header
        }
        Optional<String> header = response.headers().firstValue("Retry-After");
        if (header.isPresent()) {
            try {
                return seconds(Double.parseDouble(header.get().trim()));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Optional<Duration> seconds(double s) {
        if (!(s >= 0) || s > 86400) return Optional.empty();
        return Optional.of(Duration.ofMillis((long) Math.ceil(s * 1000)));
    }

    /** Discord's {@code code} and {@code message}, e.g. {@code " (10015 Unknown Webhook)"}, or "". */
    private static String errorDetail(String body) {
        try {
            if (JsonParser.parseString(body) instanceof JsonObject o) {
                String code = o.get("code") instanceof JsonPrimitive p ? p.getAsString() : null;
                String message = o.get("message") instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
                if (message != null) {
                    message = message.replaceAll("\\p{Cntrl}", " ");
                    if (message.length() > 200) message = message.substring(0, 200) + "…";
                    return " (" + (code != null ? code + " " : "") + message + ")";
                }
            }
        } catch (RuntimeException ignored) {
            // not JSON
        }
        return "";
    }
}
