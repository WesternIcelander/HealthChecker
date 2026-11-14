package io.siggi.healthchecker.audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Who changed what in the dashboard: an append-only JSON Lines file, {@code data/audit.jsonl}, one
 * {@link Entry} per line ({@code time}, {@code user}, {@code userName}, {@code action}, {@code id},
 * {@code before}, {@code after}).
 * <ul>
 *   <li>Secrets are masked before anything is written ({@link #mask}): values of keys that name a
 *       secret (password, secret, token, API key, webhook, …) become {@value #MASK}, and so does the
 *       password part of a URL's user info.</li>
 *   <li>Each entry is appended and forced to disk. A line cut short by a crash is skipped when reading,
 *       and the next entry starts on a new line.</li>
 *   <li>A failed write is logged and doesn't undo the change it describes.</li>
 * </ul>
 */
public final class AuditLog {
    public static final String FILE_NAME = "audit.jsonl";
    /** What a masked value is replaced with. */
    public static final String MASK = "***";

    private static final System.Logger LOG = System.getLogger(AuditLog.class.getName());
    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    /** Key names (lowercase, without {@code -}/{@code _}/{@code .}) that hold a secret. */
    private static final Pattern SECRET_KEY = Pattern.compile(
            ".*(password|passwd|passphrase|secret|token|apikey|accesskey|privatekey|credential|authorization"
                    + "|webhook|cookie).*");
    /** {@code scheme://user:password@} in a URL. */
    private static final Pattern URL_PASSWORD = Pattern.compile("([A-Za-z][A-Za-z0-9+.-]*://[^/?#@:\\s]*):[^/?#@\\s]*@");

    private static final AuditLog DISABLED = new AuditLog(null, Clock.systemUTC());

    private final Path file;
    private final Clock clock;

    /**
     * One audit entry.
     *
     * @param user     the user id, or {@code null} for an anonymous actor (e.g. a rejected sign-in)
     * @param userName the user's display name at the time, or {@code null}
     * @param action   what was done, e.g. {@code endpoint.edit}
     * @param id       what it was done to, e.g. an endpoint id or {@code endpoint/event/note}
     * @param before   the value before (masked), or {@link JsonNull} when there was none
     * @param after    the value after (masked), or {@link JsonNull} when there is none
     */
    public record Entry(Instant time, String user, String userName, String action, String id,
                        JsonElement before, JsonElement after) {
        public Entry {
            Objects.requireNonNull(time, "time");
            Objects.requireNonNull(action, "action");
            before = before == null ? JsonNull.INSTANCE : before.deepCopy();
            after = after == null ? JsonNull.INSTANCE : after.deepCopy();
        }

        @Override
        public JsonElement before() {
            return before.deepCopy();
        }

        @Override
        public JsonElement after() {
            return after.deepCopy();
        }
    }

    /**
     * One page of entries, newest first.
     *
     * @param page    the page number (0 = newest)
     * @param hasNext whether there are older entries
     */
    public record Page(List<Entry> entries, int page, boolean hasNext) {
        public Page {
            entries = List.copyOf(entries);
        }
    }

    public AuditLog(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
    }

    /** The audit log in {@code dataDir}. */
    public static AuditLog open(Path dataDir) {
        return new AuditLog(dataDir.resolve(FILE_NAME), Clock.systemUTC());
    }

    /** An audit log that records nothing (for tests that don't care). */
    public static AuditLog disabled() {
        return DISABLED;
    }

    /** The file, or {@code null} when disabled. */
    public Path file() {
        return file;
    }

    /**
     * Appends an entry with secrets masked. Never throws: a failed write is logged.
     *
     * @param userId   who did it, or {@code null}
     * @param userName their display name, or {@code null}
     * @param before   the value before, or {@code null} for none (e.g. on create)
     * @param after    the value after, or {@code null} for none (e.g. on delete)
     */
    public void record(String userId, String userName, String action, String id, JsonElement before,
                       JsonElement after) {
        if (file == null) return;
        Entry entry = new Entry(clock.instant(), userId, userName, action, id, mask(before), mask(after));
        synchronized (this) {
            try {
                append(encode(entry));
            } catch (IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Writing the audit log entry " + action + " " + id + " to " + file
                        + " failed", e);
            }
        }
    }

    private void append(String line) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            long size = channel.size();
            boolean newline = false;
            if (size > 0) {
                // A line cut short by a crash: start on a new line, so this entry stays readable.
                ByteBuffer last = ByteBuffer.allocate(1);
                channel.read(last, size - 1);
                newline = last.get(0) != '\n';
            }
            ByteBuffer data = ByteBuffer.wrap(((newline ? "\n" : "") + line + "\n").getBytes(StandardCharsets.UTF_8));
            long position = size;
            while (data.hasRemaining()) {
                position += channel.write(data, position);
            }
            channel.force(true);
        }
    }

    /**
     * A page of entries, newest first.
     *
     * @param page     0 for the newest
     * @param pageSize entries per page
     */
    public synchronized Page page(int page, int pageSize) throws IOException {
        if (page < 0 || pageSize < 1) throw new IllegalArgumentException("page " + page + ", size " + pageSize);
        List<Entry> all = readAll();
        long skip = (long) page * pageSize;
        List<Entry> out = new ArrayList<>();
        for (long i = all.size() - 1 - skip; i >= 0 && out.size() < pageSize; i--) {
            out.add(all.get((int) i));
        }
        return new Page(out, page, all.size() - skip - out.size() > 0);
    }

    /** Every readable entry, oldest first. Lines that aren't valid entries (e.g. cut short by a crash) are skipped. */
    public synchronized List<Entry> entries() throws IOException {
        return readAll();
    }

    private List<Entry> readAll() throws IOException {
        if (file == null) return List.of();
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>(lines.size());
        int skipped = 0;
        for (String line : lines) {
            if (line.isBlank()) continue;
            Entry entry = decode(line);
            if (entry == null) {
                skipped++;
            } else {
                entries.add(entry);
            }
        }
        if (skipped > 0) {
            LOG.log(System.Logger.Level.WARNING, "Skipped " + skipped + " unreadable line(s) in " + file);
        }
        return entries;
    }

    static String encode(Entry entry) {
        JsonObject o = new JsonObject();
        o.addProperty("time", entry.time().toString());
        o.add("user", entry.user() == null ? JsonNull.INSTANCE : new JsonPrimitive(entry.user()));
        o.add("userName", entry.userName() == null ? JsonNull.INSTANCE : new JsonPrimitive(entry.userName()));
        o.addProperty("action", entry.action());
        o.add("id", entry.id() == null ? JsonNull.INSTANCE : new JsonPrimitive(entry.id()));
        o.add("before", entry.before());
        o.add("after", entry.after());
        return GSON.toJson(o);
    }

    /** The entry on one line, or {@code null} when it isn't one. */
    static Entry decode(String line) {
        try {
            JsonElement parsed = JsonParser.parseString(line);
            if (!parsed.isJsonObject()) return null;
            JsonObject o = parsed.getAsJsonObject();
            String time = string(o, "time");
            String action = string(o, "action");
            if (time == null || action == null) return null;
            return new Entry(Instant.parse(time), string(o, "user"), string(o, "userName"), action, string(o, "id"),
                    o.get("before"), o.get("after"));
        } catch (JsonParseException | DateTimeParseException | IllegalStateException | UnsupportedOperationException e) {
            return null;
        }
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    // ---- masking ----

    /**
     * A copy with secrets masked: every non-null value under a key that names a secret becomes
     * {@value #MASK} (whole objects and arrays too), and in every other string the password of a URL's
     * user info ({@code https://user:pw@host}) does.
     *
     * @return the masked copy, or {@link JsonNull} for {@code null}
     */
    public static JsonElement mask(JsonElement value) {
        if (value == null || value.isJsonNull()) return JsonNull.INSTANCE;
        if (value.isJsonObject()) {
            JsonObject out = new JsonObject();
            for (Map.Entry<String, JsonElement> e : value.getAsJsonObject().entrySet()) {
                boolean secret = isSecretKey(e.getKey()) && !e.getValue().isJsonNull();
                out.add(e.getKey(), secret ? new JsonPrimitive(MASK) : mask(e.getValue()));
            }
            return out;
        }
        if (value.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement e : value.getAsJsonArray()) out.add(mask(e));
            return out;
        }
        JsonPrimitive p = value.getAsJsonPrimitive();
        if (p.isString()) {
            Matcher m = URL_PASSWORD.matcher(p.getAsString());
            if (m.find()) return new JsonPrimitive(m.replaceAll(r -> Matcher.quoteReplacement(r.group(1) + ":" + MASK + "@")));
        }
        return p.deepCopy();
    }

    /** Whether a key's name says it holds a secret, e.g. {@code password}, {@code clientSecret}, {@code api_key}. */
    public static boolean isSecretKey(String key) {
        String normal = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(".", "");
        return SECRET_KEY.matcher(normal).matches();
    }
}
