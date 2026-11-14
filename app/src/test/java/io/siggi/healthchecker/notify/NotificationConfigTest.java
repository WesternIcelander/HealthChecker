package io.siggi.healthchecker.notify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationConfigTest {
    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static List<String> errors(String channel) {
        List<String> errors = new ArrayList<>();
        NotificationConfig.parse(json("{\"channels\":{\"d\":" + channel + "}}"), errors, new ArrayList<>());
        return errors;
    }

    @Test
    void parsesDiscordChannels() {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        NotificationConfig c = NotificationConfig.parse(json("""
                { "batchSeconds": 10, "channels": {
                    "discord": { "type": "discord", "webhookUrl": "https://discord.com/api/webhooks/1/abc?thread_id=5",
                                 "username": "Status", "mention": "  <@&123>   @here " },
                    "plain": { "type": "discord", "webhookUrl": "http://127.0.0.1:9/hook" },
                    "mail": { "type": "email", "to": ["ops@example.com"] } } }
                """), errors, warnings);
        assertEquals(List.of(), errors);
        assertEquals(Duration.ofSeconds(10), c.batchWindow());
        assertEquals(List.of("discord", "plain", "mail"), List.copyOf(c.channels().keySet()));
        assertEquals(new NotificationConfig.Discord(URI.create("https://discord.com/api/webhooks/1/abc?thread_id=5"),
                "Status", "<@&123> @here"), c.channels().get("discord"));
        assertEquals(new NotificationConfig.Discord(URI.create("http://127.0.0.1:9/hook"), null, null),
                c.channels().get("plain"));
        assertEquals(Map.of("discord", "discord", "plain", "discord", "mail", "email"), c.types());
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("\"mail\""));
    }

    @Test
    void defaults() {
        NotificationConfig c = NotificationConfig.parse(json("{}"));
        assertEquals(Duration.ofSeconds(30), c.batchWindow());
        assertTrue(c.channels().isEmpty());
        assertEquals(Duration.ofSeconds(30), NotificationConfig.parse(null).batchWindow());
        assertEquals(Duration.ZERO, NotificationConfig.parse(json("{\"batchSeconds\":0}")).batchWindow());
    }

    @Test
    void rejectsInvalidSettings() {
        assertEquals(List.of("notifications.channels \"d\": webhookUrl is required"),
                errors("{\"type\":\"discord\"}"));
        assertEquals(List.of("notifications.channels \"d\": webhookUrl must use https (http is only allowed for localhost)"),
                errors("{\"type\":\"discord\",\"webhookUrl\":\"http://discord.example/secret-token\"}"));
        assertEquals(List.of("notifications.channels \"d\": webhookUrl must be an absolute https URL"),
                errors("{\"type\":\"discord\",\"webhookUrl\":\"dummy-DISCORD_WEBHOOK\"}"));
        assertEquals(List.of("notifications.channels \"d\": webhookUrl must not contain credentials or a fragment"),
                errors("{\"type\":\"discord\",\"webhookUrl\":\"https://u:p@discord.com/x\"}"));
        assertEquals(1, errors("{\"type\":\"discord\",\"webhookUrl\":\"https://discord.com/x\",\"mention\":\"@role\"}").size());
        assertEquals(1, errors("{\"type\":\"discord\",\"webhookUrl\":\"https://discord.com/x\",\"mention\":\"<@&12> hi\"}").size());
        assertEquals(1, errors("{\"type\":\"discord\",\"webhookUrl\":\"https://discord.com/x\",\"username\":\"My Discord bot\"}").size());
        assertEquals(1, errors("{\"type\":\"discord\",\"webhookUrl\":\"https://discord.com/x\",\"username\":\"\"}").size());
        assertEquals(List.of("notifications.channels \"d\": unknown key \"webhook\"",
                        "notifications.channels \"d\": webhookUrl is required"),
                errors("{\"type\":\"discord\",\"webhook\":\"https://discord.com/x\"}"));
        assertEquals(List.of("notifications.channels \"d\": unknown type \"slack\" (available: discord, email)"),
                errors("{\"type\":\"slack\"}"));
        assertEquals(List.of("notifications.channels \"d\": type must be \"discord\" or \"email\""), errors("{}"));
        assertEquals(List.of("notifications.channels \"d\": must be an object"), errors("[]"));

        List<String> root = new ArrayList<>();
        NotificationConfig.parse(json("{\"batchSeconds\":1.5,\"extra\":1}"), root, new ArrayList<>());
        assertEquals(List.of("notifications: unknown key \"extra\"",
                "notifications.batchSeconds: must be a whole number from 0 to 3600"), root);
    }

    @Test
    void errorsNeverEchoTheWebhookUrl() {
        for (String url : List.of("http://discord.example/secret-token", "ftp://secret-token", "https://secret-token:x@h/",
                "https://h/#secret-token", "https://h/ secret-token")) {
            for (String e : errors("{\"type\":\"discord\",\"webhookUrl\":\"" + url + "\"}")) {
                assertFalse(e.contains("secret-token"), e);
            }
        }
    }
}
