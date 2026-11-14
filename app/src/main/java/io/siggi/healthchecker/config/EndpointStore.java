package io.siggi.healthchecker.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import io.siggi.healthchecker.api.ConfigException;
import io.siggi.healthchecker.history.AtomicFiles;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Endpoints and categories from both sources: {@code config.json} (read-only) and
 * {@code {dataDir}/endpoints.json} (created and edited in the dashboard).
 *
 * <p>File format:
 * <pre>{@code
 * { "version": 3,
 *   "categories": [ { "id": "games", "name": "Games", "description": "…" } ],
 *   "endpoints": [ { "id": "survival", "name": "Survival", "type": "minecraft", "host": "…" } ] }
 * }</pre>
 * Endpoint entries have the same keys as in {@code config.json} and are stored as entered, so
 * {@code defaults} apply to every key they leave out. No {@code ${env:…}} substitution is done.
 *
 * <p>At load, a structural problem in the file (bad JSON, unknown keys, an entry without a valid
 * id, a duplicate id, an invalid category) or an id defined in both sources is an error. A
 * dashboard endpoint that fails validation (e.g. its plugin jar was removed) is kept as a
 * <em>disabled</em> entry with its errors: it isn't checked, but stays in the file.
 *
 * <p>Writes are versioned: each one names the version it's based on, fails with
 * {@link VersionConflictException} if that isn't the current one, and atomically writes the next
 * version. Entries are validated before anything is written.
 */
public final class EndpointStore {
    public static final String FILE_NAME = "endpoints.json";

    private static final Set<String> ROOT_KEYS = Set.of("version", "categories", "endpoints");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Where an endpoint or category is defined. */
    public enum Source { CONFIG, DASHBOARD }

    /** Validates an endpoint against its plugin, e.g. {@code PluginManager::validate}. */
    @FunctionalInterface
    public interface Validator {
        void validate(EndpointDefinition definition) throws ConfigException;
    }

    /**
     * One endpoint of either source.
     *
     * @param definition the parsed endpoint, or {@code null} if it's disabled
     * @param entry      the stored JSON entry (dashboard endpoints only, else {@code null})
     * @param errors     why it's disabled; empty for enabled endpoints
     */
    public record StoredEndpoint(String id, Source source, EndpointDefinition definition, JsonObject entry,
                                 List<String> errors) {
        public StoredEndpoint {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(source, "source");
            entry = entry == null ? null : entry.deepCopy();
            errors = List.copyOf(errors);
        }

        @Override
        public JsonObject entry() {
            return entry == null ? null : entry.deepCopy();
        }

        public boolean enabled() {
            return definition != null;
        }
    }

    /** One category of either source. */
    public record StoredCategory(CategoryConfig category, Source source) {
    }

    /** A write based on a version other than the current one (HTTP 409). */
    public static final class VersionConflictException extends Exception {
        private final int currentVersion;

        VersionConflictException(int currentVersion) {
            super("the endpoints were changed (now version " + currentVersion + ")");
            this.currentVersion = currentVersion;
        }

        public int currentVersion() {
            return currentVersion;
        }
    }

    /** A write rejected because the entry is invalid; nothing was written. */
    public static final class InvalidEntryException extends Exception {
        private final List<String> errors;

        InvalidEntryException(List<String> errors) {
            super(String.join("; ", errors));
            this.errors = List.copyOf(errors);
        }

        /** Each error, prefixed with the setting key it's about where there is one ({@code "key: …"}). */
        public List<String> errors() {
            return errors;
        }
    }

    /** An immutable snapshot of the dashboard-defined part. */
    private record State(int version, List<CategoryConfig> categories, List<StoredEndpoint> endpoints) {
    }

    private final Path file;
    private final Config config;
    private final Validator validator;
    private final List<String> warnings;
    private volatile State state;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile Predicate<String> hasHistory = id -> false;

