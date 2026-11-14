package io.siggi.healthchecker.config;

import io.siggi.healthchecker.auth.AuthProviders;
import io.siggi.healthchecker.auth.LocalLogin;
import io.siggi.healthchecker.auth.PasswordHasher;
import io.siggi.healthchecker.auth.UserDirectory;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code config.example.json} in the repository root must always load cleanly. */
class ExampleConfigTest {
    private static final Pattern ENV_REF = Pattern.compile("\\$\\{env:([A-Za-z_][A-Za-z0-9_]*)}");

    private static Path exampleConfig() {
        // Surefire runs with the module directory (app/) as working directory.
        for (Path candidate : new Path[]{Path.of("..", "config.example.json"), Path.of("config.example.json")}) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("config.example.json not found from " + Path.of("").toAbsolutePath());
    }

    @Test
    void exampleConfigLoadsWithoutErrors() throws Exception {
        Path file = exampleConfig();
        String text = Files.readString(file);
        Matcher m = ENV_REF.matcher(text);
        assertTrue(m.find(), "the example should show ${env:…} placeholders for secrets");

        // Every referenced variable is set to a dummy value; nothing comes from the real environment.
        Config config = new ConfigLoader(name -> "dummy-" + name, ZoneId.of("UTC")).load(file);

        assertTrue(config.warnings().isEmpty(), "warnings: " + config.warnings());
        assertEquals(4, config.endpoints().size());
        assertEquals(3, config.categories().size());
        assertEquals(2, config.sites().size());
        assertEquals(ZoneId.of("Atlantic/Reykjavik"), config.timezone());
        assertFalse(config.notificationChannels().isEmpty());

        // Users and roles (validated by the auth module at startup), with a real hash for the password variable.
        String hash = new PasswordHasher().hash("example".toCharArray());
        config = new ConfigLoader(name -> name.equals("ADMIN_PASSWORD_HASH") ? hash : "dummy-" + name,
                ZoneId.of("UTC")).load(file);
        List<String> errors = new ArrayList<>();
        UserDirectory users = UserDirectory.parse(config.auth(), errors);
        LocalLogin.enabled(config.auth(), errors);
        List<String> warnings = new ArrayList<>();
        AuthProviders providers = AuthProviders.parse(config.auth(), config.server(), config.sites(), errors, warnings);
        assertEquals(List.of(), errors);
        assertEquals(List.of(), warnings);
        assertEquals(2, providers.providers().size());
        assertFalse(users.users().isEmpty());
        assertTrue(users.roles().containsKey("ops"));
    }
}
