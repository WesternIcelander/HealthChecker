package io.siggi.healthchecker.api;

import io.siggi.healthchecker.api.SettingSpec.Kind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SettingSpecTest {
    @Test
    void factoriesSetKindAndDefaults() {
        assertSpec(SettingSpec.string("host", "Host"), "host", "Host", Kind.STRING);
        assertSpec(SettingSpec.integer("port", "Port"), "port", "Port", Kind.INTEGER);
        assertSpec(SettingSpec.bool("srv", "Use SRV"), "srv", "Use SRV", Kind.BOOLEAN);
        assertSpec(SettingSpec.stringList("tags", "Tags"), "tags", "Tags", Kind.STRING_LIST);
        SettingSpec c = SettingSpec.choice("maxClass", "Max class", "2", "3", "4", "5");
        assertEquals(Kind.CHOICE, c.kind());
        assertEquals(List.of("2", "3", "4", "5"), c.choices());
        assertFalse(c.required());
    }

    private static void assertSpec(SettingSpec s, String key, String label, Kind kind) {
        assertEquals(key, s.key());
        assertEquals(label, s.label());
        assertEquals(kind, s.kind());
        assertFalse(s.required());
        assertNull(s.defaultValue());
        assertNull(s.help());
        assertEquals(List.of(), s.choices());
    }

    @Test
    void modifiersReturnCopies() {
        SettingSpec base = SettingSpec.integer("port", "Port");
        SettingSpec s = base.asRequired().defaultValue(25565).help("TCP port");
        assertFalse(base.required());
        assertNull(base.defaultValue());
        assertNull(base.help());
        assertTrue(s.required());
        assertEquals(25565, s.defaultValue());
        assertEquals("TCP port", s.help());
        assertEquals("port", s.key());
        assertEquals(Kind.INTEGER, s.kind());
        assertEquals(s, SettingSpec.integer("port", "Port").help("TCP port").defaultValue(25565).asRequired());
    }

    @Test
    void defaultValueMustMatchKind() {
        assertThrows(IllegalArgumentException.class, () -> SettingSpec.integer("p", "P").defaultValue("25565"));
        assertThrows(IllegalArgumentException.class, () -> SettingSpec.integer("p", "P").defaultValue(1L));
        assertThrows(IllegalArgumentException.class, () -> SettingSpec.string("h", "H").defaultValue(1));
        assertThrows(IllegalArgumentException.class, () -> SettingSpec.bool("b", "B").defaultValue("true"));
        assertThrows(IllegalArgumentException.class, () -> SettingSpec.stringList("l", "L").defaultValue(List.of(1)));
        assertThrows(IllegalArgumentException.class, () -> SettingSpec.choice("c", "C", "a", "b").defaultValue("z"));
        assertEquals("a", SettingSpec.choice("c", "C", "a", "b").defaultValue("a").defaultValue());
        assertEquals(true, SettingSpec.bool("b", "B").defaultValue(true).defaultValue());
        assertNull(SettingSpec.string("h", "H").defaultValue("x").defaultValue(null).defaultValue());
    }

    @Test
    void listsAreCopiedAndImmutable() {
        List<String> choices = new ArrayList<>(List.of("a", "b"));
        SettingSpec c = SettingSpec.choice("c", "C", choices);
        choices.add("z");
        assertEquals(List.of("a", "b"), c.choices());
        assertThrows(UnsupportedOperationException.class, () -> c.choices().add("x"));

        List<String> def = new ArrayList<>(List.of("x"));
        SettingSpec l = SettingSpec.stringList("l", "L").defaultValue(def);
        def.add("y");
        assertEquals(List.of("x"), l.defaultValue());
    }

    @Test
    void invalidSpecsRejected() {
        assertThrows(NullPointerException.class, () -> SettingSpec.string(null, "L"));
        assertThrows(NullPointerException.class, () -> SettingSpec.string("k", null));
        assertThrows(IllegalArgumentException.class, () -> SettingSpec.string(" ", "L"));
        assertThrows(IllegalArgumentException.class, () -> SettingSpec.choice("c", "C"));
        assertThrows(IllegalArgumentException.class,
                () -> new SettingSpec("k", "L", Kind.STRING, false, null, null, List.of("a")));
    }

    @Test
    void configExceptionCarriesOptionalKey() {
        assertTrue(new ConfigException("bad").settingKey().isEmpty());
        ConfigException e = new ConfigException("port", "out of range");
        assertEquals("port", e.settingKey().orElseThrow());
        assertEquals("out of range", e.getMessage());
    }
}
