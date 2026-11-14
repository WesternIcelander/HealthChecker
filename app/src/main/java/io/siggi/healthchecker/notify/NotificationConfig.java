package io.siggi.healthchecker.notify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.siggi.healthchecker.auth.AuthProviders;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The {@code notifications} section: {@code batchSeconds} and the named {@code channels}.
 *
 * <p>Channel types:
 * <ul>
 *   <li>{@code discord}: {@code webhookUrl} (required; https, or http on localhost; no credentials or
 *       fragment), {@code username} (optional) and {@code mention} (optional; role {@code <@&id>}, user
 *       {@code <@id>}, {@code @everyone} or {@code @here}, several separated by spaces).</li>
 *   <li>{@code email} (via SimpleMailer): {@code baseUrl} (required; https, or http on localhost; no
 *       credentials, query or fragment), {@code apiKey} (required; bearer key), {@code from} (required),
 *       {@code to} (required, non-empty list) and {@code requireTls} (optional boolean, default false).</li>
 * </ul>
 * Error messages never echo setting values (webhook URLs and API keys are secrets).
 */
public record NotificationConfig(Duration batchWindow, Map<String, Channel> channels) {
    public static final int DEFAULT_BATCH_SECONDS = 30;
    public static final int MAX_BATCH_SECONDS = 3600;

    private static final Set<String> ROOT_KEYS = Set.of("batchSeconds", "channels");
    private static final Set<String> DISCORD_KEYS = Set.of("type", "webhookUrl", "username", "mention");
    private static final Set<String> EMAIL_KEYS = Set.of("type", "baseUrl", "apiKey", "from", "to", "requireTls");
    private static final Pattern MENTION = Pattern.compile("<@&[0-9]{1,20}>|<@!?[0-9]{1,20}>|@everyone|@here");

    public NotificationConfig {
        Objects.requireNonNull(batchWindow, "batchWindow");
        channels = Collections.unmodifiableMap(new LinkedHashMap<>(channels));
    }

    /** A configured channel. */
    public sealed interface Channel permits Discord, Email {
        /** The configured {@code type}. */
        String type();
    }

    /**
     * @param webhookUrl the webhook (a secret: never logged or shown)
     * @param username   overrides the webhook's name, or {@code null}
     * @param mention    pinged with down notifications and reminders, or {@code null}
     */
    public record Discord(URI webhookUrl, String username, String mention) implements Channel {
        public Discord {
            Objects.requireNonNull(webhookUrl, "webhookUrl");
        }

        @Override
        public String type() {
            return "discord";
        }
    }

    /**
     * An email channel, delivered through a SimpleMailer instance.
     *
     * @param baseUrl    SimpleMailer's base URL; messages go to {@code {baseUrl}/v1/messages}
     * @param apiKey     the bearer key (a secret: never logged or shown)
     * @param from       the {@code From} address
     * @param to         the recipients; never empty
     * @param requireTls deliver only over STARTTLS
     */
    public record Email(URI baseUrl, String apiKey, String from, List<String> to, boolean requireTls)
            implements Channel {
        public Email {
            Objects.requireNonNull(baseUrl, "baseUrl");
            Objects.requireNonNull(apiKey, "apiKey");
            Objects.requireNonNull(from, "from");
            to = List.copyOf(to);
            if (to.isEmpty()) throw new IllegalArgumentException("no recipients");
        }

        @Override
        public String type() {
            return "email";
        }

        /** Without the API key. */
        @Override
        public String toString() {
            return "Email[baseUrl=" + baseUrl + ", from=" + from + ", to=" + to + ", requireTls=" + requireTls + "]";
        }
    }

    /** {@link #parse(JsonObject, List, List)} that throws on the first problem and ignores warnings. */
    public static NotificationConfig parse(JsonObject notifications) {
        List<String> errors = new ArrayList<>();
        NotificationConfig config = parse(notifications, errors, new ArrayList<>());
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
        return config;
    }

