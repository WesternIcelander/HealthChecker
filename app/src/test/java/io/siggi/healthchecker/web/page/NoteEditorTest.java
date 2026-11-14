package io.siggi.healthchecker.web.page;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.healthchecker.api.CheckResult;
import io.siggi.healthchecker.auth.Permission;
import io.siggi.healthchecker.auth.Permissions;
import io.siggi.healthchecker.auth.Role;
import io.siggi.healthchecker.auth.SessionManager;
import io.siggi.healthchecker.auth.User;
import io.siggi.healthchecker.auth.UserDirectory;
import io.siggi.healthchecker.config.CategoryConfig;
import io.siggi.healthchecker.config.DashboardConfig;
import io.siggi.healthchecker.config.EndpointDefinition;
import io.siggi.healthchecker.config.ServerConfig;
import io.siggi.healthchecker.config.UnknownHostsPolicy;
import io.siggi.healthchecker.config.Visibility;
import io.siggi.healthchecker.history.HistoryStore;
import io.siggi.healthchecker.history.UptimeCalculator;
import io.siggi.healthchecker.plugin.JsonEndpointConfig.Target;
import io.siggi.healthchecker.schedule.Endpoint;
import io.siggi.healthchecker.web.WebServer;
import io.siggi.healthchecker.web.layout.ResolvedLayout;
import io.siggi.healthchecker.web.site.SiteResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Event notes ({@code POST /service/{id}/event/{eventId}/notes}, {@code .../note/{noteId}} and
 * {@code .../note/{noteId}/delete}), end to end over HTTP.
 */
class NoteEditorTest {
    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Pattern LOGOUT_CSRF = Pattern.compile("name=\"csrf\" value=\"([0-9a-f]{64})\"");
    private static final String EVENT = "11111111-2222-3333-4444-555555555555";
    private static final String PRIVATE_EVENT = "66666666-7777-8888-9999-000000000000";
    private static final Role ANNOTATE_ONLY = new Role("annotator", EnumSet.of(Permission.EVENTS_ANNOTATE));

    @TempDir
    Path dataDir;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final Map<String, Endpoint> endpoints = new LinkedHashMap<>();
    private final List<CategoryConfig> categories = List.of(new CategoryConfig("web", "Websites", null));
    private WebServer web;
    private HistoryStore history;

    private void add(String id, Visibility visibility) {
        EndpointDefinition def = new EndpointDefinition(id, "Name " + id, "http", "web", visibility, 60, 5000, 1,
                List.of(), List.of(), 0, new JsonObject());
        endpoints.put(id, new Endpoint(def, List.of(new Target("url", "https://" + id + ".example.com")),
                timeout -> CheckResult.ok("ok")));
    }

    private void writeEvent(String endpointId, String eventId) throws Exception {
        Path dir = dataDir.resolve("endpoints").resolve(endpointId);
        Files.createDirectories(dir.resolve("events"));
        Files.writeString(dir.resolve("coverage.json"),
                "[{\"start\":\"2026-11-01T00:00:00Z\",\"end\":\"" + NOW + "\"}]\n");
        JsonObject o = new JsonObject();
        o.addProperty("id", eventId);
        o.addProperty("endpointId", endpointId);
        o.addProperty("kind", "outage");
        o.addProperty("start", "2026-11-13T10:00:00Z");
        o.addProperty("end", "2026-11-13T10:30:00Z");
        o.addProperty("firstFailureMessage", "fail");
        o.addProperty("lastFailureMessage", "fail");
        o.addProperty("lastFailureAt", "2026-11-13T10:29:00Z");
        o.add("notes", new JsonArray());
        o.addProperty("endedUnobserved", false);
        o.addProperty("endedByConfigChange", false);
        Files.writeString(dir.resolve("events/2026.jsonl"), o + "\n");
    }

    @BeforeEach
    void start() throws Exception {
        add("website", Visibility.PUBLIC);
        add("intranet", Visibility.PRIVATE);
        writeEvent("website", EVENT);
        writeEvent("intranet", PRIVATE_EVENT);
        startServer();
    }

