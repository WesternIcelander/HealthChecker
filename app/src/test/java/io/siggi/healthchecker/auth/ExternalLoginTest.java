package io.siggi.healthchecker.auth;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pending sign-in kept between start and callback (the flows themselves are tested against fake providers). */
class ExternalLoginTest {
    private static final ProviderConfig OAUTH2 = new ProviderConfig("gh", ProviderConfig.Type.OAUTH2, "GitHub",
            "client", null, List.of(), List.of(), null, null, "https://127.0.0.1:1/authorize",
            "https://127.0.0.1:1/token", "https://127.0.0.1:1/user", "id", null, true);
    private static final ProviderConfig OTHER = new ProviderConfig("other", ProviderConfig.Type.OAUTH2, "Other",
            "client", null, List.of(), List.of(), null, null, "https://127.0.0.1:1/authorize",
            "https://127.0.0.1:1/token", "https://127.0.0.1:1/user", "id", null, true);

    @Test
    void pendingSurvivesAsAString() throws Exception {
        ExternalLogin login = ExternalLogin.standard();
        ExternalLogin.Started started = login.start(OAUTH2, "https://status.example.com/auth/callback/gh");
        assertTrue(started.authorizationUrl().toString().startsWith("https://127.0.0.1:1/authorize?"));
        // The pending sign-in is read back: the state is checked against it (before any request is made).
        assertEquals("state mismatch", assertThrows(OidcException.class,
                () -> login.finish(OAUTH2, started.pending(), Map.of("state", "wrong", "code", "c"))).getMessage());
        assertEquals("callback has no code", assertThrows(OidcException.class,
                () -> login.finish(OAUTH2, started.pending(), Map.of("state", state(started)))).getMessage());
    }

    @Test
    void missingOrForeignPendingIsRejected() throws Exception {
        ExternalLogin login = ExternalLogin.standard();
        ExternalLogin.Started started = login.start(OAUTH2, "https://status.example.com/auth/callback/gh");
        for (String pending : new String[]{null, "", "garbage", "[]", "{\"type\":\"oauth2\"}"}) {
            assertEquals("no sign-in in progress for provider gh", assertThrows(OidcException.class,
                    () -> login.finish(OAUTH2, pending, Map.of("state", "s", "code", "c"))).getMessage());
        }
        assertEquals("no sign-in in progress for provider other", assertThrows(OidcException.class,
                () -> login.finish(OTHER, started.pending(), Map.of("state", state(started), "code", "c")))
                .getMessage());
    }

    private static String state(ExternalLogin.Started started) {
        for (String part : started.authorizationUrl().getRawQuery().split("&")) {
            if (part.startsWith("state=")) return part.substring("state=".length());
        }
        throw new AssertionError("no state");
    }
}
