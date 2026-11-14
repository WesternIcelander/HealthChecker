package io.siggi.healthchecker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {
    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

    private int execute(String... args) {
        return Main.execute(args, out, err);
    }

    @Test
    void runLoadsTheGivenConfig(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("my-config.json");
        Files.writeString(config, "{}");
        assertEquals(Main.EXIT_OK, execute("run", config.toString()), errBytes.toString(StandardCharsets.UTF_8));
        assertEquals(Main.EXIT_OK, execute(config.toString()), errBytes.toString(StandardCharsets.UTF_8));
        assertTrue(outBytes.toString(StandardCharsets.UTF_8).contains("0 endpoint(s)"));
    }

    @Test
    void runReportsConfigErrors(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("bad.json");
        Files.writeString(config, "{\"timezone\": \"Nowhere/Nothing\", \"nope\": 1}");
        assertEquals(Main.EXIT_ERROR, execute("run", config.toString()));
        String errText = errBytes.toString(StandardCharsets.UTF_8);
        assertTrue(errText.contains("2 configuration errors"), errText);
    }

    @Test
    void runReportsUnknownEndpointTypes(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("config.json");
        String pluginsDir = dir.resolve("plugins").toString().replace("\\", "\\\\");
        Files.writeString(config, """
                { "plugins": { "dir": "%s" },
                  "endpoints": [ { "id": "survival", "name": "Survival", "type": "minecraft", "host": "x" } ] }
                """.formatted(pluginsDir));
        assertEquals(Main.EXIT_ERROR, execute("run", config.toString()));
        String errText = errBytes.toString(StandardCharsets.UTF_8);
        assertTrue(errText.contains("1 configuration error:"), errText);
        assertTrue(errText.contains("endpoints[0] \"survival\": unknown type \"minecraft\" (available: "), errText);
    }

    @Test
    void runReportsMissingFile(@TempDir Path dir) {
        assertEquals(Main.EXIT_ERROR, execute("run", dir.resolve("missing.json").toString()));
        assertTrue(errBytes.toString(StandardCharsets.UTF_8).contains("file not found"));
    }

    @Test
    void badArgumentsPrintUsage() {
        assertEquals(Main.EXIT_USAGE, execute("run", "a.json", "b.json"));
        assertEquals(Main.EXIT_USAGE, execute("hash-password", "extra"));
        assertEquals(Main.EXIT_USAGE, execute("--bogus"));
        assertTrue(errBytes.toString(StandardCharsets.UTF_8).contains("Usage:"));
    }

    @Test
    void hashPasswordWithoutConsoleFails() {
        assertEquals(Main.EXIT_ERROR, Main.hashPassword(null, out, err));
        assertTrue(errBytes.toString(StandardCharsets.UTF_8).contains("interactive console"));
    }
}
