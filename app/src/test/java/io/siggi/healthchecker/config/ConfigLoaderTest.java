package io.siggi.healthchecker.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ConfigLoaderTest {
    private static final ZoneId SYSTEM_ZONE = ZoneId.of("America/New_York");
    private static final Map<String, String> ENV = Map.of(
            "SIMPLEMAILER_KEY", "mail-key",
            "DISCORD_WEBHOOK", "https://discord.invalid/hook/1",
            "MC_DISCORD_WEBHOOK", "https://discord.invalid/hook/2",
            "GOOGLE_CLIENT_SECRET", "g-secret",
            "TITLE_SUFFIX", "Prod");

    private static final String FULL = """
            {
              "server": {
                "port": 9090, "bind": "127.0.0.1",
                "publicBaseUrl": "https://status.example.com/",
                "trustedProxies": ["127.0.0.1"],
                "unknownHosts": "reject"
              },
              "dataDir": "var/data",
              "timezone": "Atlantic/Reykjavik",
              "plugins": { "dir": "my-plugins" },
              "dashboard": {
                "title": "Status ${env:TITLE_SUFFIX}",
                "colors": { "yellowBelow": 100.0, "orangeBelow": 98.5, "redBelow": 90 }
              },
              "maintenance": { "countsAsDowntime": true, "maxLengthDays": 3, "upcomingDays": 14 },
              "defaults": {
                "intervalSeconds": 60, "timeoutMillis": 4000, "failureThreshold": 2,
                "visibility": "public-status",
                "notify": ["ops-email", "discord"],
                "reminders": [10, 20],
                "reminderRepeatMinutes": 240
              },
              "auth": {
                "providers": [
                  { "id": "google", "type": "oidc", "clientSecret": "${env:GOOGLE_CLIENT_SECRET}" }
                ]
              },
              "notifications": {
                "batchSeconds": 30,
                "channels": {
                  "ops-email": { "type": "email", "apiKey": "${env:SIMPLEMAILER_KEY}" },
                  "discord": { "type": "discord", "webhookUrl": "${env:DISCORD_WEBHOOK}" },
                  "mc-discord": { "type": "discord", "webhookUrl": "${env:MC_DISCORD_WEBHOOK}" }
                }
              },
              "sites": [
                { "hosts": ["MC-Status.example.com"], "title": "Minecraft status",
                  "publicBaseUrl": "https://mc-status.example.com", "categories": ["minecraft"] },
                { "hosts": ["*.web.example.com"], "endpoints": ["website"] }
              ],
              "categories": [
                { "id": "web", "name": "Websites" },
                { "id": "minecraft", "name": "Minecraft servers", "description": "Java Edition" }
              ],
              "endpoints": [
                { "id": "website", "name": "Website", "type": "http", "category": "web",
                  "url": "https://example.com", "maxClass": 2,
                  "visibility": "public", "notify": ["ops-email"] },
                { "id": "admin", "name": "Admin panel", "type": "http",
                  "url": "http://10.0.0.5:8080", "maxClass": 4,
                  "intervalSeconds": 10, "reminders": [15, 30, 60, 120] },
                { "id": "test-mc", "name": "Test server", "type": "minecraft", "category": "minecraft",
                  "host": "10.0.0.9", "port": 25566, "notify": false, "reminders": [],
                  "failureThreshold": 3, "timeoutMillis": 1000, "reminderRepeatMinutes": 0 }
              ]
            }
            """;

    private static ConfigLoader loader() {
        return new ConfigLoader(ENV::get, SYSTEM_ZONE);
    }

    private static Config load(String json) throws ConfigLoadException {
        return loader().load(json);
    }

    private static List<String> errors(String json) {
        try {
            loader().load(json);
        } catch (ConfigLoadException e) {
            return e.errors();
        }
        return fail("expected a ConfigLoadException for: " + json);
    }

    private static void assertSingleError(String json, String expected) {
        List<String> errors = errors(json);
        assertEquals(1, errors.size(), () -> "errors: " + errors);
        assertTrue(errors.getFirst().contains(expected), () -> "expected \"" + expected + "\" in " + errors);
    }

    /** A minimal valid config with the given endpoints JSON and one channel and two categories. */
    private static String withEndpoints(String endpoints) {
        return """
                { "notifications": { "channels": { "mail": {} } },
                  "categories": [ { "id": "web", "name": "Web" }, { "id": "games", "name": "Games" } ],
                  "endpoints": [ %s ] }
                """.formatted(endpoints);
    }

    // ---- loading a full config ---------------------------------------------------------

    @Test
    void loadsFullConfig() throws Exception {
        Config c = load(FULL);

        assertEquals(9090, c.server().port());
        assertEquals("127.0.0.1", c.server().bind());
        assertEquals("https://status.example.com", c.server().publicBaseUrl());
        assertEquals(List.of("127.0.0.1"), c.server().trustedProxies());
        assertEquals(UnknownHostsPolicy.REJECT, c.server().unknownHosts());
        assertEquals(Path.of("var/data"), c.dataDir());
        assertEquals(ZoneId.of("Atlantic/Reykjavik"), c.timezone());
        assertEquals(Path.of("my-plugins"), c.pluginsDir());

        assertEquals("Status Prod", c.dashboard().title());
        assertEquals(100.0, c.dashboard().yellowBelow());
        assertEquals(98.5, c.dashboard().orangeBelow());
        assertEquals(90.0, c.dashboard().redBelow());

        assertEquals(new MaintenanceSettings(true, 3, 14), c.maintenance());
        assertEquals(new EndpointDefaults(60, 4000, 2, Visibility.PUBLIC_STATUS,
                List.of("ops-email", "discord"), List.of(10, 20), 240), c.defaults());

        assertEquals(Set.of("ops-email", "discord", "mc-discord"), c.notificationChannels());
        assertEquals("g-secret", c.auth().getAsJsonArray("providers").get(0).getAsJsonObject()
                .get("clientSecret").getAsString());

        assertEquals(List.of(new CategoryConfig("web", "Websites", null),
                new CategoryConfig("minecraft", "Minecraft servers", "Java Edition")), c.categories());

        assertEquals(2, c.sites().size());
        SiteConfig mc = c.sites().get(0);
        assertEquals(List.of("mc-status.example.com"), mc.hosts());
        assertEquals("Minecraft status", mc.title());
        assertEquals("https://mc-status.example.com", mc.publicBaseUrl());
        assertEquals(List.of("minecraft"), mc.categories());
        assertFalse(mc.includesEverything());
        SiteConfig web = c.sites().get(1);
        assertEquals(List.of("*.web.example.com"), web.hosts());
        assertNull(web.title());
        assertNull(web.publicBaseUrl());
        assertEquals(List.of("website"), web.endpoints());

        assertEquals(List.of("website", "admin", "test-mc"),
                c.endpoints().stream().map(EndpointDefinition::id).toList());
        EndpointDefinition website = c.endpoints().get(0);
        assertEquals("Website", website.name());
        assertEquals("http", website.type());
        assertEquals("web", website.category());
        assertEquals(Visibility.PUBLIC, website.visibility());
        assertEquals(List.of("ops-email"), website.notifyChannels());
        JsonObject settings = JsonParser.parseString("{\"url\":\"https://example.com\",\"maxClass\":2}").getAsJsonObject();
        assertEquals(settings, website.settings());

        EndpointDefinition testMc = c.endpoints().get(2);
        assertEquals(List.of(), testMc.notifyChannels());
        assertEquals(List.of(), testMc.reminders());
        assertEquals(3, testMc.failureThreshold());
        assertEquals(1000, testMc.timeoutMillis());
        assertEquals(0, testMc.reminderRepeatMinutes());
        assertEquals(Set.of("host", "port"), testMc.settings().keySet());

        assertTrue(c.warnings().isEmpty(), () -> "warnings: " + c.warnings());
    }

    @Test
    void loadsFromFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.json");
        Files.writeString(file, FULL);
        assertEquals(3, loader().load(file).endpoints().size());
    }

    @Test
    void missingFileIsAnError(@TempDir Path dir) {
        ConfigLoadException e = assertThrows(ConfigLoadException.class,
                () -> loader().load(dir.resolve("nope.json")));
        assertTrue(e.errors().getFirst().contains("file not found"));
    }

    // ---- defaults applied --------------------------------------------------------------

    @Test
    void emptyConfigUsesBuiltInDefaults() throws Exception {
        Config c = load("{}");
        assertEquals(8080, c.server().port());
        assertEquals("0.0.0.0", c.server().bind());
        assertNull(c.server().publicBaseUrl());
        assertEquals(List.of(), c.server().trustedProxies());
        assertEquals(UnknownHostsPolicy.DEFAULT, c.server().unknownHosts());
        assertEquals(Path.of("data"), c.dataDir());
        assertEquals(SYSTEM_ZONE, c.timezone());
        assertEquals(Path.of("plugins"), c.pluginsDir());
        assertEquals(new DashboardConfig("Status", 100.0, 99.0, 95.0), c.dashboard());
        assertEquals(new MaintenanceSettings(false, 7, 7), c.maintenance());
        assertEquals(new EndpointDefaults(30, 5000, 1, Visibility.PRIVATE, List.of(), List.of(30, 60, 120), 0),
                c.defaults());
        assertTrue(c.sites().isEmpty());
        assertTrue(c.categories().isEmpty());
        assertTrue(c.endpoints().isEmpty());
        assertTrue(c.notificationChannels().isEmpty());
    }

    @Test
    void endpointInheritsDefaults() throws Exception {
        Config c = load(FULL);
        EndpointDefinition admin = c.endpoints().get(1);
        assertNull(admin.category());
        assertEquals(Visibility.PUBLIC_STATUS, admin.visibility()); // from defaults
        assertEquals(10, admin.intervalSeconds());                  // overridden
        assertEquals(4000, admin.timeoutMillis());                  // from defaults
        assertEquals(2, admin.failureThreshold());                  // from defaults
        assertEquals(List.of("ops-email", "discord"), admin.notifyChannels());
        assertEquals(List.of(15, 30, 60, 120), admin.reminders());  // overridden
        assertEquals(240, admin.reminderRepeatMinutes());           // from defaults
    }

    @Test
    void endpointWithoutAnyDefaultsIsPrivateAndSilent() throws Exception {
        Config c = load("""
                { "endpoints": [ { "id": "a", "name": "A", "type": "http" } ] }
                """);
        EndpointDefinition a = c.endpoints().getFirst();
        assertEquals(Visibility.PRIVATE, a.visibility());
        assertEquals(List.of(), a.notifyChannels());
        assertEquals(List.of(30, 60, 120), a.reminders());
        assertEquals(30, a.intervalSeconds());
        assertEquals(5000, a.timeoutMillis());
        assertEquals(1, a.failureThreshold());
    }

    @Test
    void defaultsNotifyFalseMeansOptIn() throws Exception {
        Config c = load("""
                { "notifications": { "channels": { "mail": {} } },
                  "defaults": { "notify": false },
                  "endpoints": [ { "id": "a", "name": "A", "type": "http" },
                                 { "id": "b", "name": "B", "type": "http", "notify": ["mail"] } ] }
                """);
        assertEquals(List.of(), c.endpoints().get(0).notifyChannels());
        assertEquals(List.of("mail"), c.endpoints().get(1).notifyChannels());
    }

    // ---- validation rules --------------------------------------------------------------

    @Test
    void rejectsInvalidJson() {
        assertTrue(errors("{ \"server\": ").getFirst().startsWith("invalid JSON"));
        assertTrue(errors("{} {}").getFirst().startsWith("invalid JSON"));
        assertTrue(errors("{ // comment\n }").getFirst().startsWith("invalid JSON"));
        assertEquals(List.of("the config must be a JSON object"), errors("[]"));
    }

    @Test
    void rejectsUnknownKeys() {
        assertSingleError("{ \"endpoint\": [] }", "endpoint: unknown setting");
        assertSingleError("{ \"server\": { \"prot\": 1 } }", "server.prot: unknown setting");
        assertSingleError("{ \"defaults\": { \"interval\": 1 } }", "defaults.interval: unknown setting");
        assertSingleError("{ \"dashboard\": { \"colors\": { \"green\": 1 } } }", "dashboard.colors.green: unknown setting");
        assertSingleError("{ \"categories\": [ { \"id\": \"a\", \"name\": \"A\", \"desc\": \"x\" } ] }",
                "categories[0] \"a\".desc: unknown setting");
        assertSingleError("{ \"sites\": [ { \"hosts\": [\"a.example\"], \"host\": \"x\" } ] }",
                "sites[0].host: unknown setting");
    }

    @Test
    void validatesServer() {
        assertSingleError("{ \"server\": { \"port\": 0 } }", "server.port: must be between 1 and 65535");
        assertSingleError("{ \"server\": { \"port\": 70000 } }", "server.port: must be between 1 and 65535");
        assertSingleError("{ \"server\": { \"port\": \"8080\" } }", "server.port: must be an integer");
        assertSingleError("{ \"server\": { \"port\": 80.5 } }", "server.port: must be an integer");
        assertSingleError("{ \"server\": { \"bind\": \"\" } }", "server.bind: must not be empty");
        assertSingleError("{ \"server\": { \"publicBaseUrl\": \"ftp://x.example\" } }",
                "server.publicBaseUrl: must be an http or https URL");
        assertSingleError("{ \"server\": { \"publicBaseUrl\": \"https://x.example/?a=b\" } }",
                "server.publicBaseUrl: must not have a query or fragment");
        assertSingleError("{ \"server\": { \"publicBaseUrl\": \"status.example.com\" } }",
                "server.publicBaseUrl: must be an http or https URL");
        assertSingleError("{ \"server\": { \"trustedProxies\": \"127.0.0.1\" } }",
                "server.trustedProxies: must be an array of strings");
        assertSingleError("{ \"server\": { \"unknownHosts\": \"drop\" } }",
                "server.unknownHosts: must be \"default\" or \"reject\"");
        assertSingleError("{ \"server\": 8080 }", "server: must be an object");
    }

    @Test
    void validatesTopLevelValues() {
        assertSingleError("{ \"timezone\": \"Mars/Olympus\" }", "timezone: unknown time zone \"Mars/Olympus\"");
        assertSingleError("{ \"timezone\": \"+01:00\" }", "timezone: unknown time zone");
        assertSingleError("{ \"dataDir\": \"\" }", "dataDir: must not be empty");
        assertSingleError("{ \"dataDir\": 5 }", "dataDir: must be a string");
        assertSingleError("{ \"plugins\": { \"dir\": \"\" } }", "plugins.dir: must not be empty");
        assertSingleError("{ \"auth\": [] }", "auth: must be an object");
        assertSingleError("{ \"notifications\": { \"channels\": [] } }", "notifications.channels: must be an object");
    }

    @Test
    void validatesDashboardAndMaintenance() {
        assertSingleError("{ \"dashboard\": { \"title\": 1 } }", "dashboard.title: must be a string");
        assertSingleError("{ \"dashboard\": { \"colors\": { \"redBelow\": 101 } } }",
                "dashboard.colors.redBelow: must be between 0 and 100");
        assertSingleError("{ \"dashboard\": { \"colors\": { \"orangeBelow\": 90, \"redBelow\": 95 } } }",
                "dashboard.colors: thresholds must satisfy yellowBelow ≥ orangeBelow ≥ redBelow");
        assertSingleError("{ \"maintenance\": { \"countsAsDowntime\": \"yes\" } }",
                "maintenance.countsAsDowntime: must be true or false");
        assertSingleError("{ \"maintenance\": { \"maxLengthDays\": 0 } }", "maintenance.maxLengthDays: must be between 1");
    }

    @Test
    void validatesDefaults() {
        assertSingleError("{ \"defaults\": { \"intervalSeconds\": 0 } }", "defaults.intervalSeconds: must be between 1");
        assertSingleError("{ \"defaults\": { \"timeoutMillis\": 50 } }", "defaults.timeoutMillis: must be between 100");
        assertSingleError("{ \"defaults\": { \"failureThreshold\": 0 } }", "defaults.failureThreshold: must be between 1");
        assertSingleError("{ \"defaults\": { \"visibility\": \"hidden\" } }",
                "defaults.visibility: must be one of \"public\", \"public-status\", \"private\"");
        assertSingleError("{ \"defaults\": { \"notify\": [\"nope\"] } }",
                "defaults.notify: unknown notification channel \"nope\" (configured: none)");
        assertSingleError("{ \"defaults\": { \"notify\": true } }", "defaults.notify: must be false or an array");
        assertSingleError("{ \"defaults\": { \"reminders\": [30, 30] } }", "defaults.reminders: must be strictly increasing");
        assertSingleError("{ \"defaults\": { \"reminderRepeatMinutes\": -1 } }",
                "defaults.reminderRepeatMinutes: must be between 0");
    }

    @Test
    void validatesCategories() {
        assertSingleError("{ \"categories\": [ { \"id\": \"Web\", \"name\": \"Web\" } ] }",
                "categories[0] \"Web\".id: \"Web\" must match [a-z0-9-]+");
        assertSingleError("{ \"categories\": [ { \"name\": \"Web\" } ] }", "categories[0].id: is required");
        assertSingleError("{ \"categories\": [ { \"id\": \"web\" } ] }", "categories[0] \"web\".name: is required");
        assertSingleError("{ \"categories\": [ { \"id\": \"web\", \"name\": \"A\" }, { \"id\": \"web\", \"name\": \"B\" } ] }",
                "categories[1] \"web\".id: duplicate category id \"web\"");
        assertSingleError("{ \"categories\": [ \"web\" ] }", "categories[0]: must be an object");
        assertSingleError("{ \"categories\": {} }", "categories: must be an array");
    }

    @Test
    void validatesEndpointIdsAndNames() {
        assertSingleError(withEndpoints("{ \"name\": \"A\", \"type\": \"http\" }"), "endpoints[0].id: is required");
        assertSingleError(withEndpoints("{ \"id\": \"my host.com\", \"name\": \"A\", \"type\": \"http\" }"),
                "endpoints[0] \"my host.com\".id: \"my host.com\" must match [a-z0-9-]+");
        assertSingleError(withEndpoints("{ \"id\": \"a\", \"name\": \"A\", \"type\": \"http\" },"
                        + "{ \"id\": \"a\", \"name\": \"B\", \"type\": \"http\" }"),
                "endpoints[1] \"a\".id: duplicate endpoint id \"a\"");
        assertSingleError(withEndpoints("{ \"id\": \"a\", \"name\": \"Same\", \"type\": \"http\" },"
                        + "{ \"id\": \"b\", \"name\": \"Same\", \"type\": \"http\" }"),
                "endpoints[1] \"b\".name: duplicate endpoint name \"Same\" (also used by endpoints[0] \"a\")");
        assertSingleError(withEndpoints("{ \"id\": \"a\", \"name\": \"\", \"type\": \"http\" }"),
                "endpoints[0] \"a\".name: must not be empty");
        assertSingleError(withEndpoints("{ \"id\": \"a\", \"name\": \"A\" }"), "endpoints[0] \"a\".type: is required");
        assertSingleError(withEndpoints("{ \"id\": \"a\", \"name\": \"A\", \"type\": \"HTTP\" }"),
                "endpoints[0] \"a\".type: \"HTTP\" must match");
        assertSingleError(withEndpoints("42"), "endpoints[0]: must be an object");
    }

    @Test
    void validatesEndpointCommonFields() {
        String base = "{ \"id\": \"a\", \"name\": \"A\", \"type\": \"http\", %s }";
        assertSingleError(withEndpoints(base.formatted("\"category\": \"nope\"")),
                "endpoints[0] \"a\".category: unknown category \"nope\" (configured: games, web)");
        assertSingleError(withEndpoints(base.formatted("\"visibility\": \"secret\"")),
                "endpoints[0] \"a\".visibility: must be one of");
        assertSingleError(withEndpoints(base.formatted("\"intervalSeconds\": 0")),
                "endpoints[0] \"a\".intervalSeconds: must be between 1 and 86400 (got 0)");
        assertSingleError(withEndpoints(base.formatted("\"timeoutMillis\": 999999")),
                "endpoints[0] \"a\".timeoutMillis: must be between 100 and 300000");
        assertSingleError(withEndpoints(base.formatted("\"failureThreshold\": 101")),
                "endpoints[0] \"a\".failureThreshold: must be between 1 and 100");
        assertSingleError(withEndpoints(base.formatted("\"notify\": [\"mail\", \"sms\"]")),
                "endpoints[0] \"a\".notify: unknown notification channel \"sms\" (configured: mail)");
        assertSingleError(withEndpoints(base.formatted("\"notify\": \"mail\"")),
                "endpoints[0] \"a\".notify: must be an array of strings");
        assertSingleError(withEndpoints(base.formatted("\"reminders\": [0, 10]")),
                "endpoints[0] \"a\".reminders: must contain positive whole numbers");
        assertSingleError(withEndpoints(base.formatted("\"reminders\": [10, 5]")),
                "endpoints[0] \"a\".reminders: must be strictly increasing (5 after 10)");
        assertSingleError(withEndpoints(base.formatted("\"reminders\": [10, 1.5]")),
                "endpoints[0] \"a\".reminders: must contain positive whole numbers");
        assertSingleError(withEndpoints(base.formatted("\"reminders\": 30")),
                "endpoints[0] \"a\".reminders: must be an array of minutes");
    }

    @Test
    void typeSpecificKeysArePassedThroughNotRejected() throws Exception {
        Config c = load(withEndpoints("{ \"id\": \"a\", \"name\": \"A\", \"type\": \"x\", \"anything\": {\"n\": [1]} }"));
        assertEquals(Set.of("anything"), c.endpoints().getFirst().settings().keySet());
    }

    @Test
    void validatesSites() {
        assertSingleError("{ \"sites\": [ { \"title\": \"x\" } ] }", "sites[0].hosts: is required");
        assertSingleError("{ \"sites\": [ { \"hosts\": [] } ] }", "sites[0].hosts: must not be empty");
        assertSingleError("{ \"sites\": [ { \"hosts\": [\"a.example\"] }, { \"hosts\": [\"A.example\"] } ] }",
                "sites[1].hosts: \"a.example\" is also listed in sites[0]");
        assertSingleError("{ \"sites\": [ { \"hosts\": [\"a.example\", \"a.example\"] } ] }",
                "sites[0].hosts: \"a.example\" is also listed in this site");
        assertSingleError("{ \"sites\": [ { \"hosts\": [\"a.*.example\"] } ] }",
                "sites[0].hosts: \"a.*.example\" is not a valid host name");
        assertSingleError("{ \"sites\": [ { \"hosts\": [\"a.example:8080\"] } ] }",
                "sites[0].hosts: \"a.example:8080\" is not a valid host name");
        assertSingleError("{ \"sites\": [ { \"hosts\": [\"a.example\"], \"publicBaseUrl\": \"nope\" } ] }",
                "sites[0].publicBaseUrl: must be an http or https URL");
        assertSingleError("{ \"sites\": [ { \"hosts\": [\"a.example\"], \"categories\": \"web\" } ] }",
                "sites[0].categories: must be an array of strings");
    }

    @Test
    void siteWarningsDoNotFailLoading() throws Exception {
        Config c = load("""
                { "sites": [
                    { "hosts": ["*.example.com"] },
                    { "hosts": ["mc.example.com"], "categories": ["gone"], "endpoints": ["deleted"] } ] }
                """);
        assertEquals(2, c.sites().size());
        List<String> w = c.warnings();
        assertEquals(3, w.size(), () -> "warnings: " + w);
        assertTrue(w.stream().anyMatch(s -> s.startsWith("sites[1].hosts: \"mc.example.com\" overlaps with \"*.example.com\"")), w::toString);
        assertTrue(w.contains("sites[1].categories: unknown category \"gone\""), w::toString);
        assertTrue(w.contains("sites[1].endpoints: endpoint \"deleted\" is not in config.json"), w::toString);
    }

    @Test
    void emptyCategoryIsAWarning() throws Exception {
        Config c = load("{ \"categories\": [ { \"id\": \"empty\", \"name\": \"Empty\" } ] }");
        assertEquals(List.of("categories: category \"empty\" has no endpoints in config.json"), c.warnings());
    }

    // ---- all errors reported together --------------------------------------------------

    @Test
    void reportsAllErrorsTogether() {
        ConfigLoadException e = assertThrows(ConfigLoadException.class, () -> load("""
                { "server": { "port": -1 },
                  "timezone": "Nowhere/Land",
                  "defaults": { "reminders": [5, 1] },
                  "categories": [ { "id": "c", "name": "C" } ],
                  "endpoints": [
                    { "id": "BAD", "name": "x", "type": "http" },
                    { "id": "ok", "name": "Ok", "type": "http", "category": "missing", "secret": "${env:NOPE}" } ],
                  "sites": [ { "hosts": [] } ],
                  "typo": 1 }
                """));
        List<String> errors = e.errors();
        assertEquals(8, errors.size(), () -> "errors: " + errors);
        List<String> prefixes = List.of(
                "server.port:", "timezone:", "defaults.reminders:", "endpoints[0] \"BAD\".id:",
                "endpoints[1] \"ok\".category:", "endpoints[1].secret: unresolved ${env:NOPE}",
                "sites[0].hosts:", "typo: unknown setting");
        for (String prefix : prefixes) {
            assertTrue(errors.stream().anyMatch(s -> s.startsWith(prefix)), () -> prefix + " missing in " + errors);
        }
        assertTrue(e.getMessage().startsWith("8 configuration errors:"));
        for (String error : errors) {
            assertTrue(e.getMessage().contains("\n  " + error));
        }
    }

    // ---- env substitution --------------------------------------------------------------

    @Test
    void substitutesEnvInEveryString() throws Exception {
        Config c = load("""
                { "dataDir": "${env:BASE}/data",
                  "server": { "publicBaseUrl": "https://${env:HOST}" },
                  "endpoints": [ { "id": "a", "name": "A", "type": "http",
                                   "url": "${env:HOST}:${env:PORT}", "list": ["${env:PORT}"] } ] }
                """, Map.of("BASE", "/srv", "HOST", "status.example.com", "PORT", "8443"));
        assertEquals(Path.of("/srv/data"), c.dataDir());
        assertEquals("https://status.example.com", c.server().publicBaseUrl());
        JsonObject settings = c.endpoints().getFirst().settings();
        assertEquals("status.example.com:8443", settings.get("url").getAsString());
        assertEquals("8443", settings.getAsJsonArray("list").get(0).getAsString());
    }

    @Test
    void envValuesAreNotReinterpreted() throws Exception {
        Config c = load("{ \"dataDir\": \"${env:A}\" }", Map.of("A", "${env:B}$1\\x", "B", "nope"));
        assertEquals(Path.of("${env:B}$1\\x"), c.dataDir());
    }

    @Test
    void emptyEnvValueIsResolved() throws Exception {
        Config c = load("{ \"dashboard\": { \"title\": \"Status${env:SUFFIX}\" } }", Map.of("SUFFIX", ""));
        assertEquals("Status", c.dashboard().title());
    }

    @Test
    void unresolvedEnvIsAnErrorWithItsPath() {
        ConfigLoadException e = assertThrows(ConfigLoadException.class, () -> load("""
                { "auth": { "providers": [ { "clientSecret": "${env:MISSING_ONE}" } ] },
                  "notifications": { "channels": { "d": { "webhookUrl": "x${env:MISSING_TWO}y" } } } }
                """, Map.of()));
        assertEquals(List.of(
                "auth.providers[0].clientSecret: unresolved ${env:MISSING_ONE} (environment variable MISSING_ONE is not set)",
                "notifications.channels.d.webhookUrl: unresolved ${env:MISSING_TWO} (environment variable MISSING_TWO is not set)"),
                e.errors());
    }

    @Test
    void malformedEnvReferencesAreErrors() {
        assertSingleError("{ \"dataDir\": \"${env:}\" }", "dataDir: invalid environment variable name in ${env:}");
        assertSingleError("{ \"dataDir\": \"${env:A-B}\" }", "dataDir: invalid environment variable name");
        assertSingleError("{ \"dataDir\": \"${env:HOME\" }", "dataDir: unterminated ${env:…} reference");
    }

    @Test
    void envErrorsNeverShowOtherValues() {
        ConfigLoadException e = assertThrows(ConfigLoadException.class, () -> load(
                "{ \"x\": \"${env:SECRET}${env:MISSING}\" }", Map.of("SECRET", "hunter2")));
        assertFalse(e.getMessage().contains("hunter2"), e::getMessage);
    }

    private static Config load(String json, Map<String, String> env) throws ConfigLoadException {
        return new ConfigLoader(env::get, SYSTEM_ZONE).load(json);
    }
}
