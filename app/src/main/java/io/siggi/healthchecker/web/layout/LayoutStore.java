package io.siggi.healthchecker.web.layout;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.siggi.healthchecker.config.CategoryConfig;
import io.siggi.healthchecker.config.EndpointDefinition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The dashboard layout saved by logged-in users, in {@code {dataDir}/layout.json}
 * (read side; saving comes with the layout editor).
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
 */
public final class LayoutStore {
    public static final String FILE_NAME = "layout.json";

    private static final Set<String> ROOT_KEYS = Set.of("version", "categories");
    private static final Set<String> CATEGORY_KEYS = Set.of("id", "services");

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
    public void load() throws IOException {
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
