package io.siggi.healthchecker.web.layout;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.siggi.healthchecker.config.CategoryConfig;
import io.siggi.healthchecker.config.EndpointDefinition;
import io.siggi.healthchecker.history.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The dashboard layout saved by logged-in users, in {@code {dataDir}/layout.json}.
 *
 * <p>File format:
 * <pre>{@code
 * { "version": 3,
 *   "categories": [
 *     { "id": "web", "services": ["website"] },
 *     { "id": "_other", "services": [] } ] }
 * }</pre>
 * {@code "_other"} is the implicit "Other" category; the leading underscore can't clash
 * with a category id. A missing file means no saved layout (version 0).
 *
 * <p>Saves are versioned: {@link #save} only succeeds when based on the current version, and
 * writes the next one atomically. "Reset to config order" saves an empty category list, which
 * resolves exactly like no saved layout but keeps the version counting up, so an editor opened
 * before the reset still gets a conflict.
 */
public final class LayoutStore {
    public static final String FILE_NAME = "layout.json";

    private static final Set<String> ROOT_KEYS = Set.of("version", "categories");
    private static final Set<String> CATEGORY_KEYS = Set.of("id", "services");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;
    private volatile SavedLayout saved;

    public LayoutStore(Path dataDir) {
        this.file = dataDir.resolve(FILE_NAME);
    }

    public Path file() {
        return file;
    }

    /**
     * Reads the saved layout from disk, replacing what was loaded before.
     *
     * @throws IOException if the file can't be read or is malformed (the message names the file)
     */
    public synchronized void load() throws IOException {
        deleteLeftoverTempFiles();
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            saved = null;
            return;
        }
        try {
            saved = decode(json);
        } catch (FormatException e) {
            throw new IOException(file + ": " + e.getMessage());
        }
    }

    /** Removes temp files of this store's file left behind by a crash during a save. */
    private void deleteLeftoverTempFiles() throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (!Files.isDirectory(dir)) return;
        String prefix = "." + FILE_NAME + ".";
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, prefix + "*")) {
            for (Path temp : files) {
                if (AtomicFiles.isTempFile(temp) && Files.isRegularFile(temp)) {
                    Files.deleteIfExists(temp);
                }
            }
        }
    }

    /** The saved layout, or empty if none has been saved. */
    public Optional<SavedLayout> saved() {
        return Optional.ofNullable(saved);
    }

    /** The saved layout's version, 0 if none has been saved. */
    public int version() {
        SavedLayout s = saved;
        return s == null ? 0 : s.version();
    }

    /** The effective layout for the current categories and endpoints (both in config order). */
    public ResolvedLayout resolve(List<CategoryConfig> categories, List<EndpointDefinition> endpoints) {
        return ResolvedLayout.resolve(saved, categories, endpoints);
    }

    /** A save based on a version other than the current one. */
    public static final class VersionConflictException extends Exception {
        private final int currentVersion;

        VersionConflictException(int currentVersion) {
            super("the layout was changed (now version " + currentVersion + ")");
            this.currentVersion = currentVersion;
        }

        public int currentVersion() {
            return currentVersion;
        }
    }

    /**
     * Replaces the saved layout with {@code categories} as the next version, written atomically.
     * An empty list resets to config order.
     *
     * @param expectedVersion the version the edit was based on
     * @return the saved layout
     * @throws VersionConflictException if {@code expectedVersion} isn't the current version
     * @throws IOException if writing fails; the saved layout is then unchanged
     */
    public synchronized SavedLayout save(int expectedVersion, List<SavedLayout.Category> categories)
            throws IOException, VersionConflictException {
        int current = version();
        if (expectedVersion != current) {
            throw new VersionConflictException(current);
        }
        if (current == Integer.MAX_VALUE) {
            throw new IOException(file + ": version limit reached");
        }
        SavedLayout next = new SavedLayout(current + 1, categories);
        AtomicFiles.write(file, encode(next).getBytes(StandardCharsets.UTF_8));
        saved = next;
        return next;
    }

    /**
     * For a deleted category: if the saved layout has it, removes it and appends its services to
     * "Other" (created at the end if the saved layout doesn't have it), as the next version. Without a
     * saved entry for the category nothing is written, since the resolved layout already puts its
     * remaining services elsewhere.
     *
     * @return whether the saved layout changed
     * @throws IOException if writing fails; the saved layout is then unchanged
     */
    public synchronized boolean moveToOther(String categoryId) throws IOException {
        SavedLayout current = saved;
        if (current == null || categoryId.equals(SavedLayout.OTHER_ID)) return false;
        List<SavedLayout.Category> categories = new ArrayList<>(current.categories());
        int index = -1;
        for (int i = 0; i < categories.size(); i++) {
            if (categories.get(i).id().equals(categoryId)) {
                index = i;
                break;
            }
        }
        if (index < 0) return false;
        if (current.version() == Integer.MAX_VALUE) {
            throw new IOException(file + ": version limit reached");
        }
        List<String> moved = categories.remove(index).services();
        int other = -1;
        for (int i = 0; i < categories.size(); i++) {
            if (categories.get(i).id().equals(SavedLayout.OTHER_ID)) other = i;
        }
        if (other < 0) {
            categories.add(new SavedLayout.Category(SavedLayout.OTHER_ID, moved));
        } else {
            List<String> services = new ArrayList<>(categories.get(other).services());
            services.addAll(moved);
            categories.set(other, new SavedLayout.Category(SavedLayout.OTHER_ID, services));
        }
        SavedLayout next = new SavedLayout(current.version() + 1, categories);
        AtomicFiles.write(file, encode(next).getBytes(StandardCharsets.UTF_8));
        saved = next;
        return true;
    }

    static String encode(SavedLayout layout) {
        JsonObject root = new JsonObject();
        root.addProperty("version", layout.version());
        JsonArray categories = new JsonArray();
        for (SavedLayout.Category category : layout.categories()) {
            JsonObject c = new JsonObject();
            c.addProperty("id", category.id());
            JsonArray services = new JsonArray();
            category.services().forEach(services::add);
            c.add("services", services);
            categories.add(c);
        }
        root.add("categories", categories);
        return GSON.toJson(root) + "\n";
    }

    private static final class FormatException extends Exception {
        FormatException(String message) {
            super(message);
        }
    }

    static SavedLayout decode(String json) throws FormatException {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            throw new FormatException("invalid JSON");
        }
        JsonObject o = object(root, "layout");
        checkKeys(o, ROOT_KEYS, "layout");
        int version = nonNegativeInt(o.get("version"), "version");
        JsonArray categoriesJson = array(o.get("categories"), "categories");
        List<SavedLayout.Category> categories = new ArrayList<>();
        for (int i = 0; i < categoriesJson.size(); i++) {
            String where = "categories[" + i + "]";
            JsonObject c = object(categoriesJson.get(i), where);
            checkKeys(c, CATEGORY_KEYS, where);
            String id = string(c.get("id"), where + ".id");
            JsonArray servicesJson = array(c.get("services"), where + ".services");
            List<String> services = new ArrayList<>();
            for (int j = 0; j < servicesJson.size(); j++) {
                services.add(string(servicesJson.get(j), where + ".services[" + j + "]"));
            }
            categories.add(new SavedLayout.Category(id, services));
        }
        return new SavedLayout(version, categories);
    }

    private static void checkKeys(JsonObject o, Set<String> allowed, String where) throws FormatException {
        for (String key : o.keySet()) {
            if (!allowed.contains(key)) {
                throw new FormatException(where + ": unknown key \"" + key + "\"");
            }
        }
    }

    private static JsonObject object(JsonElement e, String where) throws FormatException {
        if (e == null || !e.isJsonObject()) {
            throw new FormatException(where + ": expected an object");
        }
        return e.getAsJsonObject();
    }

    private static JsonArray array(JsonElement e, String where) throws FormatException {
        if (e == null || !e.isJsonArray()) {
            throw new FormatException(where + ": expected an array");
        }
        return e.getAsJsonArray();
    }

    private static String string(JsonElement e, String where) throws FormatException {
        if (e instanceof JsonPrimitive p && p.isString()) {
            return p.getAsString();
        }
        throw new FormatException(where + ": expected a string");
    }

    private static int nonNegativeInt(JsonElement e, String where) throws FormatException {
        if (e instanceof JsonPrimitive p && p.isNumber()) {
            double d = p.getAsDouble();
            if (d >= 0 && d <= Integer.MAX_VALUE && d == Math.rint(d)) {
                return (int) d;
            }
        }
        throw new FormatException(where + ": expected a non-negative integer");
    }
}