    /**
     * Reads the section.
     *
     * @param notifications the raw, env-substituted {@code notifications} section, or {@code null}
     * @param errors        receives one message per problem, prefixed with its path
     * @param warnings      receives startup warnings
     */
    public static NotificationConfig parse(JsonObject notifications, List<String> errors, List<String> warnings) {
        Map<String, Channel> channels = new LinkedHashMap<>();
        if (notifications == null) {
            return new NotificationConfig(Duration.ofSeconds(DEFAULT_BATCH_SECONDS), channels);
        }
        for (String key : notifications.keySet()) {
            if (!ROOT_KEYS.contains(key)) errors.add("notifications: unknown key \"" + key + "\"");
        }
        int batchSeconds = DEFAULT_BATCH_SECONDS;
        JsonElement batch = notifications.get("batchSeconds");
        if (batch != null && !batch.isJsonNull()) {
            Integer value = wholeNumber(batch);
            if (value == null || value < 0 || value > MAX_BATCH_SECONDS) {
                errors.add("notifications.batchSeconds: must be a whole number from 0 to " + MAX_BATCH_SECONDS);
            } else {
                batchSeconds = value;
            }
        }
        JsonElement list = notifications.get("channels");
        if (list != null && !list.isJsonNull()) {
            if (!(list instanceof JsonObject object)) {
                errors.add("notifications.channels: must be an object");
            } else {
                for (Map.Entry<String, JsonElement> e : object.entrySet()) {
                    Channel channel = channel(e.getKey(), e.getValue(), errors, warnings);
                    if (channel != null) channels.put(e.getKey(), channel);
                }
            }
        }
        return new NotificationConfig(Duration.ofSeconds(batchSeconds), channels);
    }

    /**
     * One notifier per channel, in configuration order.
     *
     * @param zone the timezone times are written in where the receiver can't localise them (email)
     */
    public Map<String, Notifier> notifiers(HttpClient client, ZoneId zone) {
        Map<String, Notifier> out = new LinkedHashMap<>();
        channels.forEach((name, channel) -> out.put(name, switch (channel) {
            case Discord d -> new DiscordNotifier(d, client);
            case Email e -> new EmailNotifier(e, client, zone);
        }));
        return out;
    }

    /** Channel name → type, for display. */
    public Map<String, String> types() {
        Map<String, String> out = new LinkedHashMap<>();
        channels.forEach((name, channel) -> out.put(name, channel.type()));
        return Collections.unmodifiableMap(out);
    }

    private static Channel channel(String name, JsonElement element, List<String> errors, List<String> warnings) {
        String path = "notifications.channels \"" + name + "\"";
        if (!(element instanceof JsonObject o)) {
            errors.add(path + ": must be an object");
            return null;
        }
        String type = string(o, "type");
        if (type == null) {
            errors.add(path + ": type must be \"discord\" or \"email\"");
            return null;
        }
        switch (type) {
            case "discord" -> {
                return discord(o, path, errors);
            }
            case "email" -> {
                return email(o, path, errors);
            }
            default -> {
                errors.add(path + ": unknown type \"" + type + "\" (available: discord, email)");
                return null;
            }
        }
    }

    private static Discord discord(JsonObject o, String path, List<String> errors) {
        int before = errors.size();
        for (String key : o.keySet()) {
            if (!DISCORD_KEYS.contains(key)) errors.add(path + ": unknown key \"" + key + "\"");
        }
        URI url = httpUrl(o.get("webhookUrl"), "webhookUrl", true, path, errors);
        String username = null;
        JsonElement u = o.get("username");
        if (u != null && !u.isJsonNull()) {
            username = string(o, "username");
            String lower = username == null ? null : username.toLowerCase(Locale.ROOT);
            if (username == null || username.isBlank() || username.length() > 80
                    || username.chars().anyMatch(Character::isISOControl)) {
                errors.add(path + ": username must be 1 to 80 characters without control characters");
            } else if (lower.contains("discord") || lower.contains("clyde")) {
                errors.add(path + ": username can't contain \"discord\" or \"clyde\" (Discord rejects it)");
            }
        }
        String mention = null;
        JsonElement m = o.get("mention");
        if (m != null && !m.isJsonNull()) {
            mention = string(o, "mention");
            if (mention == null || mention.isBlank() || !mentionValid(mention)) {
                errors.add(path + ": mention must be role (<@&id>), user (<@id>), @everyone or @here mentions,"
                        + " separated by spaces");
            } else {
                mention = String.join(" ", mention.strip().split("\\s+"));
            }
        }
        return errors.size() == before ? new Discord(url, username, mention) : null;
    }

