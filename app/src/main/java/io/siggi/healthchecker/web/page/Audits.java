package io.siggi.healthchecker.web.page;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.siggi.healthchecker.audit.AuditLog;
import io.siggi.healthchecker.auth.Permissions;
import io.siggi.healthchecker.auth.User;
import io.siggi.healthchecker.history.EventNote;
import io.siggi.healthchecker.web.layout.SavedLayout;
import io.siggi.http.HTTPRequest;

import java.util.Locale;

/** Writes the dashboard editors' audit entries, and the JSON values they record. */
final class Audits {
    private Audits() {
    }

    /** Records an entry for the request's signed-in user (see {@link AuditLog#record}). */
    static void record(AuditLog log, Permissions permissions, HTTPRequest request, String action, String id,
                       JsonElement before, JsonElement after) {
        if (log == null) return;
        User user = permissions.user(request).orElse(null);
        log.record(user == null ? null : user.id(), user == null ? null : user.displayName(), action, id, before, after);
    }

    /** An event's title as recorded: {@code {"title": …}}, {@code null} for the default title. */
    static JsonObject title(String title) {
        JsonObject o = new JsonObject();
        o.add("title", title == null ? JsonNull.INSTANCE : new JsonPrimitive(title));
        return o;
    }

    /** A note as recorded, or {@code null}. */
    static JsonObject note(EventNote note) {
        if (note == null) return null;
        JsonObject o = new JsonObject();
        o.addProperty("author", note.author());
        o.addProperty("createdAt", note.createdAt().toString());
        o.add("editedAt", note.editedAt() == null ? JsonNull.INSTANCE : new JsonPrimitive(note.editedAt().toString()));
        o.addProperty("visibility", note.visibility().name().toLowerCase(Locale.ROOT));
        o.addProperty("text", note.text());
        return o;
    }

    /** A saved layout as recorded, or {@code null} when there's none (config order). */
    static JsonObject layout(SavedLayout layout) {
        if (layout == null) return null;
        JsonObject o = new JsonObject();
        o.addProperty("version", layout.version());
        JsonArray categories = new JsonArray();
        for (SavedLayout.Category category : layout.categories()) {
            JsonObject c = new JsonObject();
            c.addProperty("id", category.id());
            JsonArray services = new JsonArray();
            category.services().forEach(services::add);
            c.add("services", services);
            categories.add(c);
        }
        o.add("categories", categories);
        return o;
    }
}
