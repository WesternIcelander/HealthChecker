package io.siggi.healthchecker.notify;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.siggi.healthchecker.auth.AuthProviders;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.time.Duration;
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
 *   <li>{@code email}: delivered from M15 on; until then its settings aren't checked, a startup
 *       warning says so, and messages to it fail permanently.</li>
 * </ul>
 * Error messages never echo setting values (webhook URLs are secrets).
 */
public record NotificationConfig(Duration batchWindow, Map<String, Channel> channels) {
    public static final int DEFAULT_BATCH_SECONDS = 30;
    public static final int MAX_BATCH_SECONDS = 3600;

    private static final Set<String> ROOT_KEYS = Set.of("batchSeconds", "channels");
    private static final Set<String> DISCORD_KEYS = Set.of("type", "webhookUrl", "username", "mention");
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

    /** An email channel; delivery comes with M15. */
    public record Email() implements Channel {
        @Override
        public String type() {
            return "email";
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

    /** One notifier per channel, in configuration order. */
    public Map<String, Notifier> notifiers(HttpClient client) {
        Map<String, Notifier> out = new LinkedHashMap<>();
        channels.forEach((name, channel) -> out.put(name, switch (channel) {
            case Discord d -> new DiscordNotifier(d, client);
            case Email ignored -> batch -> {
                throw NotifierException.permanent("email delivery isn't available yet");
            };
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
                warnings.add(path + ": email channels aren't supported yet; messages to this channel fail");
                return new Email();
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
        URI url = webhookUrl(o.get("webhookUrl"), path, errors);
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

    static boolean mentionValid(String mention) {
        for (String token : mention.strip().split("\\s+")) {
            if (!MENTION.matcher(token).matches()) return false;
        }
        return true;
    }

    private static URI webhookUrl(JsonElement element, String path, List<String> errors) {
        if (!(element instanceof JsonPrimitive p && p.isString()) || p.getAsString().isEmpty()) {
            errors.add(path + ": webhookUrl is required");
            return null;
        }
        try {
            URI uri = new URI(p.getAsString());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if (!uri.isAbsolute() || host == null || host.isEmpty()
                    || !(scheme.equals("https") || scheme.equals("http"))) {
                errors.add(path + ": webhookUrl must be an absolute https URL");
                return null;
            }
            if (scheme.equals("http") && !AuthProviders.isLoopback(host)) {
                errors.add(path + ": webhookUrl must use https (http is only allowed for localhost)");
                return null;
            }
            if (uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
                errors.add(path + ": webhookUrl must not contain credentials or a fragment");
                return null;
            }
            return uri;
        } catch (URISyntaxException e) {
            errors.add(path + ": webhookUrl is not a valid URL");
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