    private static Email email(JsonObject o, String path, List<String> errors) {
        int before = errors.size();
        for (String key : o.keySet()) {
            if (!EMAIL_KEYS.contains(key)) errors.add(path + ": unknown key \"" + key + "\"");
        }
        URI baseUrl = httpUrl(o.get("baseUrl"), "baseUrl", false, path, errors);
        String apiKey = string(o, "apiKey");
        if (apiKey == null || apiKey.isEmpty()) {
            errors.add(path + ": apiKey is required");
        } else if (apiKey.chars().anyMatch(c -> c <= ' ' || c == 0x7F)) {
            errors.add(path + ": apiKey must not contain spaces or control characters");
        }
        String from = string(o, "from");
        if (from == null || from.isBlank()) {
            errors.add(path + ": from is required");
        } else if (!addressValid(from)) {
            errors.add(path + ": from must be an email address without control characters");
        }
        List<String> to = new ArrayList<>();
        JsonElement t = o.get("to");
        if (!(t instanceof JsonArray array) || array.isEmpty()) {
            errors.add(path + ": to must be a non-empty list of email addresses");
        } else {
            for (JsonElement e : array) {
                String address = e instanceof JsonPrimitive p && p.isString() ? p.getAsString().strip() : null;
                if (address == null || !addressValid(address)) {
                    errors.add(path + ": to must be a non-empty list of email addresses");
                    break;
                }
                to.add(address);
            }
        }
        boolean requireTls = false;
        JsonElement tls = o.get("requireTls");
        if (tls != null && !tls.isJsonNull()) {
            if (tls instanceof JsonPrimitive p && p.isBoolean()) {
                requireTls = p.getAsBoolean();
            } else {
                errors.add(path + ": requireTls must be true or false");
            }
        }
        return errors.size() == before ? new Email(baseUrl, apiKey, from.strip(), to, requireTls) : null;
    }

    /** Loosely an address ({@code a@b}, optionally with a display name); SimpleMailer checks the rest. */
    private static boolean addressValid(String address) {
        return address.contains("@") && !address.isBlank() && address.length() <= 320
                && address.chars().noneMatch(Character::isISOControl);
    }

    static boolean mentionValid(String mention) {
        for (String token : mention.strip().split("\\s+")) {
            if (!MENTION.matcher(token).matches()) return false;
        }
        return true;
    }

    /** An https URL (http only for loopback) without credentials or fragment; a query only if allowed. */
    private static URI httpUrl(JsonElement element, String key, boolean allowQuery, String path, List<String> errors) {
        if (!(element instanceof JsonPrimitive p && p.isString()) || p.getAsString().isEmpty()) {
            errors.add(path + ": " + key + " is required");
            return null;
        }
        try {
            URI uri = new URI(p.getAsString());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if (!uri.isAbsolute() || host == null || host.isEmpty()
                    || !(scheme.equals("https") || scheme.equals("http"))) {
                errors.add(path + ": " + key + " must be an absolute https URL");
                return null;
            }
            if (scheme.equals("http") && !AuthProviders.isLoopback(host)) {
                errors.add(path + ": " + key + " must use https (http is only allowed for localhost)");
                return null;
            }
            if (uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
                errors.add(path + ": " + key + " must not contain credentials or a fragment");
                return null;
            }
            if (!allowQuery && uri.getRawQuery() != null) {
                errors.add(path + ": " + key + " must not contain a query");
                return null;
            }
            return uri;
        } catch (URISyntaxException e) {
            errors.add(path + ": " + key + " is not a valid URL");
            return null;
        }
    }

    private static String string(JsonObject o, String key) {
        return o.get(key) instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }

    private static Integer wholeNumber(JsonElement e) {
        if (!(e instanceof JsonPrimitive p) || !p.isNumber()) return null;
        try {
            return p.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException ex) {
            return null;
        }
    }
}
