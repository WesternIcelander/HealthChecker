package io.siggi.healthchecker.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.healthchecker.config.ServerConfig;
import io.siggi.healthchecker.config.SiteConfig;
import io.siggi.healthchecker.config.UnknownHostsPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthProvidersTest {
    private static final ServerConfig SERVER = server("https://status.example.com", UnknownHostsPolicy.DEFAULT);
    private static final String GOOGLE = """
            {"id": "google", "type": "oidc", "label": "Google", "issuer": "https://accounts.google.com",
             "clientId": "cid", "clientSecret": "secret"}""";
    private static final String GITHUB = """
            {"id": "github", "type": "oauth2", "label": "GitHub",
             "authorizeUrl": "https://github.com/login/oauth/authorize",
             "tokenUrl": "https://github.com/login/oauth/access_token",
             "userinfoUrl": "https://api.github.com/user",
             "scopes": ["read:user"], "idField": "id", "nameField": "login",
             "clientId": "cid", "clientSecret": "secret"}""";

    private static ServerConfig server(String baseUrl, UnknownHostsPolicy policy) {
        return new ServerConfig(8080, "0.0.0.0", baseUrl, List.of(), policy);
    }

    private static SiteConfig site(String host, String baseUrl) {
        return new SiteConfig(List.of(host), null, baseUrl, List.of(), List.of());
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static JsonObject auth(String... providers) {
        return json("{\"providers\": [" + String.join(",", providers) + "],"
                + " \"rules\": [{\"provider\": \"google\"}, {\"provider\": \"github\"}, {\"provider\": \"p\"}]}");
    }

    private record Result(AuthProviders providers, List<String> errors, List<String> warnings) {
    }

    private static Result parse(JsonObject auth, ServerConfig server, List<SiteConfig> sites) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        AuthProviders providers = AuthProviders.parse(auth, server, sites, errors, warnings);
        return new Result(providers, errors, warnings);
    }

    private static List<String> errors(String provider) {
        return parse(auth(provider), SERVER, List.of()).errors();
    }

    private static void assertHasError(List<String> errors, String expected) {
        assertTrue(errors.stream().anyMatch(e -> e.startsWith(expected)), "expected " + expected + " in " + errors);
    }

    private static ProviderConfig provider(String json) {
        return AuthProviders.parse(auth(json), SERVER, List.of()).providers().iterator().next();
    }

    // ---- config validation ----

    @Test
    void validProvidersParseWithDefaults() {
        Result r = parse(auth(GOOGLE, GITHUB), SERVER, List.of());
        assertEquals(List.of(), r.errors());
        assertEquals(List.of(), r.warnings());

        ProviderConfig google = r.providers().provider("google").orElseThrow();
        assertEquals(ProviderConfig.Type.OIDC, google.type());
        assertEquals("Google", google.label());
        assertEquals("https://accounts.google.com", google.issuer());
        assertEquals("sub", google.identifyBy());
        assertEquals(List.of("openid", "email", "profile"), google.scopes());
        assertEquals("secret", google.clientSecret());
        assertTrue(google.pkce());
        assertNull(google.idField());

        ProviderConfig github = r.providers().provider("github").orElseThrow();
        assertEquals(ProviderConfig.Type.OAUTH2, github.type());
        assertEquals("id", github.idField());
        assertEquals("login", github.nameField());
        assertEquals(List.of("read:user"), github.scopes());
        assertEquals("https://api.github.com/user", github.userinfoUrl());
        assertTrue(github.pkce());
        assertNull(github.issuer());
        assertNull(github.identifyBy());

        assertEquals(List.of("google", "github"),
                r.providers().providers().stream().map(ProviderConfig::id).toList());
    }

    @Test
    void noProvidersIsFine() {
        assertTrue(parse(null, SERVER, List.of()).providers().isEmpty());
        assertTrue(parse(json("{}"), server(null, UnknownHostsPolicy.DEFAULT), List.of()).errors().isEmpty());
    }

    @Test
    void optionalSettings() {
        ProviderConfig p = provider("""
                {"id": "p", "type": "oauth2", "authorizeUrl": "https://a.example/auth?x=1",
                 "tokenUrl": "https://a.example/token", "userinfoUrl": "https://a.example/me",
                 "idField": "user_id", "pkce": false, "clientId": "c"}""");
        assertEquals("p", p.label());
        assertNull(p.clientSecret());
        assertEquals(List.of(), p.scopes());
        assertFalse(p.pkce());
        assertNull(p.nameField());
        assertEquals("https://a.example/auth?x=1", p.authorizeUrl());
    }

    @Test
    void structuralErrors() {
        assertHasError(parse(json("{\"providers\": {}}"), SERVER, List.of()).errors(),
                "auth.providers must be an array");
        assertHasError(parse(json("{\"providers\": [1]}"), SERVER, List.of()).errors(),
                "auth.providers[0] must be an object");
        assertHasError(errors("{\"type\": \"oidc\"}"), "auth.providers[0]: id must match");
        assertHasError(errors("{\"id\": \"Goo:gle\", \"type\": \"oidc\"}"), "auth.providers[0]: id must match");
        assertHasError(errors("{\"id\": \"x\", \"type\": \"saml\"}"),
                "auth.providers[0] \"x\": type must be \"oidc\" or \"oauth2\"");
        assertHasError(errors("{\"id\": \"x\"}"), "auth.providers[0] \"x\": type must be");
        assertHasError(parse(auth(GOOGLE, GOOGLE), SERVER, List.of()).errors(),
                "auth.providers[1] \"google\": duplicate provider id");
    }

    @Test
    void unknownKeysDependOnType() {
        assertHasError(errors(GOOGLE.replace("\"clientId\"", "\"idField\": \"id\", \"clientId\"")),
                "auth.providers[0] \"google\": unknown key \"idField\" for type \"oidc\"");
        assertHasError(errors(GOOGLE.replace("\"clientId\"", "\"pkce\": false, \"clientId\"")),
                "auth.providers[0] \"google\": unknown key \"pkce\"");
        assertHasError(errors(GITHUB.replace("\"idField\"", "\"identifyBy\": \"sub\", \"idField\"")),
                "auth.providers[0] \"github\": unknown key \"identifyBy\" for type \"oauth2\"");
        assertHasError(errors(GITHUB.replace("\"idField\"", "\"issuer\": \"https://x\", \"idField\"")),
                "auth.providers[0] \"github\": unknown key \"issuer\"");
        assertHasError(errors(GOOGLE.replace("\"clientId\"", "\"colour\": 1, \"clientId\"")),
                "auth.providers[0] \"google\": unknown key \"colour\"");
    }

    @Test
    void requiredSettings() {
        String p = "auth.providers[0] \"google\": ";
        assertHasError(errors(GOOGLE.replace("\"clientId\": \"cid\",", "")), p + "clientId must be a non-empty string");
        assertHasError(errors(GOOGLE.replace("\"cid\"", "\"\"")), p + "clientId must be a non-empty string");
        assertHasError(errors(GOOGLE.replace("\"secret\"", "\"\"")), p + "clientSecret must be a non-empty string");
        assertHasError(errors(GOOGLE.replace("\"issuer\": \"https://accounts.google.com\",", "")),
                p + "issuer must be a URL");
        assertHasError(errors(GOOGLE.replace("\"Google\"", "\"  \"")), p + "label must be a non-empty string");

        String g = "auth.providers[0] \"github\": ";
        assertHasError(errors(GITHUB.replace("\"idField\": \"id\",", "")), g + "idField must be a claim or field name");
        assertHasError(errors(GITHUB.replace("\"idField\": \"id\"", "\"idField\": \"user id\"")),
                g + "idField must be a claim or field name");
        assertHasError(errors(GITHUB.replace("\"login\"", "3")), g + "nameField must be a claim or field name");
        assertHasError(errors(GITHUB.replace("\"tokenUrl\": \"https://github.com/login/oauth/access_token\",", "")),
                g + "tokenUrl must be a URL");
        assertHasError(errors(GITHUB.replace("\"nameField\"", "\"pkce\": \"yes\", \"nameField\"")),
                g + "pkce must be true or false");
    }

    @Test
    void urlsMustBeHttpsExceptLoopback() {
        String p = "auth.providers[0] \"google\": issuer ";
        assertHasError(errors(GOOGLE.replace("https://accounts", "http://accounts")), p + "must use https");
        assertHasError(errors(GOOGLE.replace("https://accounts.google.com", "ftp://x.example")),
                p + "must be an absolute https URL");
        assertHasError(errors(GOOGLE.replace("https://accounts.google.com", "/relative")),
                p + "must be an absolute https URL");
        assertHasError(errors(GOOGLE.replace("https://accounts.google.com", "https://u:pw@x.example")),
                p + "must not contain credentials");
        assertHasError(errors(GOOGLE.replace("accounts.google.com", "x.example?a=b")), p + "must not have a query");
        assertHasError(errors(GOOGLE.replace("accounts.google.com", "x.example#f")), p + "must not have a query");
        assertHasError(errors(GOOGLE.replace("https://accounts", "http://accounts.127.0.0.1.nip.io/")),
                p + "must use https");
        assertHasError(errors(GITHUB.replace("https://api.github.com/user", "https://api.github.com/user#x")),
                "auth.providers[0] \"github\": userinfoUrl must not have a fragment");

        for (String local : new String[]{"http://localhost:1234", "http://127.0.0.1:8080/realm",
                "http://[::1]:9000", "http://auth.localhost"}) {
            assertEquals(List.of(), errors(GOOGLE.replace("https://accounts.google.com", local)), local);
        }
        // The issuer is kept exactly as written: it's what iss must equal.
        assertEquals("https://accounts.google.com/",
                provider(GOOGLE.replace("google.com\"", "google.com/\"")).issuer());
    }

    @Test
    void scopes() {
        String p = "auth.providers[0] \"google\": ";
        assertHasError(errors(GOOGLE.replace("\"clientId\"", "\"scopes\": [\"email\"], \"clientId\"")),
                p + "scopes must include \"openid\"");
        assertHasError(errors(GOOGLE.replace("\"clientId\"", "\"scopes\": [\"open id\"], \"clientId\"")),
                p + "scopes must be an array of scope names");
        assertHasError(errors(GOOGLE.replace("\"clientId\"", "\"scopes\": \"openid\", \"clientId\"")),
                p + "scopes must be an array of scope names");
        assertEquals(List.of("openid", "groups"),
                provider(GOOGLE.replace("\"clientId\"", "\"scopes\": [\"openid\", \"groups\", \"openid\"], \"clientId\""))
                        .scopes());
    }

    @Test
    void identifyByModes() {
        assertEquals("email", provider(GOOGLE.replace("\"clientId\"", "\"identifyBy\": \"email\", \"clientId\""))
                .identifyBy());
        assertEquals("preferred_username",
                provider(GOOGLE.replace("\"clientId\"", "\"identifyBy\": \"preferred_username\", \"clientId\""))
                        .identifyBy());
        assertHasError(errors(GOOGLE.replace("\"clientId\"", "\"identifyBy\": \"\", \"clientId\"")),
                "auth.providers[0] \"google\": identifyBy must be a claim or field name");
        assertHasError(errors(GOOGLE.replace("\"clientId\"", "\"identifyBy\": true, \"clientId\"")),
                "auth.providers[0] \"google\": identifyBy must be a claim or field name");
    }

    @Test
    void sitesMustNameConfiguredSiteHosts() {
        List<SiteConfig> sites = List.of(site("mc-status.example.com", "https://mc-status.example.com"),
                site("web.example.com", null));
        Result ok = parse(auth(GOOGLE.replace("\"clientId\"", "\"sites\": [\"MC-Status.example.com\"], \"clientId\"")),
                SERVER, sites);
        assertEquals(List.of(), ok.errors());
        ProviderConfig google = ok.providers().provider("google").orElseThrow();
        assertEquals(List.of("mc-status.example.com"), google.sites());
        assertTrue(google.availableOn(sites.get(0)));
        assertFalse(google.availableOn(sites.get(1)));
        assertFalse(google.availableOn(null));
        assertEquals(List.of(), ok.providers().availableOn(null));
        assertEquals(List.of(google), ok.providers().availableOn(sites.get(0)));

        ProviderConfig everywhere = provider(GITHUB);
        assertTrue(everywhere.availableOn(null));
        assertTrue(everywhere.availableOn(sites.get(1)));

        assertHasError(parse(auth(GOOGLE.replace("\"clientId\"", "\"sites\": [\"other.example.com\"], \"clientId\"")),
                SERVER, sites).errors(), "auth.providers[0] \"google\": sites[0]: \"other.example.com\" is not a host"
                + " of any configured site (available: mc-status.example.com, web.example.com)");
        assertHasError(parse(auth(GOOGLE.replace("\"clientId\"", "\"sites\": [], \"clientId\"")),
                SERVER, sites).errors(), "auth.providers[0] \"google\": sites must be a non-empty array");
    }

    @Test
    void callbackBaseUrlRequired() {
        ServerConfig noBase = server(null, UnknownHostsPolicy.DEFAULT);
        assertHasError(parse(auth(GOOGLE), noBase, List.of()).errors(),
                "auth.providers[0] \"google\": server.publicBaseUrl is required");

        // A site without its own publicBaseUrl falls back to the server's.
        List<SiteConfig> sites = List.of(site("a.example.com", "https://a.example.com"), site("b.example.com", null));
        assertEquals(List.of(), parse(auth(GOOGLE), SERVER, sites).errors());
        assertHasError(parse(auth(GOOGLE), noBase, sites).errors(),
                "auth.providers[0] \"google\": sites[1] (b.example.com) needs a publicBaseUrl");

        // Limited to a site that has one: no server URL needed.
        String limited = GOOGLE.replace("\"clientId\"", "\"sites\": [\"a.example.com\"], \"clientId\"");
        assertEquals(List.of(), parse(auth(limited), noBase, sites).errors());

        // With unknown hosts rejected there's no default site to sign in on.
        List<SiteConfig> withBase = List.of(site("a.example.com", "https://a.example.com"));
        assertEquals(List.of(), parse(auth(GOOGLE), server(null, UnknownHostsPolicy.REJECT), withBase).errors());
        assertHasError(parse(auth(GOOGLE), noBase, withBase).errors(),
                "auth.providers[0] \"google\": server.publicBaseUrl is required");
    }

    @Test
    void unusedProviderIsAWarning() {
        JsonObject auth = json("{\"providers\": [" + GOOGLE + "," + GITHUB + "],"
                + " \"users\": [{\"id\": \"a\", \"role\": \"admin\", \"identities\": [\"github:1\"]}]}");
        Result r = parse(auth, SERVER, List.of());
        assertEquals(List.of(), r.errors());
        assertEquals(1, r.warnings().size(), r.warnings().toString());
        assertTrue(r.warnings().get(0).startsWith("auth.providers[0] \"google\": no user identity or rule"));
        assertEquals(2, r.providers().providers().size());
    }

    @Test
    void throwingParse() {
        assertThrows(IllegalArgumentException.class,
                () -> AuthProviders.parse(auth("{\"id\": \"x\"}"), SERVER, List.of()));
        assertEquals(1, AuthProviders.parse(auth(GOOGLE), SERVER, List.of()).providers().size());
    }

    // ---- identifier construction ----

    @Test
    void oidcSubIsTheDefault() {
        ProviderConfig google = provider(GOOGLE);
        assertEquals(Optional.of("google:110248495921238986420"),
                google.identify(json("{\"sub\": \"110248495921238986420\", \"email\": \"jane@example.com\","
                        + " \"email_verified\": true}")));
        assertEquals(Optional.empty(), google.identify(json("{\"email\": \"jane@example.com\"}")));
        assertEquals(Optional.empty(), google.identify(json("{\"sub\": \"\"}")));
        assertEquals(Optional.empty(), google.identify(json("{\"sub\": {\"x\": 1}}")));
        assertEquals(Optional.empty(), google.identify(null));
        // The value may itself contain a colon; only the first one separates the provider.
        assertEquals(Optional.of("google:a:b"), google.identify(json("{\"sub\": \"a:b\"}")));
    }

    @Test
    void oidcEmailOnlyWhenVerified() {
        ProviderConfig p = provider(GOOGLE.replace("\"clientId\"", "\"identifyBy\": \"email\", \"clientId\""));
        assertEquals(Optional.of("google:jane@example.com"),
                p.identify(json("{\"sub\": \"1\", \"email\": \"jane@example.com\", \"email_verified\": true}")));
        assertEquals(Optional.of("google:jane@example.com"),
                p.identify(json("{\"email\": \"jane@example.com\", \"email_verified\": \"true\"}")));
        assertEquals(Optional.empty(),
                p.identify(json("{\"sub\": \"1\", \"email\": \"jane@example.com\", \"email_verified\": false}")));
        assertEquals(Optional.empty(), p.identify(json("{\"sub\": \"1\", \"email\": \"jane@example.com\"}")));
        assertEquals(Optional.empty(),
                p.identify(json("{\"email\": \"jane@example.com\", \"email_verified\": \"yes\"}")));
        assertEquals(Optional.empty(), p.identify(json("{\"email\": \"jane@example.com\", \"email_verified\": 1}")));
        assertEquals(Optional.empty(), p.identify(json("{\"sub\": \"1\", \"email_verified\": true}")));
    }

    @Test
    void oidcOtherClaim() {
        ProviderConfig p = provider(GOOGLE.replace("\"clientId\"", "\"identifyBy\": \"oid\", \"clientId\""));
        assertEquals(Optional.of("google:00000000-1111"), p.identify(json("{\"sub\": \"x\", \"oid\": \"00000000-1111\"}")));
        assertEquals(Optional.empty(), p.identify(json("{\"sub\": \"x\"}")));
        // Another claim isn't subject to email_verified.
        ProviderConfig name = provider(GOOGLE.replace("\"clientId\"", "\"identifyBy\": \"preferred_username\", \"clientId\""));
        assertEquals(Optional.of("google:jane"), name.identify(json("{\"preferred_username\": \"jane\"}")));
    }

    @Test
    void oauth2IdField() {
        ProviderConfig github = provider(GITHUB);
        assertEquals(Optional.of("github:123456"), github.identify(json("{\"id\": 123456, \"login\": \"jane\"}")));
        assertEquals(Optional.of("github:123456"), github.identify(json("{\"id\": \"123456\"}")));
        assertEquals(Optional.of("github:98765432109876543210"),
                github.identify(json("{\"id\": 98765432109876543210}")));
        assertEquals(Optional.empty(), github.identify(json("{\"id\": 1.5}")));
        assertEquals(Optional.empty(), github.identify(json("{\"id\": true}")));
        assertEquals(Optional.empty(), github.identify(json("{\"id\": null}")));
        assertEquals(Optional.empty(), github.identify(json("{\"login\": \"jane\"}")));
        // sub and email aren't used for oauth2.
        assertEquals(Optional.empty(), github.identify(json("{\"sub\": \"1\", \"email\": \"a@b\", \"email_verified\": true}")));
        assertEquals(Optional.of("jane"), github.displayName(json("{\"id\": 1, \"login\": \"jane\"}")));
        assertEquals(Optional.empty(), github.displayName(json("{\"id\": 1}")));
    }

    @Test
    void unusableValuesAreRejected() {
        ProviderConfig github = provider(GITHUB);
        assertEquals(Optional.empty(), github.identify(json("{\"id\": \"a\\nb\"}")));
        assertEquals(Optional.empty(), github.identify(json("{\"id\": \"" + "x".repeat(513) + "\"}")));
        assertEquals(Optional.of("github:" + "x".repeat(512)), github.identify(json("{\"id\": \"" + "x".repeat(512) + "\"}")));
        assertEquals("github:42", github.identifier("42"));
        assertThrows(IllegalArgumentException.class, () -> github.identifier(""));
        assertThrows(IllegalArgumentException.class, () -> github.identifier("a\u0000"));
    }
}