    private EndpointStore(Path file, Config config, Validator validator, State state, List<String> warnings) {
        this.file = file;
        this.config = config;
        this.validator = validator;
        this.state = state;
        this.warnings = List.copyOf(warnings);
    }

    /**
     * Loads {@code {dataDir}/endpoints.json} (a missing file means no dashboard entries, version 0)
     * and merges it with the config. Leftover temp files of interrupted writes are removed.
     *
     * @throws IOException         if the file can't be read
     * @throws ConfigLoadException with every error, each naming the file; disabled entries are warnings
     */
    public static EndpointStore load(Config config, Validator validator) throws IOException, ConfigLoadException {
        Path file = config.dataDir().resolve(FILE_NAME);
        deleteLeftoverTempFiles(file);
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return new EndpointStore(file, config, validator, new State(0, List.of(), List.of()), List.of());
        }
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        State state = decode(json, file, config, validator, errors, warnings);
        if (!errors.isEmpty()) {
            throw new ConfigLoadException(errors, warnings);
        }
        return new EndpointStore(file, config, validator, state, warnings);
    }

    private static void deleteLeftoverTempFiles(Path file) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "." + FILE_NAME + ".*")) {
            for (Path temp : files) {
                if (AtomicFiles.isTempFile(temp) && Files.isRegularFile(temp)) {
                    Files.deleteIfExists(temp);
                }
            }
        }
    }

    public Path file() {
        return file;
    }

    /** The current version of the dashboard-defined entries; 0 before the first write. */
    public int version() {
        return state.version();
    }

    /** Problems found at load that didn't prevent it (disabled endpoints). */
    public List<String> warnings() {
        return warnings;
    }

    /** Every endpoint: config-defined first (in config order), then dashboard-defined (in file order). */
    public List<StoredEndpoint> endpoints() {
        List<StoredEndpoint> out = new ArrayList<>();
        for (EndpointDefinition d : config.endpoints()) {
            out.add(new StoredEndpoint(d.id(), Source.CONFIG, d, null, List.of()));
        }
        out.addAll(state.endpoints());
        return out;
    }

    /** The enabled endpoints, in the order of {@link #endpoints()}. */
    public List<EndpointDefinition> definitions() {
        List<EndpointDefinition> out = new ArrayList<>(config.endpoints());
        for (StoredEndpoint e : state.endpoints()) {
            if (e.enabled()) out.add(e.definition());
        }
        return out;
    }

    public Optional<StoredEndpoint> endpoint(String id) {
        return endpoints().stream().filter(e -> e.id().equals(id)).findFirst();
    }

    /** Every category: config-defined first, then dashboard-defined. */
    public List<StoredCategory> categories() {
        List<StoredCategory> out = new ArrayList<>();
        for (CategoryConfig c : config.categories()) out.add(new StoredCategory(c, Source.CONFIG));
        for (CategoryConfig c : state.categories()) out.add(new StoredCategory(c, Source.DASHBOARD));
        return out;
    }

    /** The categories of {@link #categories()} without their source. */
    public List<CategoryConfig> categoryConfigs() {
        List<CategoryConfig> out = new ArrayList<>(config.categories());
        out.addAll(state.categories());
        return out;
    }

    // ---- writes ------------------------------------------------------------------------

    /**
     * Creates or replaces a dashboard endpoint (matched by its {@code id}), validated like a
     * config entry including the plugin's {@code create()}.
     *
     * @return the stored, enabled endpoint
     * @throws InvalidEntryException    if the entry is invalid, its id belongs to a config endpoint, or it's
     *                                  a new endpoint whose id still has history (see {@link #reserveIdsWithHistory})
     * @throws VersionConflictException if {@code expectedVersion} isn't the current version
     * @throws IOException              if writing fails; nothing changes then
     */
    public synchronized StoredEndpoint putEndpoint(int expectedVersion, JsonObject entry)
            throws InvalidEntryException, VersionConflictException, IOException {
        State current = checkVersion(expectedVersion);
        EndpointDefinition definition = check(current, entry);
        String id = definition.id();
        StoredEndpoint stored = new StoredEndpoint(id, Source.DASHBOARD, definition, entry, List.of());
        List<StoredEndpoint> endpoints = new ArrayList<>(current.endpoints());
        int index = indexOf(endpoints, id);
        if (index < 0 && hasHistory.test(id)) {
            throw new InvalidEntryException(List.of("id: \"" + id + "\" still has history from a deleted service; "
                    + "choose another id (it can be reused once that history is deleted)"));
        }
        if (index >= 0) {
            endpoints.set(index, stored);
        } else {
            endpoints.add(stored);
        }
        write(new State(current.version() + 1, current.categories(), endpoints));
        return stored;
    }

    /**
     * Blocks reusing ids that still have history: {@link #putEndpoint} refuses to create a new
     * endpoint whose id {@code hasHistory} accepts (history of a deleted or no longer configured
     * endpoint), so two services' histories never mix. Existing endpoints aren't affected.
     */
    public void reserveIdsWithHistory(Predicate<String> hasHistory) {
        this.hasHistory = Objects.requireNonNull(hasHistory, "hasHistory");
    }

    /**
     * Validates a dashboard endpoint entry exactly as {@link #putEndpoint} would, without writing
     * anything (for "Test now").
     *
     * @return the parsed endpoint
     * @throws InvalidEntryException if the entry is invalid or its id belongs to a config endpoint
     */
    public EndpointDefinition check(JsonObject entry) throws InvalidEntryException {
        return check(state, entry);
    }

    private EndpointDefinition check(State current, JsonObject entry) throws InvalidEntryException {
        JsonElement idElement = entry.get("id");
        String id = idElement instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
        if (id != null && configEndpointIds().contains(id)) {
            throw new InvalidEntryException(List.of("id: \"" + id + "\" is defined in config.json and can't be edited here"));
        }
        Map<String, String> names = namesInUse(current, id);
        ConfigLoader.EntryResult<EndpointDefinition> parsed = ConfigLoader.parseEndpoint(entry, "",
                config.defaults(), categoryIds(current.categories()), config.notificationChannels(), names);
        if (parsed.value() == null) {
            throw new InvalidEntryException(parsed.errors());
        }
        try {
            validator.validate(parsed.value());
        } catch (ConfigException e) {
            throw new InvalidEntryException(List.of(describe(e)));
        } catch (RuntimeException | LinkageError e) {
            throw new InvalidEntryException(List.of("validation failed: " + e));
        }
        return parsed.value();
    }

    /**
     * Removes a dashboard endpoint (enabled or disabled).
     *
     * @throws InvalidEntryException if there's no dashboard endpoint with that id
     */
    public synchronized void removeEndpoint(int expectedVersion, String id)
            throws InvalidEntryException, VersionConflictException, IOException {
        State current = checkVersion(expectedVersion);
        List<StoredEndpoint> endpoints = new ArrayList<>(current.endpoints());
        int index = indexOf(endpoints, id);
        if (index < 0) {
            throw new InvalidEntryException(List.of(notDashboard("endpoint", id, configEndpointIds().contains(id))));
        }
        endpoints.remove(index);
        write(new State(current.version() + 1, current.categories(), endpoints));
    }

    /**
     * Creates or replaces a dashboard category (matched by its {@code id}).
     *
     * @throws InvalidEntryException if the entry is invalid or its id belongs to a config category
     */
    public synchronized CategoryConfig putCategory(int expectedVersion, JsonObject entry)
            throws InvalidEntryException, VersionConflictException, IOException {
        State current = checkVersion(expectedVersion);
        ConfigLoader.EntryResult<CategoryConfig> parsed = ConfigLoader.parseCategory(entry, "");
        if (parsed.value() == null) {
            throw new InvalidEntryException(parsed.errors());
        }
        CategoryConfig category = parsed.value();
        if (categoryIds(List.of()).contains(category.id())) {
            throw new InvalidEntryException(List.of("id: \"" + category.id()
                    + "\" is defined in config.json and can't be edited here"));
        }
        List<CategoryConfig> categories = new ArrayList<>(current.categories());
        int index = categoryIndex(categories, category.id());
        if (index >= 0) {
            categories.set(index, category);
        } else {
            categories.add(category);
        }
        write(new State(current.version() + 1, categories, current.endpoints()));
        return category;
    }

    /**
     * Removes a dashboard category. Dashboard endpoints in it move to "Other" (their
     * {@code category} is removed), in the same write.
     *
     * @throws InvalidEntryException if there's no dashboard category with that id
     */
    public synchronized void removeCategory(int expectedVersion, String id)
            throws InvalidEntryException, VersionConflictException, IOException {
        State current = checkVersion(expectedVersion);
        List<CategoryConfig> categories = new ArrayList<>(current.categories());
        int index = categoryIndex(categories, id);
        if (index < 0) {
            throw new InvalidEntryException(List.of(notDashboard("category", id, categoryIds(List.of()).contains(id))));
        }
        categories.remove(index);
        Set<String> categoryIds = categoryIds(categories);
        List<StoredEndpoint> endpoints = new ArrayList<>();
        for (StoredEndpoint e : current.endpoints()) {
            JsonObject entry = e.entry();
            JsonElement category = entry.get("category");
            if (!(category instanceof JsonPrimitive p && p.isString() && p.getAsString().equals(id))) {
                endpoints.add(e);
                continue;
            }
            entry.remove("category");
            if (e.enabled()) {
                EndpointDefinition d = e.definition();
                endpoints.add(new StoredEndpoint(e.id(), Source.DASHBOARD, new EndpointDefinition(d.id(), d.name(),
                        d.type(), null, d.visibility(), d.intervalSeconds(), d.timeoutMillis(), d.failureThreshold(),
                        d.notifyChannels(), d.reminders(), d.reminderRepeatMinutes(), d.settings()), entry, List.of()));
            } else {
                endpoints.add(validate(entry, e.id(), categoryIds, namesInUse(current, e.id()), config, validator));
            }
        }
        write(new State(current.version() + 1, categories, endpoints));
    }

    private State checkVersion(int expectedVersion) throws VersionConflictException, IOException {
        State current = state;
        if (expectedVersion != current.version()) {
            throw new VersionConflictException(current.version());
        }
        if (current.version() == Integer.MAX_VALUE) {
            throw new IOException(file + ": version limit reached");
        }
        return current;
    }

    private void write(State next) throws IOException {
        AtomicFiles.write(file, encode(next).getBytes(StandardCharsets.UTF_8));
        state = next;
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                System.getLogger(EndpointStore.class.getName())
                        .log(System.Logger.Level.ERROR, "Endpoint store listener failed", e);
            }
        }
    }

    /**
     * Adds a listener that runs after every successful write, while the store is still locked
     * (so listeners see the writes in order and one at a time), e.g. to apply the change to the
     * running checks. It reads the new state through {@link #definitions()} and the like.
     */
    public void addChangeListener(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    private static String notDashboard(String what, String id, boolean inConfig) {
        return "id: " + (inConfig
                ? "\"" + id + "\" is defined in config.json and can't be edited here"
                : "no dashboard " + what + " \"" + id + "\"");
    }

    private Set<String> configEndpointIds() {
        Set<String> ids = new HashSet<>();
        for (EndpointDefinition d : config.endpoints()) ids.add(d.id());
        return ids;
    }

    /** Config category ids plus {@code dashboard}'s. */
    private Set<String> categoryIds(List<CategoryConfig> dashboard) {
        return categoryIds(config, dashboard);
    }

    private static Set<String> categoryIds(Config config, List<CategoryConfig> dashboard) {
        Set<String> ids = new LinkedHashSet<>();
        for (CategoryConfig c : config.categories()) ids.add(c.id());
        for (CategoryConfig c : dashboard) ids.add(c.id());
        return ids;
    }

    /** Names of every endpoint except dashboard endpoint {@code exceptId}, → who uses them. */
    private Map<String, String> namesInUse(State current, String exceptId) {
        Map<String, String> names = configNames(config);
        for (StoredEndpoint e : current.endpoints()) {
            if (e.id().equals(exceptId)) continue;
            String name = nameOf(e.entry);
            if (name != null) names.putIfAbsent(name, "dashboard endpoint \"" + e.id() + "\"");
        }
        return names;
    }

    private static Map<String, String> configNames(Config config) {
        Map<String, String> names = new HashMap<>();
        for (EndpointDefinition d : config.endpoints()) {
            names.putIfAbsent(d.name(), "config.json endpoint \"" + d.id() + "\"");
        }
        return names;
    }

    private static String nameOf(JsonObject entry) {
        return entry.get("name") instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }

    private static int indexOf(List<StoredEndpoint> endpoints, String id) {
        for (int i = 0; i < endpoints.size(); i++) {
            if (endpoints.get(i).id().equals(id)) return i;
        }
        return -1;
    }

    private static int categoryIndex(List<CategoryConfig> categories, String id) {
        for (int i = 0; i < categories.size(); i++) {
            if (categories.get(i).id().equals(id)) return i;
        }
        return -1;
    }

    private static String describe(ConfigException e) {
        String key = e.settingKey().orElse(null);
        String message = e.getMessage();
        return key == null || message.startsWith(key + ":") ? message : key + ": " + message;
    }

    /** Parses and validates one dashboard endpoint; invalid ones come back disabled. */
    private static StoredEndpoint validate(JsonObject entry, String id, Set<String> categoryIds,
                                           Map<String, String> names, Config config, Validator validator) {
        ConfigLoader.EntryResult<EndpointDefinition> parsed = ConfigLoader.parseEndpoint(entry, "",
                config.defaults(), categoryIds, config.notificationChannels(), names);
        if (parsed.value() == null) {
            return new StoredEndpoint(id, Source.DASHBOARD, null, entry, parsed.errors());
        }
        try {
            validator.validate(parsed.value());
        } catch (ConfigException e) {
            return new StoredEndpoint(id, Source.DASHBOARD, null, entry, List.of(describe(e)));
        } catch (RuntimeException | LinkageError e) {
            return new StoredEndpoint(id, Source.DASHBOARD, null, entry, List.of("validation failed: " + e));
        }
        return new StoredEndpoint(id, Source.DASHBOARD, parsed.value(), entry, List.of());
    }

    // ---- file format -------------------------------------------------------------------

    static String encode(State state) {
        JsonObject root = new JsonObject();
        root.addProperty("version", state.version());
        JsonArray categories = new JsonArray();
        for (CategoryConfig c : state.categories()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", c.id());
            o.addProperty("name", c.name());
            if (c.description() != null) o.addProperty("description", c.description());
            categories.add(o);
        }
        root.add("categories", categories);
        JsonArray endpoints = new JsonArray();
        for (StoredEndpoint e : state.endpoints()) endpoints.add(e.entry());
        root.add("endpoints", endpoints);
        return GSON.toJson(root) + "\n";
    }

    private static State decode(String json, Path file, Config config, Validator validator,
                                List<String> errors, List<String> warnings) {
        JsonElement rootElement;
        try {
            JsonReader reader = new JsonReader(new StringReader(json));
            reader.setStrictness(Strictness.STRICT);
            rootElement = JsonParser.parseReader(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new JsonParseException("unexpected data after the top-level object");
            }
        } catch (JsonParseException | IOException e) {
            errors.add(file + ": invalid JSON");
            return null;
        }
        if (!rootElement.isJsonObject()) {
            errors.add(file + ": must be a JSON object");
            return null;
        }
        JsonObject root = rootElement.getAsJsonObject();
        for (String key : root.keySet()) {
            if (!ROOT_KEYS.contains(key)) errors.add(file + ": unknown key \"" + key + "\"");
        }
        int version = 0;
        if (root.get("version") instanceof JsonPrimitive p && p.isNumber()
                && Section.asInteger(p) instanceof Integer v && v >= 0) {
            version = v;
        } else {
            errors.add(file + ": version: must be a non-negative integer");
        }

        // Categories: no plugin involved, so an invalid one is an error.
        List<CategoryConfig> categories = new ArrayList<>();
        Set<String> configCategoryIds = categoryIds(config, List.of());
        Set<String> seenCategories = new HashSet<>();
        JsonArray categoryArray = array(root, "categories", file, errors);
        for (int i = 0; i < categoryArray.size(); i++) {
            String path = file + ": categories[" + i + "]";
            if (!categoryArray.get(i).isJsonObject()) {
                errors.add(path + ": must be an object");
                continue;
            }
            ConfigLoader.EntryResult<CategoryConfig> parsed =
                    ConfigLoader.parseCategory(categoryArray.get(i).getAsJsonObject(), path);
            errors.addAll(parsed.errors());
            CategoryConfig c = parsed.value();
            if (c == null) continue;
            if (configCategoryIds.contains(c.id())) {
                errors.add(path + ": category id \"" + c.id() + "\" is defined in both config.json and " + file
                        + "; remove one of them");
            } else if (!seenCategories.add(c.id())) {
                errors.add(path + ": duplicate category id \"" + c.id() + "\"");
            } else {
                categories.add(c);
            }
        }
        Set<String> categoryIds = categoryIds(config, categories);

        // Endpoints: the id must be usable; anything else makes the entry disabled.
        Set<String> configIds = new HashSet<>();
        for (EndpointDefinition d : config.endpoints()) configIds.add(d.id());
        Set<String> seenIds = new HashSet<>();
        Map<String, String> names = configNames(config);
        List<StoredEndpoint> endpoints = new ArrayList<>();
        JsonArray endpointArray = array(root, "endpoints", file, errors);
        for (int i = 0; i < endpointArray.size(); i++) {
            String path = file + ": endpoints[" + i + "]";
            if (!endpointArray.get(i).isJsonObject()) {
                errors.add(path + ": must be an object");
                continue;
            }
            JsonObject entry = endpointArray.get(i).getAsJsonObject();
            String id = entry.get("id") instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
            if (id == null || !ConfigLoader.ID.matcher(id).matches()) {
                errors.add(path + ": id: must be a string matching [a-z0-9-]+");
                continue;
            }
            path += " \"" + id + "\"";
            if (configIds.contains(id)) {
                errors.add(path + ": endpoint id \"" + id + "\" is defined in both config.json and " + file
                        + "; remove one of them");
                continue;
            }
            if (!seenIds.add(id)) {
                errors.add(path + ": duplicate endpoint id \"" + id + "\"");
                continue;
            }
            StoredEndpoint stored = validate(entry, id, categoryIds, names, config, validator);
            String name = nameOf(entry);
            if (name != null) names.putIfAbsent(name, "dashboard endpoint \"" + id + "\"");
            if (!stored.enabled()) {
                warnings.add(path + ": disabled: " + String.join("; ", stored.errors()));
            }
            endpoints.add(stored);
        }
        return new State(version, categories, endpoints);
    }

    private static JsonArray array(JsonObject root, String key, Path file, List<String> errors) {
        JsonElement e = root.get(key);
        if (e == null || e.isJsonNull()) return new JsonArray();
        if (!e.isJsonArray()) {
            errors.add(file + ": " + key + ": must be an array");
            return new JsonArray();
        }
        return e.getAsJsonArray();
    }
}
