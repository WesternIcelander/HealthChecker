package io.siggi.healthchecker.web.layout;

import com.google.gson.JsonObject;
import io.siggi.healthchecker.config.CategoryConfig;
import io.siggi.healthchecker.config.EndpointDefinition;
import io.siggi.healthchecker.config.Visibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayoutStoreTest {
    @TempDir
    Path dataDir;

    private static final List<CategoryConfig> CATEGORIES = List.of(
            new CategoryConfig("web", "Websites", null),
            new CategoryConfig("minecraft", "Minecraft servers", "Java Edition"),
            new CategoryConfig("internal", "Internal", null));

    private static EndpointDefinition endpoint(String id, String category) {
        return new EndpointDefinition(id, "Name " + id, "http", category, Visibility.PUBLIC, 60, 5000, 1,
                List.of(), List.of(), 0, new JsonObject());
    }

    private static final List<EndpointDefinition> ENDPOINTS = List.of(
            endpoint("website", "web"),
            endpoint("admin", "internal"),
            endpoint("survival", "minecraft"),
            endpoint("loose", null),
            endpoint("blog", "web"),
            endpoint("creative", "minecraft"));

    /** Category id → endpoint ids, in order. */
    private static Map<String, List<String>> shape(ResolvedLayout layout) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (ResolvedLayout.Category c : layout.categories()) {
            result.put(c.id(), c.endpointIds());
        }
        return result;
    }

    private LayoutStore storeWith(String json) throws IOException {
        Files.writeString(dataDir.resolve("layout.json"), json);
        LayoutStore store = new LayoutStore(dataDir);
        store.load();
        return store;
    }

    @Test
    void defaultOrderingFromConfig() throws IOException {
        LayoutStore store = new LayoutStore(dataDir);
        store.load();
        assertTrue(store.saved().isEmpty());
        assertEquals(0, store.version());
        ResolvedLayout layout = store.resolve(CATEGORIES, ENDPOINTS);
        assertFalse(layout.flat());
        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("web", List.of("website", "blog"));
        expected.put("minecraft", List.of("survival", "creative"));
        expected.put("internal", List.of("admin"));
        expected.put("_other", List.of("loose"));
        assertEquals(expected, shape(layout));
        assertEquals("Minecraft servers", layout.categories().get(1).config().name());
        assertTrue(layout.categories().get(3).isOther());
        assertNull(layout.categories().get(3).config());
    }

    @Test
    void uncategorizedLastByDefaultAndOtherAlwaysPresent() {
        ResolvedLayout layout = ResolvedLayout.resolve(null, CATEGORIES, List.of(endpoint("website", "web")));
        assertEquals(List.of("web", "minecraft", "internal", "_other"), List.copyOf(shape(layout).keySet()));
        assertEquals(List.of(), shape(layout).get("_other"));
    }

    @Test
    void savedLayoutOverridesOrderAndCategory() throws IOException {
        LayoutStore store = storeWith("""
                { "version": 4, "categories": [
                  { "id": "_other",    "services": ["loose", "website"] },
                  { "id": "minecraft", "services": ["creative", "survival", "admin"] },
                  { "id": "web",       "services": ["blog"] },
                  { "id": "internal",  "services": [] }
                ] }""");
        assertEquals(4, store.version());
        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("_other", List.of("loose", "website"));
        expected.put("minecraft", List.of("creative", "survival", "admin"));
        expected.put("web", List.of("blog"));
        expected.put("internal", List.of());
        assertEquals(expected, shape(store.resolve(CATEGORIES, ENDPOINTS)));
    }

    @Test
    void newConfigEntriesAppended() throws IOException {
        LayoutStore store = storeWith("""
                { "version": 1, "categories": [
                  { "id": "minecraft", "services": ["creative", "survival"] },
                  { "id": "web",       "services": ["website"] }
                ] }""");
        // "internal" and "_other" are new to the layout; "blog", "admin" and "loose" too.
        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("minecraft", List.of("creative", "survival"));
        expected.put("web", List.of("website", "blog"));
        expected.put("internal", List.of("admin"));
        expected.put("_other", List.of("loose"));
        assertEquals(expected, shape(store.resolve(CATEGORIES, ENDPOINTS)));
    }

    @Test
    void savedOtherPositionKeptWhenNewCategoriesAppended() throws IOException {
        LayoutStore store = storeWith("""
                { "version": 1, "categories": [
                  { "id": "_other", "services": [] },
                  { "id": "web",    "services": [] }
                ] }""");
        assertEquals(List.of("_other", "web", "minecraft", "internal"),
                List.copyOf(shape(store.resolve(CATEGORIES, ENDPOINTS)).keySet()));
    }

    @Test
    void staleIdsIgnored() throws IOException {
        LayoutStore store = storeWith("""
                { "version": 2, "categories": [
                  { "id": "gone",      "services": ["website"] },
                  { "id": "web",       "services": ["removed", "blog", "blog", "website"] },
                  { "id": "minecraft", "services": ["blog", "survival"] },
                  { "id": "web",       "services": ["creative"] }
                ] }""");
        Map<String, List<String>> expected = new LinkedHashMap<>();
        // "gone" is ignored, so "website" isn't placed by it; repeats count once (first wins).
        expected.put("web", List.of("blog", "website"));
        expected.put("minecraft", List.of("survival", "creative"));
        expected.put("internal", List.of("admin"));
        expected.put("_other", List.of("loose"));
        assertEquals(expected, shape(store.resolve(CATEGORIES, ENDPOINTS)));
    }

    @Test
    void endpointWithUnknownCategoryGoesToOther() {
        ResolvedLayout layout = ResolvedLayout.resolve(null, CATEGORIES, List.of(endpoint("x", "deleted")));
        assertEquals(List.of("x"), shape(layout).get("_other"));
    }

    @Test
    void flatListWhenNoCategories() throws IOException {
        List<EndpointDefinition> endpoints = List.of(endpoint("a", null), endpoint("b", null), endpoint("c", null));
        ResolvedLayout layout = ResolvedLayout.resolve(null, List.of(), endpoints);
        assertTrue(layout.flat());
        assertEquals(Map.of("_other", List.of("a", "b", "c")), shape(layout));

        LayoutStore store = storeWith("""
                { "version": 1, "categories": [
                  { "id": "old",    "services": ["a"] },
                  { "id": "_other", "services": ["c", "b"] }
                ] }""");
        ResolvedLayout saved = store.resolve(List.of(), endpoints);
        assertTrue(saved.flat());
        assertEquals(Map.of("_other", List.of("c", "b", "a")), shape(saved));
    }

    @Test
    void reloadReplacesAndMissingFileClears() throws IOException {
        LayoutStore store = storeWith("{\"version\": 7, \"categories\": []}");
        assertEquals(7, store.version());
        Files.delete(store.file());
        store.load();
        assertTrue(store.saved().isEmpty());
        assertEquals(0, store.version());
    }

    @Test
    void malformedFileRejectedWithFileName() throws IOException {
        for (String json : List.of(
                "not json {",
                "[]",
                "{\"categories\": []}",
                "{\"version\": -1, \"categories\": []}",
                "{\"version\": 1.5, \"categories\": []}",
                "{\"version\": 1}",
                "{\"version\": 1, \"categories\": [], \"extra\": 1}",
                "{\"version\": 1, \"categories\": [{\"id\": 3, \"services\": []}]}",
                "{\"version\": 1, \"categories\": [{\"id\": \"web\", \"services\": [1]}]}",
                "{\"version\": 1, \"categories\": [{\"id\": \"web\", \"services\": [], \"name\": \"x\"}]}")) {
            Files.writeString(dataDir.resolve("layout.json"), json);
            LayoutStore store = new LayoutStore(dataDir);
            IOException e = assertThrows(IOException.class, store::load, json);
            assertTrue(e.getMessage().contains("layout.json"), e.getMessage());
        }
    }

    // ---- saving ----

    @Test
    void saveWritesNextVersionAndReloads() throws Exception {
        LayoutStore store = new LayoutStore(dataDir);
        store.load();
        SavedLayout saved = store.save(0, List.of(
                new SavedLayout.Category("_other", List.of("loose", "website")),
                new SavedLayout.Category("web", List.of("blog"))));
        assertEquals(1, saved.version());
        assertEquals(1, store.version());

        LayoutStore reloaded = new LayoutStore(dataDir);
        reloaded.load();
        assertEquals(1, reloaded.version());
        assertEquals(saved, reloaded.saved().orElseThrow());
        Map<String, List<String>> shape = shape(reloaded.resolve(CATEGORIES, ENDPOINTS));
        assertEquals(List.of("_other", "web", "minecraft", "internal"), List.copyOf(shape.keySet()));
        assertEquals(List.of("loose", "website"), shape.get("_other"));
        assertEquals(List.of("blog"), shape.get("web"));
    }

    @Test
    void staleSaveIsAConflictAndChangesNothing() throws Exception {
        LayoutStore store = new LayoutStore(dataDir);
        store.load();
        store.save(0, List.of(new SavedLayout.Category("web", List.of("blog", "website"))));
        String before = Files.readString(store.file());
        LayoutStore.VersionConflictException e = assertThrows(LayoutStore.VersionConflictException.class,
                () -> store.save(0, List.of()));
        assertEquals(1, e.currentVersion());
        assertEquals(1, store.version());
        assertEquals(before, Files.readString(store.file()));
    }

    @Test
    void resetKeepsCountingAndResolvesToConfigOrder() throws Exception {
        LayoutStore store = new LayoutStore(dataDir);
        store.load();
        Map<String, List<String>> defaults = shape(store.resolve(CATEGORIES, ENDPOINTS));
        store.save(0, List.of(new SavedLayout.Category("_other", List.of("website"))));
        store.save(1, List.of());
        assertEquals(2, store.version());
        assertEquals(defaults, shape(store.resolve(CATEGORIES, ENDPOINTS)));
        assertThrows(LayoutStore.VersionConflictException.class, () -> store.save(0, List.of()));
    }

    @Test
    void leftoverTempFilesRemovedAtLoad() throws Exception {
        Path temp = dataDir.resolve(".layout.json.0123.tmp");
        Files.writeString(temp, "{");
        Path other = dataDir.resolve(".other.json.0123.tmp");
        Files.writeString(other, "{");
        new LayoutStore(dataDir).load();
        assertFalse(Files.exists(temp));
        assertTrue(Files.exists(other));
    }
}
