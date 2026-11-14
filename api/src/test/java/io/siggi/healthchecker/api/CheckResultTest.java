package io.siggi.healthchecker.api;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CheckResultTest {
    @Test
    void okAndFail() {
        CheckResult ok = CheckResult.ok("fine");
        assertTrue(ok.success());
        assertEquals("fine", ok.message());
        assertEquals(Map.of(), ok.details());

        CheckResult fail = CheckResult.fail("broken");
        assertFalse(fail.success());
        assertEquals("broken", fail.message());
        assertTrue(fail.details().isEmpty());
    }

    @Test
    void nullMessageBecomesEmpty() {
        assertEquals("", CheckResult.ok(null).message());
        assertEquals("", new CheckResult(false, null, null).message());
        assertTrue(new CheckResult(false, null, null).details().isEmpty());
    }

    @Test
    void withAddsDetailsInOrderWithoutChangingOriginal() {
        CheckResult base = CheckResult.ok("up");
        CheckResult r = base.with("statusCode", 200).with("version", "1.21").with("tls", true);
        assertTrue(base.details().isEmpty());
        assertTrue(r.success());
        assertEquals("up", r.message());
        assertEquals(List.of("statusCode", "version", "tls"), List.copyOf(r.details().keySet()));
        assertEquals(200, r.details().get("statusCode"));
        assertEquals("1.21", r.details().get("version"));
        assertEquals(true, r.details().get("tls"));
    }

    @Test
    void withReplacesExistingKeyInPlace() {
        CheckResult r = CheckResult.fail("x").with("a", 1).with("b", 2).with("a", 3);
        assertEquals(List.of("a", "b"), List.copyOf(r.details().keySet()));
        assertEquals(3, r.details().get("a"));
    }

    @Test
    void detailsAreImmutableAndCopied() {
        Map<String, Object> source = new HashMap<>();
        source.put("k", 1L);
        CheckResult r = new CheckResult(true, "m", source);
        source.put("other", "x");
        assertEquals(Map.of("k", 1L), r.details());
        assertThrows(UnsupportedOperationException.class, () -> r.details().put("z", 1));
        assertThrows(UnsupportedOperationException.class,
                () -> r.with("y", 2).details().remove("k"));
    }

    @Test
    void rejectsNonJsonFriendlyValuesAndBadKeys() {
        CheckResult r = CheckResult.ok("m");
        assertThrows(IllegalArgumentException.class, () -> r.with("k", null));
        assertThrows(IllegalArgumentException.class, () -> r.with("k", new Object()));
        assertThrows(IllegalArgumentException.class, () -> r.with("k", List.of("a")));
        assertThrows(NullPointerException.class, () -> r.with(null, 1));
        assertThrows(IllegalArgumentException.class, () -> r.with(" ", 1));
        Map<String, Object> bad = new HashMap<>();
        bad.put("k", null);
        assertThrows(IllegalArgumentException.class, () -> new CheckResult(true, "m", bad));
    }

    @Test
    void equality() {
        assertEquals(CheckResult.ok("a").with("x", 1), CheckResult.ok("a").with("x", 1));
        assertNotEquals(CheckResult.ok("a"), CheckResult.fail("a"));
    }
}