    private void startServer() throws Exception {
        List<EndpointDefinition> defs = endpoints.values().stream().map(Endpoint::definition).toList();
        UserDirectory users = UserDirectory.of(List.of(
                new User("ed", "Ed", Role.EDITOR, null, List.of(), false),
                new User("vic", "Vic", Role.VIEWER, null, List.of(), false),
                new User("ann", "Ann", ANNOTATE_ONLY, null, List.of(), false)));
        web = new WebServer(new ServerConfig(0, "127.0.0.1", null, List.of(), UnknownHostsPolicy.DEFAULT));
        SessionManager sessions = new SessionManager(dataDir.resolve("sessions"), Duration.ofDays(7),
                web.trustedProxies());
        Permissions permissions = new Permissions(sessions, users::user);
        SiteResolver sites = new SiteResolver(List.of(), "Status", null, UnknownHostsPolicy.DEFAULT,
                web.trustedProxies());
        history = HistoryStore.open(dataDir, CLOCK, System.getLogger(NoteEditorTest.class.getName()));
        UptimeCalculator uptime = new UptimeCalculator(history, ZoneOffset.UTC, false, CLOCK);
        web.register("/test-login", request -> {
            sessions.login(request, request.get().get("user"));
            PageSupport.write(request, "200 OK", "text/plain; charset=utf-8", "ok");
        });
        new ServicePage(sites, () -> ResolvedLayout.resolve(null, categories, defs),
                id -> Optional.ofNullable(endpoints.get(id)), id -> Optional.empty(),
                permissions::viewer, uptime, history, CLOCK, new DashboardConfig("Status", 100.0, 99.0, 95.0),
                new EventEditor(permissions, sessions, history)).register(web);
        web.start();
    }

    private void restart() throws Exception {
        web.close();
        history.close();
        startServer();
    }

    @AfterEach
    void stop() {
        if (web != null) web.close();
        if (history != null) history.close();
    }

    // ---- HTTP helpers ----

    record Session(String cookie, String csrf) {
    }

