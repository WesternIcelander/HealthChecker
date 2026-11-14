package io.siggi.healthchecker.audit;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditLogTest {
    private static final Instant NOW = Instant.parse("2026-11-14T09:30:00Z");

    @TempDir
    Path dir;

    private AuditLog log() {
        return new AuditLog(dir.resolve(AuditLog.FILE_NAME), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static JsonElement json(String s) {
        return JsonParser.parseString(s);
    }

    @Test
    void recordsAndReadsBackEntries() throws Exception {
        AuditLog log = log();
        log.record("root", "Root", "endpoint.edit", "shop", json("{\"name\":\"Shop\"}"), json("{\"name\":\"Store\"}"));
        log.record(null, null, "login.failed", "192.0.2.1", null, null);

        List<AuditLog.Entry> entries = log.entries();
        assertEquals(2, entries.size());
        AuditLog.Entry first = entries.getFirst();
        assertEquals(NOW, first.time());
        assertEquals("root", first.user());
        assertEquals("Root", first.userName());
        assertEquals("endpoint.edit", first.action());
        assertEquals("shop", first.id());
        assertEquals(json("{\"name\":\"Shop\"}"), first.before());
        assertEquals(json("{\"name\":\"Store\"}"), first.after());
        AuditLog.Entry second = entries.get(1);
        assertNull(second.user());
        assertEquals(JsonNull.INSTANCE, second.before());
        assertEquals(JsonNull.INSTANCE, second.after());

        // One JSON object per line, with explicit nulls; a new instance reads the same file.
        List<String> lines = Files.readAllLines(log.file());
        assertEquals(2, lines.size());
        JsonObject line = JsonParser.parseString(lines.get(1)).getAsJsonObject();
        assertTrue(line.get("before").isJsonNull());
        assertTrue(line.has("user"));
        assertEquals(entries, log().entries());
    }

    @Test
    void pagesNewestFirst() throws Exception {
        AuditLog log = log();
        for (int i = 0; i < 7; i++) log.record("root", "Root", "a" + i, null, null, null);
        AuditLog.Page page0 = log.page(0, 3);
        assertEquals(List.of("a6", "a5", "a4"), page0.entries().stream().map(AuditLog.Entry::action).toList());
        assertTrue(page0.hasNext());
        AuditLog.Page page2 = log.page(2, 3);
        assertEquals(List.of("a0"), page2.entries().stream().map(AuditLog.Entry::action).toList());
        assertFalse(page2.hasNext());
        assertTrue(log.page(3, 3).entries().isEmpty());
        assertFalse(log.page(1, 2).entries().isEmpty());
        assertEquals(7, log().page(0, 10).entries().size());
        assertTrue(new AuditLog(dir.resolve("missing.jsonl"), Clock.systemUTC()).page(0, 10).entries().isEmpty());
    }

    @Test
    void secretsAreMasked() throws Exception {
        AuditLog log = log();
        log.record("root", "Root", "endpoint.create", "vault", null, json("""
                { "id": "vault", "host": "vault.example.invalid", "apiToken": "hunter2",
                  "nested": { "password": "pw1", "client_secret": "cs1", "X-Api-Key": "k1", "name": "fine" },
                  "list": [ { "webhookUrl": "https://discord.example.invalid/api/webhooks/1/abc" } ],
                  "url": "https://alice:pw2@example.invalid/", "other": "ftp://bob:pw3@ftp.example.invalid",
                  "plain": "https://alice@example.invalid/", "token": null, "secrets": { "a": "b" } }"""));
        String file = Files.readString(log.file());
        for (String secret : List.of("hunter2", "pw1", "cs1", "k1", "/api/webhooks/1/abc", "pw2", "pw3", "\"b\"")) {
            assertFalse(file.contains(secret), secret);
        }
        JsonObject after = log.entries().getFirst().after().getAsJsonObject();
        assertEquals(AuditLog.MASK, after.get("apiToken").getAsString());
        assertEquals("vault.example.invalid", after.get("host").getAsString());
        JsonObject nested = after.getAsJsonObject("nested");
        assertEquals(AuditLog.MASK, nested.get("password").getAsString());
        assertEquals(AuditLog.MASK, nested.get("client_secret").getAsString());
        assertEquals(AuditLog.MASK, nested.get("X-Api-Key").getAsString());
        assertEquals("fine", nested.get("name").getAsString());
        assertEquals(AuditLog.MASK, after.getAsJsonArray("list").get(0).getAsJsonObject().get("webhookUrl").getAsString());
        assertEquals("https://alice:***@example.invalid/", after.get("url").getAsString());
        assertEquals("ftp://bob:***@ftp.example.invalid", after.get("other").getAsString());
        assertEquals("https://alice@example.invalid/", after.get("plain").getAsString());
        assertTrue(after.get("token").isJsonNull()); // nothing to hide
        assertEquals(AuditLog.MASK, after.get("secrets").getAsString());

        assertTrue(AuditLog.isSecretKey("clientSecret"));
        assertTrue(AuditLog.isSecretKey("api_key"));
        assertFalse(AuditLog.isSecretKey("host"));
        assertFalse(AuditLog.isSecretKey("maxClass"));
    }

    @Test
    void aLineCutShortByACrashIsSkipped() throws Exception {
        AuditLog log = log();
        log.record("root", "Root", "first", null, null, null);
        Files.writeString(log.file(), "{\"time\":\"2026-11-14T09:30:00Z\",\"act", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        log.record("root", "Root", "second", null, null, null);
        assertEquals(List.of("first", "second"), log.entries().stream().map(AuditLog.Entry::action).toList());
    }

    @Test
    void disabledRecordsNothing() throws Exception {
        AuditLog.disabled().record("root", "Root", "x", null, null, null);
        assertNull(AuditLog.disabled().file());
        assertTrue(AuditLog.disabled().entries().isEmpty());
    }
}
