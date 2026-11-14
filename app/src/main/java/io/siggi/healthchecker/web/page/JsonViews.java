package io.siggi.healthchecker.web.page;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.siggi.healthchecker.config.DashboardConfig;
import io.siggi.healthchecker.history.DaySummary;
import io.siggi.healthchecker.history.EventNote;
import io.siggi.healthchecker.history.Uptime;
import io.siggi.healthchecker.plugin.JsonEndpointConfig.Target;
import io.siggi.healthchecker.web.view.CheckView;
import io.siggi.healthchecker.web.view.EndpointView;
import io.siggi.healthchecker.web.view.EventView;
import io.siggi.healthchecker.web.view.NoteView;
import io.siggi.healthchecker.web.view.Viewer;
import io.siggi.http.HTTPRequest;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * JSON representations shared by the API responders. Like the HTML pages they only copy what the
 * {@link io.siggi.healthchecker.web.view.ViewFilter} views provide: a field the viewer may not see
 * is {@code null} in the view and left out here.
 */
final class JsonViews {
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    static final String CONTENT_TYPE = "application/json; charset=utf-8";

    private JsonViews() {
    }

    /** {@code "anonymous"} or the logged-in user's name. */
    static String viewer(Viewer viewer) {
        return viewer.anonymous() ? "anonymous" : viewer.userName();
    }

    /**
     * An endpoint as in {@code /api/status}: id, name, state, uptimes and day summaries, plus
     * whatever else the view provides (type, since, last check, visibility, targets).
     */
    static JsonObject endpoint(EndpointView service, Uptime day, Uptime sixtyDays, List<DaySummary> days,
                               DashboardConfig dashboard) {
        return endpoint(service, day, sixtyDays, days, dashboard, java.util.Set.of());
    }

    /** As above, with the days on which a maintenance window affecting it took place (see {@link #days}). */
    static JsonObject endpoint(EndpointView service, Uptime day, Uptime sixtyDays, List<DaySummary> days,
                               DashboardConfig dashboard, java.util.Set<java.time.LocalDate> maintenanceDays) {
        JsonObject endpoint = new JsonObject();
        endpoint.addProperty("id", service.id());
        endpoint.addProperty("name", service.name());
        if (service.type() != null) endpoint.addProperty("type", service.type());
        endpoint.addProperty("state", service.state().name());
        if (service.since() != null) endpoint.addProperty("since", service.since().toString());
        JsonObject uptimes = new JsonObject();
        uptimes.add("24h", percent(day));
        uptimes.add("60d", percent(sixtyDays));
        endpoint.add("uptime", uptimes);
        endpoint.add("days", days(days, dashboard, maintenanceDays));
        if (service.lastCheck() != null) {
            endpoint.add("last", check(service.lastCheck()));
        }
        // Privileged viewers only (ViewFilter leaves these null for everyone else).
        if (service.visibility() != null) endpoint.addProperty("visibility", service.visibility().configName());
        if (service.targets() != null) {
            JsonArray targets = new JsonArray();
            for (Target target : service.targets()) {
                JsonObject t = new JsonObject();
                t.addProperty("label", target.label());
                t.addProperty("value", target.value());
                targets.add(t);
            }
            endpoint.add("targets", targets);
        }
        return endpoint;
    }

    /** Day summaries, in the given order. */
    static JsonArray days(List<DaySummary> days, DashboardConfig dashboard) {
        return days(days, dashboard, java.util.Set.of());
    }

    /**
     * Day summaries, in the given order; a day in {@code maintenanceDays} (a maintenance window affecting
     * the service took place) has {@code "maintenance": true} and is blue without unplanned downtime.
     */
    static JsonArray days(List<DaySummary> days, DashboardConfig dashboard, java.util.Set<java.time.LocalDate> maintenanceDays) {
        JsonArray array = new JsonArray();
        for (DaySummary day : days) {
            JsonObject d = new JsonObject();
            d.addProperty("date", day.date().toString());
            d.add("uptime", percent(day.uptime()));
            d.addProperty("downtimeSeconds", day.uptime().downtime().getSeconds());
            d.addProperty("events", day.events());
            if (day.maintenanceEvents() > 0) d.addProperty("maintenanceEvents", day.maintenanceEvents());
            boolean planned = maintenanceDays.contains(day.date());
            if (planned) d.addProperty("maintenance", true);
            d.addProperty("color", DayColor.of(day, dashboard, planned).id());
            array.add(d);
        }
        return array;
    }