    private HttpResponse<String> get(String path, Session session) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web.port() + path))
                .timeout(Duration.ofSeconds(10));
        if (session != null) b.header("Cookie", session.cookie());
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Map<String, String> fields, Session session) throws Exception {
        StringBuilder form = new StringBuilder();
        for (Map.Entry<String, String> f : fields.entrySet()) {
            if (f.getValue() == null) continue;
            if (form.length() > 0) form.append('&');
            form.append(f.getKey()).append('=').append(URLEncoder.encode(f.getValue(), StandardCharsets.UTF_8));
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web.port() + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString()));
        if (session != null) b.header("Cookie", session.cookie());
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, String> fields(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static String base(String service, String event) {
        return "/service/" + service + "/event/" + event;
    }

    /** Adds a note to the website event and returns its id (from the redirect's fragment). */
    private String addNote(Session s, String text, String visibility) throws Exception {
        HttpResponse<String> r = post(base("website", EVENT) + "/notes",
                fields("text", text, "visibility", visibility, "csrf", s.csrf()), s);
        assertEquals(303, r.statusCode(), r.body());
        String location = r.headers().firstValue("Location").orElseThrow();
        assertTrue(location.startsWith(base("website", EVENT) + "#note-"), location);
        return location.substring(location.indexOf("#note-") + 6);
    }

    private HttpResponse<String> editNote(Session s, String noteId, String text, String visibility) throws Exception {
        return post(base("website", EVENT) + "/note/" + noteId,
                fields("text", text, "visibility", visibility, "csrf", s.csrf()), s);
    }

    private HttpResponse<String> deleteNote(Session s, String noteId) throws Exception {
        return post(base("website", EVENT) + "/note/" + noteId + "/delete", fields("csrf", s.csrf()), s);
    }

    private Session login(String user) throws Exception {
        HttpResponse<String> r = get("/test-login?user=" + user, null);
        assertEquals(200, r.statusCode());
        String cookie = null;
        for (String c : r.headers().allValues("Set-Cookie")) {
            if (c.startsWith("SessID=")) cookie = c.substring(0, c.indexOf(';'));
        }
        assertNotNull(cookie);
        Matcher m = LOGOUT_CSRF.matcher(get("/service/website", new Session(cookie, null)).body());
        assertTrue(m.find());
        return new Session(cookie, m.group(1));
    }

    /** The notes of the website event in the anonymous JSON. */
    private JsonArray apiNotes() throws Exception {
        HttpResponse<String> r = get("/api/service/website/event/" + EVENT, null);
        assertEquals(200, r.statusCode());
        return JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonArray("notes");
    }

    private JsonArray storedNotes() throws Exception {
        String line = Files.readString(dataDir.resolve("endpoints/website/events/2026.jsonl")).strip();
        return JsonParser.parseString(line).getAsJsonObject().getAsJsonArray("notes");
    }

    // ---- tests ----

    @Test
    void privateNoteHiddenFromAnonymousInHtmlAndJson() throws Exception {
        Session ed = login("ed");
        String publicId = addNote(ed, "Public words", "public");
        String privateId = addNote(ed, "Secret words", "private");

        List<String> pages = List.of("/service/website", base("website", EVENT), "/service/website/day/2026-11-13",
                "/service/website?day=2026-11-13");
        for (String path : pages) {
            String anon = get(path, null).body();
            assertTrue(anon.contains("Public words"), path);
            assertFalse(anon.contains("Secret words"), path);
            assertFalse(anon.contains(privateId), path);
            // Logged in without view.private: same as anonymous.
            String ann = get(path, login("ann")).body();
            assertTrue(ann.contains("Public words"), path);
            assertFalse(ann.contains("Secret words"), path);
            // With view.private: both, with badges.
            String full = get(path, ed).body();
            assertTrue(full.contains("Secret words") && full.contains("badge-private"), path);
        }
        for (String path : List.of("/api/service/website/event/" + EVENT, "/api/service/website/events")) {
            String json = get(path, null).body();
            assertTrue(json.contains("Public words"), path);
            assertFalse(json.contains("Secret words"), path);
            assertFalse(json.contains(privateId), path);
            assertFalse(json.contains("\"visibility\""), path);
        }
        JsonArray notes = apiNotes();
        assertEquals(1, notes.size());
        JsonObject note = notes.get(0).getAsJsonObject();
        assertEquals(publicId, note.get("id").getAsString());
        assertEquals("Ed", note.get("author").getAsString());
        assertEquals(NOW.toString(), note.get("createdAt").getAsString());
        assertFalse(note.has("editedAt"));
    }

    @Test
    void editsArePersistedAndSurviveRestart() throws Exception {
        Session ed = login("ed");
        String a = addNote(ed, "First", "public");
        String b = addNote(ed, "Second", "private");
        assertEquals(2, storedNotes().size()); // written before answering

        // Edit the text (keeps visibility when it's not sent).
        HttpResponse<String> r = editNote(ed, a, "First, edited", null);
        assertEquals(303, r.statusCode());
        assertEquals(base("website", EVENT) + "#note-" + a, r.headers().firstValue("Location").orElseThrow());
        // Toggle the private note to public (visibility only).
        assertEquals(303, editNote(ed, b, null, "public").statusCode());
        JsonArray stored = storedNotes();
        assertEquals("First, edited", stored.get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("public", stored.get(1).getAsJsonObject().get("visibility").getAsString());

        restart();
        JsonArray notes = apiNotes();
        assertEquals(2, notes.size());
        JsonObject first = notes.get(0).getAsJsonObject();
        assertEquals("First, edited", first.get("text").getAsString());
        assertEquals(NOW.toString(), first.get("editedAt").getAsString());
        JsonObject second = notes.get(1).getAsJsonObject();
        assertEquals("Second", second.get("text").getAsString());
        assertFalse(second.has("editedAt"), "a visibility toggle isn't a text edit");

        // Toggle back to private, delete the other one; also survives a restart.
        ed = login("ed");
        assertEquals(303, editNote(ed, b, null, "private").statusCode());
        r = deleteNote(ed, a);
        assertEquals(303, r.statusCode());
        assertEquals(base("website", EVENT), r.headers().firstValue("Location").orElseThrow());
        restart();
        assertEquals(0, apiNotes().size());
        stored = storedNotes();
        assertEquals(1, stored.size());
        assertEquals(b, stored.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("private", stored.get(0).getAsJsonObject().get("visibility").getAsString());
    }

    @Test
    void noteTextIsHtmlEscaped() throws Exception {
        Session ed = login("ed");
        String text = "<script>alert(\"x\")</script> & <b>bold</b>\nsecond line";
        addNote(ed, text, "public");
        String escaped = "&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt; &amp; &lt;b&gt;bold&lt;/b&gt;\nsecond line";
        for (String path : List.of("/service/website", base("website", EVENT), "/service/website/day/2026-11-13")) {
            String anon = get(path, null).body();
            assertTrue(anon.contains("<p class=\"note-text\">" + escaped + "</p>"), path);
            assertFalse(anon.contains("<script>alert"), path);
            assertFalse(anon.contains("<b>bold"), path);
            String full = get(path, ed).body();
            assertTrue(full.contains(">" + escaped + "</textarea>"), path); // the edit form
            assertFalse(full.contains("<script>alert"), path);
        }
        // JSON keeps the plain text.
        assertEquals(text, apiNotes().get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void lengthLimitAndValidation() throws Exception {
        Session ed = login("ed");
        String path = base("website", EVENT) + "/notes";
        assertEquals(400, post(path, fields("text", "x".repeat(10_001), "visibility", "public", "csrf", ed.csrf()), ed).statusCode());
        assertEquals(400, post(path, fields("text", "  \n ", "visibility", "public", "csrf", ed.csrf()), ed).statusCode());
        assertEquals(400, post(path, fields("text", "a\u0000b", "visibility", "public", "csrf", ed.csrf()), ed).statusCode());
        assertEquals(400, post(path, fields("text", "ok", "visibility", "everyone", "csrf", ed.csrf()), ed).statusCode());
        assertEquals(0, storedNotes().size());
        // Exactly the limit is fine, counted in characters (an emoji is one).
        String id = addNote(ed, "😀".repeat(10_000), "public");
        assertEquals(400, editNote(ed, id, "y".repeat(10_001), null).statusCode());
        assertEquals(400, editNote(ed, id, "", null).statusCode());
        assertEquals("😀".repeat(10_000), storedNotes().get(0).getAsJsonObject().get("text").getAsString());
        // Line breaks are normalised, surrounding whitespace removed, tabs kept.
        assertEquals(303, editNote(ed, id, "  a\r\nb\rc\td  ", null).statusCode());
        assertEquals("a\nb\nc\td", storedNotes().get(0).getAsJsonObject().get("text").getAsString());
        // Without a visibility, a new note is private.
        HttpResponse<String> r = post(path, fields("text", "no vis", "csrf", ed.csrf()), ed);
        assertEquals(303, r.statusCode());
        assertEquals("private", storedNotes().get(1).getAsJsonObject().get("visibility").getAsString());
        // The forms carry the limit.
        assertTrue(get("/service/website", ed).body().contains("maxlength=\"10000\""));
    }

    @Test
    void permissionsAndCsrf() throws Exception {
        Session ed = login("ed");
        String id = addNote(ed, "Note", "public");
        String privateId = addNote(ed, "Private note", "private");
        // No events.annotate: 403; anonymous: sent to sign in.
        Session vic = login("vic");
        assertEquals(403, post(base("website", EVENT) + "/notes", fields("text", "x", "csrf", vic.csrf()), vic).statusCode());
        assertEquals(403, editNote(vic, id, "x", null).statusCode());
        assertEquals(403, deleteNote(vic, id).statusCode());
        HttpResponse<String> anon = post(base("website", EVENT) + "/notes", fields("text", "x"), null);
        assertEquals(303, anon.statusCode());
        assertTrue(anon.headers().firstValue("Location").orElseThrow().startsWith("/login?next="));
        // CSRF.
        assertEquals(403, post(base("website", EVENT) + "/notes", fields("text", "x"), ed).statusCode());
        assertEquals(403, post(base("website", EVENT) + "/note/" + id, fields("text", "x", "csrf", "0".repeat(64)), ed).statusCode());
        assertEquals(403, post(base("website", EVENT) + "/note/" + id + "/delete", fields(), ed).statusCode());
        // GET isn't allowed.
        assertEquals(405, get(base("website", EVENT) + "/notes", ed).statusCode());
        assertEquals(2, storedNotes().size());
        assertEquals("Note", storedNotes().get(0).getAsJsonObject().get("text").getAsString());

        // events.annotate without view.private: may add and edit public notes, but private ones
        // (and private endpoints) don't exist for them.
        Session ann = login("ann");
        assertEquals(404, editNote(ann, privateId, "x", "public").statusCode());
        assertEquals(404, deleteNote(ann, privateId).statusCode());
        assertEquals(404, post(base("intranet", PRIVATE_EVENT) + "/notes", fields("text", "x", "csrf", ann.csrf()), ann).statusCode());
        assertEquals(404, editNote(ann, "unknown", "x", null).statusCode());
        assertEquals(303, editNote(ann, id, "Edited by Ann", null).statusCode());
        String annId = addNote(ann, "By Ann", "public");
        JsonArray stored = storedNotes();
        assertEquals("Private note", stored.get(1).getAsJsonObject().get("text").getAsString());
        assertEquals("Ed", stored.get(0).getAsJsonObject().get("author").getAsString(), "editing keeps the author");
        assertEquals("Ann", stored.get(2).getAsJsonObject().get("author").getAsString());
        assertEquals(annId, stored.get(2).getAsJsonObject().get("id").getAsString());
        // Ed may add notes to the private endpoint's events.
        assertEquals(303, post(base("intranet", PRIVATE_EVENT) + "/notes",
                fields("text", "x", "csrf", ed.csrf()), ed).statusCode());
    }

    @Test
    void formsInListEventPageAndDayPopover() throws Exception {
        Session ed = login("ed");
        String id = addNote(ed, "Note", "private");
        String action = "action=\"" + base("website", EVENT) + "/note/" + id + "\"";
        String addAction = "action=\"" + base("website", EVENT) + "/notes\"";
        Map<String, String> pages = Map.of(
                "/service/website", "/service/website?view=list&amp;page=0#event-" + EVENT,
                base("website", EVENT), base("website", EVENT),
                "/service/website/day/2026-11-13", "/service/website?day=2026-11-13",
                "/service/website?day=2026-11-13", "/service/website?day=2026-11-13");
        for (Map.Entry<String, String> p : pages.entrySet()) {
            String html = get(p.getKey(), ed).body();
            assertTrue(html.contains(addAction), p.getKey());
            assertTrue(html.contains(action), p.getKey());
            assertTrue(html.contains("action=\"" + base("website", EVENT) + "/note/" + id + "/delete\""), p.getKey());
            assertTrue(html.contains("name=\"visibility\" value=\"public\"><button type=\"submit\">Make public"), p.getKey());
            assertTrue(html.contains("name=\"next\" value=\"" + p.getValue() + "\""), p.getKey());
            assertTrue(html.contains("name=\"csrf\" value=\"" + ed.csrf() + "\""), p.getKey());
            assertFalse(get(p.getKey(), null).body().contains("note-form"), p.getKey());
            assertFalse(get(p.getKey(), login("vic")).body().contains("note-form"), p.getKey());
        }
        // The form's next is followed (and kept on this site).
        HttpResponse<String> r = post(base("website", EVENT) + "/notes",
                fields("text", "x", "next", "/service/website?day=2026-11-13", "csrf", ed.csrf()), ed);
        assertEquals("/service/website?day=2026-11-13", r.headers().firstValue("Location").orElseThrow());
        r = post(base("website", EVENT) + "/note/" + id + "/delete",
                fields("next", "//evil.example.com/", "csrf", ed.csrf()), ed);
        assertEquals(303, r.statusCode());
        assertEquals("/", r.headers().firstValue("Location").orElseThrow());
    }
}
