package io.siggi.healthchecker.plugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.healthchecker.api.ConfigException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonEndpointConfigTest {
    private static JsonEndpointConfig config(String json) {
        return new JsonEndpointConfig("survival", "Survival", JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void idAndName() {
        JsonEndpointConfig c = config("{}");
        assertEquals("survival", c.id());
        assertEquals("Survival", c.name());
    }

    @Test
    void typedGetters() throws ConfigException {
        JsonEndpointConfig c = config("""
                { "host": "mc.example.com", "port": 25566, "srv": false,
                  "big": 1e3, "neg": -5, "list": ["a", "b"], "empty": [] }
                """);
        assertEquals("mc.example.com", c.getString("host"));
        assertEquals(Optional.of("mc.example.com"), c.optString("host"));
        assertEquals(25566, c.getInt("port"));
        assertEquals(OptionalInt.of(25566), c.optInt("port"));
        assertEquals(1000, c.getInt("big"));
        assertEquals(-5, c.getInt("neg"));
        assertFalse(c.optBoolean("srv", true));
        assertEquals(List.of("a", "b"), c.optStringList("list"));
        assertEquals(List.of(), c.optStringList("empty"));
        assertEquals(Set.of("host", "port", "srv", "big", "neg", "list", "empty"), c.keys());
    }

    @Test
    void optionalGettersWhenAbsentOrNull() throws ConfigException {
        JsonEndpointConfig c = config("{ \"nothing\": null }");
        for (String key : List.of("absent", "nothing")) {
            assertEquals(Optional.empty(), c.optString(key));
            assertEquals(OptionalInt.empty(), c.optInt(key));
            assertTrue(c.optBoolean(key, true));
            assertFalse(c.optBoolean(key, false));
            assertEquals(List.of(), c.optStringList(key));
        }
    }

    @Test
    void missingRequiredKey() {
        JsonEndpointConfig c = config("{ \"nothing\": null }");
        ConfigException e = assertThrows(ConfigException.class, () -> c.getString("host"));
        assertEquals(Optional.of("host"), e.settingKey());
        assertTrue(e.getMessage().contains("missing"), e.getMessage());
        assertTrue(e.getMessage().contains("\"host\""), e.getMessage());

        e = assertThrows(ConfigException.class, () -> c.getInt("port"));
        assertEquals(Optional.of("port"), e.settingKey());

        e = assertThrows(ConfigException.class, () -> c.getString("nothing"));
        assertEquals(Optional.of("nothing"), e.settingKey());
    }

    @Test
    void wrongType() {
        JsonEndpointConfig c = config("""
                { "num": 5, "str": "5", "bool": true, "frac": 1.5, "huge": 3000000000,
                  "obj": {}, "arr": ["x"], "mixed": ["x", 1], "strTrue": "true" }
                """);
        assertWrongType("num", () -> c.getString("num"), "a string");
        assertWrongType("bool", () -> c.optString("bool"), "a string");
        assertWrongType("arr", () -> c.getString("arr"), "a string");
        assertWrongType("str", () -> c.getInt("str"), "an integer");
        assertWrongType("frac", () -> c.optInt("frac"), "an integer");
        assertWrongType("obj", () -> c.getInt("obj"), "an integer");
        assertWrongType("strTrue", () -> c.optBoolean("strTrue", false), "a boolean");
        assertWrongType("num", () -> c.optBoolean("num", false), "a boolean");
        assertWrongType("str", () -> c.optStringList("str"), "a list of strings");
        assertWrongType("mixed", () -> c.optStringList("mixed"), "a list of strings");

        ConfigException e = assertThrows(ConfigException.class, () -> c.getInt("huge"));
        assertEquals(Optional.of("huge"), e.settingKey());
        assertTrue(e.getMessage().contains("out of range"), e.getMessage());
    }

    private static void assertWrongType(String key, ThrowingCall call, String expected) {
        ConfigException e = assertThrows(ConfigException.class, call::run);
        assertEquals(Optional.of(key), e.settingKey());
        assertEquals("setting \"" + key + "\" must be " + expected, e.getMessage());
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    @Test
    void unreadKeysReported() throws ConfigException {
        JsonEndpointConfig c = config("{ \"host\": \"h\", \"prot\": 1, \"srv\": true, \"extra\": null }");
        assertEquals(List.of("host", "prot", "srv", "extra"), List.copyOf(c.unreadKeys()));

        c.keys(); // listing keys doesn't count as reading them
        c.getString("host");
        c.optInt("port"); // reading an absent key is fine and doesn't affect others
        c.optBoolean("srv", false);
        assertEquals(List.of("prot", "extra"), List.copyOf(c.unreadKeys()));

        ConfigException e = assertThrows(ConfigException.class, c::checkNoUnreadKeys);
        assertEquals(Optional.of("prot"), e.settingKey());
        assertEquals("unknown settings \"prot\", \"extra\"", e.getMessage());

        c.optString("extra");
        e = assertThrows(ConfigException.class, c::checkNoUnreadKeys);
        assertEquals("unknown setting \"prot\"", e.getMessage());
    }

    @Test
    void keyReadWithWrongTypeCountsAsRead() {
        JsonEndpointConfig c = config("{ \"port\": \"x\" }");
        assertThrows(ConfigException.class, () -> c.getInt("port"));
        assertTrue(c.unreadKeys().isEmpty());
    }

    @Test
    void allKeysReadPasses() throws ConfigException {
        JsonEndpointConfig c = config("{ \"host\": \"h\" }");
        c.getString("host");
        assertDoesNotThrow(c::checkNoUnreadKeys);
        assertDoesNotThrow(() -> config("{}").checkNoUnreadKeys());
    }

    @Test
    void settingsAreCopied() throws ConfigException {
        JsonObject src = JsonParser.parseString("{ \"host\": \"a\" }").getAsJsonObject();
        JsonEndpointConfig c = new JsonEndpointConfig("x", "X", src);
        src.addProperty("host", "b");
        src.addProperty("other", 1);
        assertEquals("a", c.getString("host"));
        assertEquals(Set.of("host"), c.keys());
        assertThrows(UnsupportedOperationException.class, () -> c.keys().add("y"));
    }

    @Test
    void declaredTargets() {
        JsonEndpointConfig c = config("{}");
        c.declareTarget("host", "mc.example.com");
        c.declareTarget("port", "25565");
        c.declareTarget("host", "mc.example.com");
        assertEquals(List.of(new JsonEndpointConfig.Target("host", "mc.example.com"),
                new JsonEndpointConfig.Target("port", "25565")), c.targets());
    }
}