    /** A check with the fields the view provides ({@code at} and {@code success} always). */
    static JsonObject check(CheckView check) {
        JsonObject json = new JsonObject();
        json.addProperty("at", check.at().toString());
        if (check.latency() != null) json.addProperty("latencyMs", check.latency().toMillis());
        json.addProperty("success", check.success());
        if (check.message() != null) json.addProperty("message", check.message());
        if (check.details() != null) {
            JsonObject details = new JsonObject();
            for (Map.Entry<String, Object> e : check.details().entrySet()) {
                Object v = e.getValue();
                if (v instanceof Number n) details.addProperty(e.getKey(), n);
                else if (v instanceof Boolean b) details.addProperty(e.getKey(), b);
                else details.addProperty(e.getKey(), String.valueOf(v));
            }
            json.add("details", details);
        }
        return json;
    }

    /**
     * An event: id, title, kind, start, end ({@code null} while ongoing), duration (up to
     * {@code now} while ongoing) and the visible notes, plus the failure messages and
     * privileged fields where the view provides them.
     */
    static JsonObject event(EventView event, Instant now) {
        JsonObject json = new JsonObject();
        json.addProperty("id", event.id());
        json.addProperty("title", event.title());
        json.addProperty("kind", event.kind().name().toLowerCase(Locale.ROOT));
        json.addProperty("start", event.start().toString());
        json.add("end", event.end() == null ? JsonNull.INSTANCE : new JsonPrimitive(event.end().toString()));
        json.addProperty("ongoing", event.ongoing());
        json.addProperty("durationSeconds", event.duration(now).getSeconds());
        if (event.customTitle() != null) json.addProperty("customTitle", event.customTitle());
        if (event.firstFailureMessage() != null) json.addProperty("firstFailureMessage", event.firstFailureMessage());
        if (event.lastFailureMessage() != null) json.addProperty("lastFailureMessage", event.lastFailureMessage());
        if (event.endedUnobserved() != null) json.addProperty("endedUnobserved", event.endedUnobserved());
        if (event.endedByConfigChange() != null) json.addProperty("endedByConfigChange", event.endedByConfigChange());
        if (event.maintenanceId() != null) json.addProperty("maintenanceId", event.maintenanceId());
        JsonArray notes = new JsonArray();
        for (NoteView note : event.notes()) {
            notes.add(note(note));
        }
        json.add("notes", notes);
        return json;
    }

    static JsonObject note(NoteView note) {
        JsonObject json = new JsonObject();
        json.addProperty("id", note.id());
        json.addProperty("author", note.author());
        json.addProperty("createdAt", note.createdAt().toString());
        if (note.editedAt() != null) json.addProperty("editedAt", note.editedAt().toString());
        json.addProperty("text", note.text());
        if (note.visibility() != null) {
            json.addProperty("visibility", note.visibility() == EventNote.Visibility.PRIVATE ? "private" : "public");
        }
        return json;
    }

    /** The percentage as a number, or JSON {@code null} without data. */
    static JsonElement percent(Uptime uptime) {
        OptionalDouble p = uptime.percent();
        return p.isPresent() ? new JsonPrimitive(p.getAsDouble()) : JsonNull.INSTANCE;
    }

    /** {@code {"error": message}} with the given status. */
    static void error(HTTPRequest request, String status, String message) throws Exception {
        JsonObject json = new JsonObject();
        json.addProperty("error", message);
        PageSupport.write(request, status, CONTENT_TYPE, GSON.toJson(json));
    }
}
