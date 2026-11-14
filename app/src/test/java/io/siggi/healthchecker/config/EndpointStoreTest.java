package io.siggi.healthchecker.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.healthchecker.check.http.HttpCheckPlugin;
import io.siggi.healthchecker.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndpointStoreTest {
    @TempDir
    Path dataDir;

    private PluginManager plugins;

    @BeforeEach
    void loadPlugins() throws Exception {
        plugins = PluginManager.load(List.of(new HttpCheckPlugin()), null, getClass().getClassLoader());
    }

    @AfterEach
    void closePlugins() {
        plugins.close();
    }

    private Config config(String categories, String endpoints) throws ConfigLoadException {
        String json = """
                { "dataDir": %s,
                  "defaults": { "intervalSeconds": 45, "visibility": "public-status" },
                  "notifications": { "channels": { "discord": {} } },
                  "categories": [%s],
                  "endpoints": [%s] }
                """.formatted(new com.google.gson.Gson().toJson(dataDir.toString()), categories, endpoints);
        return new ConfigLoader(name -> null, ZoneId.of("UTC")).load(json);
    }

    private Config defaultConfig() throws ConfigLoadException {
        return config("""
                { "id": "web", "name": "Websites" }""", """
                { "id": "website", "name": "Website", "type": "http", "category": "web",
                  "url": "https://example.invalid", "maxClass": 2 }""");
    }

    private void writeFile(String json) throws Exception {
        Files.writeString(dataDir.resolve(EndpointStore.FILE_NAME), json);
    }

    private EndpointStore load(Config config) throws Exception {
        return EndpointStore.load(config, plugins::validate);
    }

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void missingFileMeansConfigOnlyAtVersionZero() throws Exception {
        EndpointStore store = load(defaultConfig());
        assertEquals(0, store.version());
        assertEquals(List.of("website"), store.endpoints().stream().map(EndpointStore.StoredEndpoint::id).toList());
        assertEquals(EndpointStore.Source.CONFIG, store.endpoints().getFirst().source());
        assertEquals(List.of("web"), store.categoryConfigs().stream().map(CategoryConfig::id).toList());
        assertTrue(store.warnings().isEmpty());
    }

    @Test
    void mergesConfigAndDashboardEntries() throws Exception {
        writeFile("""
                { "version": 4,
                  "categories": [ { "id": "games", "name": "Games", "description": "Fun" } ],
                  "endpoints": [
                    { "id": "shop", "name": "Shop", "type": "http", "category": "web",
                      "url": "https://shop.example.invalid", "maxClass": 3, "notify": ["discord"] },
                    { "id": "arcade", "name": "Arcade", "type": "http", "category": "games",
                      "url": "http://arcade.example.invalid", "maxClass": 4, "visibility": "public" } ] }
                """);
        EndpointStore store = load(defaultConfig());
        assertEquals(4, store.version());
        assertEquals(List.of("website", "shop", "arcade"),
                store.endpoints().stream().map(EndpointStore.StoredEndpoint::id).toList());
        assertEquals(List.of(EndpointStore.Source.CONFIG, EndpointStore.Source.DASHBOARD, EndpointStore.Source.DASHBOARD),
                store.endpoints().stream().map(EndpointStore.StoredEndpoint::source).toList());
        assertEquals(List.of("website", "shop", "arcade"), store.definitions().stream().map(EndpointDefinition::id).toList());
        assertEquals(List.of(new CategoryConfig("web", "Websites", null), new CategoryConfig("games", "Games", "Fun")),
                store.categoryConfigs());
        assertEquals(EndpointStore.Source.DASHBOARD, store.categories().get(1).source());

        // Defaults apply to dashboard entries exactly as to config ones.
        EndpointDefinition shop = store.endpoint("shop").orElseThrow().definition();
        assertEquals(45, shop.intervalSeconds());
        assertEquals(Visibility.PUBLIC_STATUS, shop.visibility());
        assertEquals(List.of("discord"), shop.notifyChannels());
        assertEquals("https://shop.example.invalid", shop.settings().get("url").getAsString());
        EndpointDefinition arcade = store.endpoint("arcade").orElseThrow().definition();
        assertEquals("games", arcade.category());
        assertEquals(Visibility.PUBLIC, arcade.visibility());
        assertNull(store.endpoint("website").orElseThrow().entry());
        assertTrue(store.warnings().isEmpty());
    }

    @Test
    void endpointIdInBothSourcesIsAStartupError() throws Exception {
        writeFile("""
                { "version": 1, "endpoints": [
                  { "id": "website", "name": "Other website", "type": "http",
                    "url": "https://other.example.invalid", "maxClass": 2 } ] }
                """);
        ConfigLoadException e = assertThrows(ConfigLoadException.class, () -> load(defaultConfig()));
        assertEquals(1, e.errors().size());
        String error = e.errors().getFirst();
        assertTrue(error.contains("endpoints[0] \"website\""), error);
        assertTrue(error.contains("\"website\" is defined in both config.json and"), error);
        assertTrue(error.contains(EndpointStore.FILE_NAME), error);
    }

    @Test
    void duplicateIdsInTheFileAndCategoryClashesAreStartupErrors() throws Exception {
        writeFile("""
                { "version": 1,
                  "categories": [ { "id": "web", "name": "Web again" } ],
                  "endpoints": [
                    { "id": "a", "name": "A", "type": "http", "url": "https://a.example.invalid", "maxClass": 2 },
                    { "id": "a", "name": "A2", "type": "http", "url": "https://b.example.invalid", "maxClass": 2 },
                    { "name": "No id", "type": "http" } ] }
                """);
        ConfigLoadException e = assertThrows(ConfigLoadException.class, () -> load(defaultConfig()));
        assertEquals(3, e.errors().size(), e.errors().toString());
        assertTrue(e.errors().get(0).contains("categories[0]: category id \"web\" is defined in both"), e.errors().get(0));
        assertTrue(e.errors().get(1).contains("endpoints[1] \"a\": duplicate endpoint id \"a\""), e.errors().get(1));
        assertTrue(e.errors().get(2).contains("endpoints[2]: id: must be a string"), e.errors().get(2));
    }

    @Test
    void malformedFileIsAnErrorNamingIt() throws Exception {
        writeFile("{ \"version\": 1, \"endpoints\": [ ");
        ConfigLoadException e = assertThrows(ConfigLoadException.class, () -> load(defaultConfig()));
        assertTrue(e.errors().getFirst().contains(EndpointStore.FILE_NAME), e.errors().toString());

        writeFile("{ \"version\": -1, \"extra\": true }");
        e = assertThrows(ConfigLoadException.class, () -> load(defaultConfig()));
        assertEquals(2, e.errors().size(), e.errors().toString());
    }

    @Test
    void entryWhosePluginIsMissingIsLoadedDisabled() throws Exception {
        writeFile("""
                { "version": 2, "endpoints": [
                  { "id": "survival", "name": "Survival", "type": "minecraft", "host": "mc.example.invalid" },
                  { "id": "shop", "name": "Shop", "type": "http", "url": "https://shop.example.invalid", "maxClass": 2 },
                  { "id": "typo", "name": "Typo", "type": "http", "url": "https://t.example.invalid", "maxClass": 9 } ] }
                """);
        EndpointStore store = load(defaultConfig());
        EndpointStore.StoredEndpoint survival = store.endpoint("survival").orElseThrow();
        assertFalse(survival.enabled());
        assertNull(survival.definition());
        assertEquals(List.of("type: unknown type \"minecraft\" (available: http)"), survival.errors());
        assertEquals("mc.example.invalid", survival.entry().get("host").getAsString());
        assertFalse(store.endpoint("typo").orElseThrow().enabled());
        assertTrue(store.endpoint("shop").orElseThrow().enabled());

        // Disabled entries aren't checked, but stay listed (and in the file).
        assertEquals(List.of("website", "shop"), store.definitions().stream().map(EndpointDefinition::id).toList());
        assertEquals(List.of("website", "survival", "shop", "typo"),
                store.endpoints().stream().map(EndpointStore.StoredEndpoint::id).toList());
        assertEquals(2, store.warnings().size());
        assertTrue(store.warnings().getFirst().contains("endpoints[0] \"survival\": disabled: type: unknown type"),
                store.warnings().getFirst());

        // An unrelated write keeps the disabled entry in the file.
        store.putEndpoint(2, json("""
                { "id": "blog", "name": "Blog", "type": "http", "url": "https://blog.example.invalid", "maxClass": 2 }"""));
        EndpointStore reloaded = load(defaultConfig());
        assertEquals(List.of("website", "survival", "shop", "typo", "blog"),
                reloaded.endpoints().stream().map(EndpointStore.StoredEndpoint::id).toList());
        assertFalse(reloaded.endpoint("survival").orElseThrow().enabled());
    }

    @Test
    void dashboardEndpointWithUnknownCategoryOrDuplicateNameIsDisabled() throws Exception {
        writeFile("""
                { "version": 1, "endpoints": [
                  { "id": "a", "name": "Website", "type": "http", "url": "https://a.example.invalid", "maxClass": 2 },
                  { "id": "b", "name": "B", "type": "http", "category": "gone",
                    "url": "https://b.example.invalid", "maxClass": 2 } ] }
                """);
        EndpointStore store = load(defaultConfig());
        assertTrue(store.endpoint("a").orElseThrow().errors().getFirst().contains("duplicate endpoint name \"Website\""));
        assertTrue(store.endpoint("b").orElseThrow().errors().getFirst().startsWith("category: unknown category \"gone\""));
        assertEquals(List.of("website"), store.definitions().stream().map(EndpointDefinition::id).toList());
    }

    @Test
    void writesAreVersionedAndAStaleVersionConflicts() throws Exception {
        EndpointStore store = load(defaultConfig());
        EndpointStore.StoredEndpoint created = store.putEndpoint(0, json("""
                { "id": "shop", "name": "Shop", "type": "http", "url": "https://shop.example.invalid", "maxClass": 2 }"""));
        assertTrue(created.enabled());
        assertEquals(1, store.version());

        // A second editor still on version 0 gets a conflict (HTTP 409), and nothing changes.
        EndpointStore.VersionConflictException conflict = assertThrows(EndpointStore.VersionConflictException.class,
                () -> store.putEndpoint(0, json("""
                        { "id": "shop", "name": "Shop 2", "type": "http",
                          "url": "https://shop.example.invalid", "maxClass": 3 }""")));
        assertEquals(1, conflict.currentVersion());
        assertEquals("Shop", store.endpoint("shop").orElseThrow().definition().name());
        assertThrows(EndpointStore.VersionConflictException.class, () -> store.removeEndpoint(0, "shop"));
        assertThrows(EndpointStore.VersionConflictException.class,
                () -> store.putCategory(2, json("{\"id\": \"x\", \"name\": \"X\"}")));
        assertEquals(1, store.version());

        // Based on the current version, it works; the version is on disk too.
        store.putEndpoint(1, json("""
                { "id": "shop", "name": "Shop 2", "type": "http", "url": "https://shop.example.invalid", "maxClass": 3 }"""));
        assertEquals(2, store.version());
        EndpointStore reloaded = load(defaultConfig());
        assertEquals(2, reloaded.version());
        assertEquals("Shop 2", reloaded.endpoint("shop").orElseThrow().definition().name());
        assertEquals(3, reloaded.endpoint("shop").orElseThrow().definition().settings().get("maxClass").getAsInt());
    }

    @Test
    void writesAreAtomicAndLeftoverTempFilesAreRemoved() throws Exception {
        EndpointStore store = load(defaultConfig());
        store.putCategory(0, json("{\"id\": \"games\", \"name\": \"Games\"}"));
        Path leftover = dataDir.resolve(".endpoints.json.1234.tmp");
        Files.writeString(leftover, "{ partial");
        try (var files = Files.list(dataDir)) {
            assertEquals(2, files.count());
        }
        EndpointStore reloaded = load(defaultConfig());
        assertFalse(Files.exists(leftover));
        assertEquals(List.of("web", "games"), reloaded.categoryConfigs().stream().map(CategoryConfig::id).toList());
    }

    @Test
    void invalidWritesAreRejectedWithoutSaving() throws Exception {
        EndpointStore store = load(defaultConfig());
        EndpointStore.InvalidEntryException e = assertThrows(EndpointStore.InvalidEntryException.class,
                () -> store.putEndpoint(0, json("""
                        { "id": "shop", "name": "Shop", "type": "http", "url": "https://shop.example.invalid/x",
                          "maxClass": 2 }""")));
        assertTrue(e.errors().getFirst().startsWith("url: "), e.errors().toString());

        e = assertThrows(EndpointStore.InvalidEntryException.class, () -> store.putEndpoint(0, json("""
                { "id": "shop", "name": "Website", "type": "http", "intervalSeconds": 0,
                  "url": "https://shop.example.invalid", "maxClass": 2 }""")));
        assertEquals(2, e.errors().size(), e.errors().toString());

        // Config-defined entries are read-only.
        e = assertThrows(EndpointStore.InvalidEntryException.class, () -> store.putEndpoint(0, json("""
                { "id": "website", "name": "Website", "type": "http", "url": "https://x.example.invalid", "maxClass": 2 }""")));
        assertTrue(e.errors().getFirst().contains("defined in config.json"));
        assertThrows(EndpointStore.InvalidEntryException.class, () -> store.removeEndpoint(0, "website"));
        assertThrows(EndpointStore.InvalidEntryException.class,
                () -> store.putCategory(0, json("{\"id\": \"web\", \"name\": \"Web\"}")));
        assertThrows(EndpointStore.InvalidEntryException.class, () -> store.removeCategory(0, "web"));

        assertEquals(0, store.version());
        assertFalse(Files.exists(store.file()));
    }

    @Test
    void removingACategoryMovesItsDashboardEndpointsToOther() throws Exception {
        EndpointStore store = load(defaultConfig());
        store.putCategory(0, json("{\"id\": \"games\", \"name\": \"Games\"}"));
        store.putEndpoint(1, json("""
                { "id": "arcade", "name": "Arcade", "type": "http", "category": "games",
                  "url": "https://arcade.example.invalid", "maxClass": 2 }"""));
        store.removeCategory(2, "games");
        assertNull(store.endpoint("arcade").orElseThrow().definition().category());
        assertFalse(store.endpoint("arcade").orElseThrow().entry().has("category"));
        EndpointStore reloaded = load(defaultConfig());
        assertTrue(reloaded.endpoint("arcade").orElseThrow().enabled());
        assertEquals(List.of("web"), reloaded.categoryConfigs().stream().map(CategoryConfig::id).toList());

        store.removeEndpoint(3, "arcade");
        assertTrue(load(defaultConfig()).endpoint("arcade").isEmpty());
    }

    // ---- "Copy to dashboard" ----

    private Config configWithEnv(String categories, String endpoints) throws ConfigLoadException {
        String json = """
                { "dataDir": %s,
                  "defaults": { "intervalSeconds": 45, "visibility": "public-status" },
                  "notifications": { "channels": { "discord": {} } },
                  "categories": [%s],
                  "endpoints": [%s] }
                """.formatted(new com.google.gson.Gson().toJson(dataDir.toString()), categories, endpoints);
        return new ConfigLoader(name -> name.equals("SITE_HOST") ? "example.invalid" : null, ZoneId.of("UTC")).load(json);
    }

    private static final String WEB = """
            { "id": "web", "name": "Websites", "description": "Our sites" }""";
    private static final String WEBSITE = """
            { "id": "website", "name": "Website", "type": "http", "category": "web",
              "url": "https://${env:SITE_HOST}/", "maxClass": 2, "notify": ["discord"], "timeoutMillis": 900 }""";
    private static final String BLOG = """
            { "id": "blog", "name": "Blog", "type": "http", "url": "https://blog.example.invalid", "maxClass": 2 }""";

    @Test
    void copiedEndpointIsAnEquivalentEntryThatTakesOverAfterRemovalAndRestart() throws Exception {
        Config config = configWithEnv(WEB, WEBSITE + "," + BLOG);
        EndpointDefinition original = config.endpoints().getFirst();
        assertTrue(config.endpointEntries().get("website").usesPlaceholders());
        assertEquals("https://${env:SITE_HOST}/",
                config.endpointEntries().get("website").written().get("url").getAsString());
        EndpointStore store = load(config);

        JsonObject copy = store.copyEndpoint(0, "website");
        // The entry as written, with placeholders substituted (the file isn't substituted); nothing filled in.
        assertEquals(json("""
                { "id": "website", "name": "Website", "type": "http", "category": "web",
                  "url": "https://example.invalid/", "maxClass": 2, "notify": ["discord"], "timeoutMillis": 900 }"""), copy);
        assertEquals(1, store.version());
        assertEquals(copy, store.endpointCopy("website").orElseThrow());
        // Nothing changes until the restart: still the config endpoint, not listed twice.
        assertEquals(List.of("website", "blog"), store.definitions().stream().map(EndpointDefinition::id).toList());
        assertEquals(EndpointStore.Source.CONFIG, store.endpoint("website").orElseThrow().source());
        assertThrows(EndpointStore.InvalidEntryException.class, () -> store.copyEndpoint(1, "website"));
        // Other writes keep the copy.
        store.putCategory(1, json("{\"id\": \"games\", \"name\": \"Games\"}"));
        assertTrue(store.endpointCopy("website").isPresent());

        // Restarting without removing it from config.json is the "defined in both" error.
        ConfigLoadException e = assertThrows(ConfigLoadException.class, () -> load(config));
        assertTrue(e.getMessage().contains("endpoint id \"website\" is defined in both"), e.getMessage());

        // After removing it, the copy takes over the same id with exactly the same settings.
        EndpointStore after = load(configWithEnv(WEB, BLOG));
        EndpointStore.StoredEndpoint website = after.endpoint("website").orElseThrow();
        assertEquals(EndpointStore.Source.DASHBOARD, website.source());
        assertEquals(original, website.definition());
        assertEquals(List.of("blog", "website"), after.definitions().stream().map(EndpointDefinition::id).toList());
        assertTrue(after.endpointCopy("website").isEmpty());
    }

    @Test
    void copyCanBeCancelledAndOnlyConfigEntriesCanBeCopied() throws Exception {
        Config config = configWithEnv(WEB, WEBSITE);
        EndpointStore store = load(config);
        store.putEndpoint(0, json("""
                { "id": "shop", "name": "Shop", "type": "http", "url": "https://shop.example.invalid", "maxClass": 2 }"""));
        assertThrows(EndpointStore.InvalidEntryException.class, () -> store.copyEndpoint(1, "shop"));
        assertThrows(EndpointStore.InvalidEntryException.class, () -> store.copyEndpoint(1, "nope"));
        assertThrows(EndpointStore.InvalidEntryException.class, () -> store.cancelEndpointCopy(1, "website"));
        assertThrows(EndpointStore.VersionConflictException.class, () -> store.copyEndpoint(0, "website"));
        store.copyEndpoint(1, "website");
        store.cancelEndpointCopy(2, "website");
        assertTrue(store.endpointCopy("website").isEmpty());
        // The restart works again, and the file has no trace of the copy.
        EndpointStore reloaded = load(config);
        assertEquals(EndpointStore.Source.CONFIG, reloaded.endpoint("website").orElseThrow().source());
        assertFalse(Files.readString(store.file()).contains("\"website\""));
    }

    @Test
    void copiedCategoryIsEquivalentAndTakesOverAfterRemovalAndRestart() throws Exception {
        Config config = configWithEnv(WEB, BLOG);
        EndpointStore store = load(config);
        CategoryConfig copy = store.copyCategory(0, "web");
        assertEquals(new CategoryConfig("web", "Websites", "Our sites"), copy);
        assertEquals(copy, store.categoryCopy("web").orElseThrow());
        assertEquals(List.of(EndpointStore.Source.CONFIG),
                store.categories().stream().map(EndpointStore.StoredCategory::source).toList());
        assertThrows(EndpointStore.InvalidEntryException.class, () -> store.copyCategory(1, "web"));
        // Still read-only until the restart.
        assertThrows(EndpointStore.InvalidEntryException.class,
                () -> store.putCategory(1, json("{\"id\": \"web\", \"name\": \"Renamed\"}")));
        assertThrows(EndpointStore.InvalidEntryException.class, () -> store.removeCategory(1, "web"));

        ConfigLoadException e = assertThrows(ConfigLoadException.class, () -> load(config));
        assertTrue(e.getMessage().contains("category id \"web\" is defined in both"), e.getMessage());

        EndpointStore after = load(configWithEnv("", BLOG));
        assertEquals(List.of(new EndpointStore.StoredCategory(copy, EndpointStore.Source.DASHBOARD)), after.categories());
        // Now it's a dashboard category: it can be renamed.
        after.putCategory(1, json("{\"id\": \"web\", \"name\": \"Renamed\"}"));
        assertEquals("Renamed", after.categoryConfigs().getFirst().name());
    }
}
