package io.siggi.healthchecker;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.siggi.healthchecker.api.CheckPlugin;
import io.siggi.healthchecker.api.CheckResult;
import io.siggi.healthchecker.api.Checker;
import io.siggi.healthchecker.api.ConfigException;
import io.siggi.healthchecker.api.EndpointConfig;
import io.siggi.healthchecker.api.SettingSpec;
import io.siggi.healthchecker.config.Config;
import io.siggi.healthchecker.config.ConfigLoader;
import io.siggi.healthchecker.config.EndpointStore;
import io.siggi.healthchecker.history.HistoryEvent;
import io.siggi.healthchecker.history.HistoryStore;
import io.siggi.healthchecker.notify.DiscordNotifier;
import io.siggi.healthchecker.notify.FakeWebhook;
import io.siggi.healthchecker.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The daemon sends down and recovered notifications to a Discord channel (a fake webhook) and tracks reminders. */
class DaemonNotificationsTest {
    @TempDir
    Path dataDir;

    private final AtomicBoolean up = new AtomicBoolean(false);
    private FakeWebhook webhook;
    private PluginManager plugins;
    private Daemon daemon;

    /** Passes while {@link #up} is set. */
    final class TogglePlugin implements CheckPlugin {
        @Override
        public String type() {
            return "toggle";
        }

        @Override
        public List<SettingSpec> settings() {
            return List.of();
        }

        @Override
        public Checker create(EndpointConfig config) throws ConfigException {
            return timeout -> up.get() ? CheckResult.ok("ok") : CheckResult.fail("refused");
        }
    }

    @AfterEach
    void stop() {
        if (daemon != null) daemon.close();
        if (plugins != null) plugins.close();
        if (webhook != null) webhook.close();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(20);
        }
    }

    @Test
    void downAndRecoveredGoToDiscord() throws Exception {
        webhook = new FakeWebhook();
        Config config = new ConfigLoader(name -> name.equals("HOOK") ? webhook.url().toString() : null,
                ZoneId.of("UTC")).load("""
                { "server": { "port": %d, "bind": "127.0.0.1", "publicBaseUrl": "https://status.example.com" },
                  "dataDir": %s, "timezone": "UTC",
                  "notifications": { "batchSeconds": 0, "channels": {
                    "discord": { "type": "discord", "webhookUrl": "${env:HOOK}", "mention": "<@&77>" } } },
                  "defaults": { "notify": ["discord"] },
                  "endpoints": [ { "id": "svc", "name": "Service", "type": "toggle", "intervalSeconds": 1 } ] }
                """.formatted(freePort(), new Gson().toJson(dataDir.toString())));
        plugins = PluginManager.load(List.of(new TogglePlugin()), null, getClass().getClassLoader());
        daemon = Daemon.start(config, plugins, EndpointStore.load(config, plugins::validate), HistoryStore.open(dataDir));

        await(() -> webhook.posts().size() >= 1, "the down notification");
        HistoryEvent outage = daemon.history().openEvent("svc").orElseThrow();
        JsonObject down = webhook.posts().getFirst().json();
        assertEquals("<@&77>", down.get("content").getAsString());
        JsonObject embed = down.getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals("Service is down", embed.get("title").getAsString());
        assertEquals(DiscordNotifier.RED, embed.get("color").getAsInt());
        assertEquals("https://status.example.com/service/svc/event/" + outage.id(), embed.get("url").getAsString());
        assertTrue(embed.get("description").getAsString().contains("Latest failure: refused"));
        assertEquals(List.of("svc"), daemon.reminders().pendingEndpoints());

        up.set(true);
        await(() -> webhook.posts().size() >= 2, "the recovery notification");
        JsonObject recovered = webhook.posts().get(1).json();
        assertFalse(recovered.has("content"), "recoveries don't ping");
        JsonObject r = recovered.getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals("Service recovered", r.get("title").getAsString());
        assertEquals("https://status.example.com/service/svc/event/" + outage.id(), r.get("url").getAsString());
        assertEquals(List.of(), daemon.reminders().pendingEndpoints());
        assertEquals(2, webhook.posts().size());
    }
}
