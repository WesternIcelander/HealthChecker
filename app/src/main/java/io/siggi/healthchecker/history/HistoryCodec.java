package io.siggi.healthchecker.history;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * JSON form of coverage periods and events. Timestamps are ISO-8601 UTC strings, enums are
 * lowercase, and {@code null} values are left out.
 */
final class HistoryCodec {
    private HistoryCodec() {
    }

    /** Thrown for malformed stored data. */
    static final class FormatException extends Exception {
        FormatException(String message) {
            super(message);
        }
    }

    static String encodeCoverage(List<CoveragePeriod> periods) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < periods.size(); i++) {
            CoveragePeriod p = periods.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("start", p.start().toString());
            o.addProperty("end", p.end().toString());
            sb.append(i == 0 ? "\n  " : ",\n  ").append(o);
        }
        return sb.append(periods.isEmpty() ? "]\n" : "\n]\n").toString();
    }

    static List<CoveragePeriod> decodeCoverage(String json) throws FormatException {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            throw new FormatException("invalid JSON");
        }
        if (!root.isJsonArray()) {
            throw new FormatException("expected an array");
        }
        List<CoveragePeriod> result = new ArrayList<>();
        JsonArray array = root.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            String where = "[" + i + "]";
            JsonObject o = object(array.get(i), where);
            Instant start = requiredInstant(o, "start", where);
            Instant end = requiredInstant(o, "end", where);
            if (end.isBefore(start)) {
                throw new FormatException(where + ": end is before start");
            }
            result.add(new CoveragePeriod(start, end));
        }
        return result;
    }

    static String encodeEvent(HistoryEvent e) {
        JsonObject o = new JsonObject();
        o.addProperty("id", e.id());
        o.addProperty("endpointId", e.endpointId());
        o.addProperty("kind", e.kind().name().toLowerCase(Locale.ROOT));
        o.addProperty("start", e.start().toString());
        putIfSet(o, "end", e.end());
        putIfSet(o, "title", e.title());
        putIfSet(o, "firstFailureMessage", e.firstFailureMessage());
        putIfSet(o, "lastFailureMessage", e.lastFailureMessage());
        o.addProperty("lastFailureAt", e.lastFailureAt().toString());
        JsonArray notes = new JsonArray();
        for (EventNote n : e.notes()) {
            JsonObject no = new JsonObject();
            no.addProperty("id", n.id());
            no.addProperty("author", n.author());
            no.addProperty("createdAt", n.createdAt().toString());
            putIfSet(no, "editedAt", n.editedAt());
            no.addProperty("text", n.text());
            no.addProperty("visibility", n.visibility().name().toLowerCase(Locale.ROOT));
            notes.add(no);
        }
        o.add("notes", notes);
        o.addProperty("endedUnobserved", e.endedUnobserved());
        o.addProperty("endedByConfigChange", e.endedByConfigChange());
        putIfSet(o, "maintenanceId", e.maintenanceId());
        return o.toString();
    }

    static HistoryEvent decodeEvent(String line) throws FormatException {
        JsonElement root;
        try {
            root = JsonParser.parseString(line);
        } catch (JsonParseException e) {
            throw new FormatException("invalid JSON");
        }
        JsonObject o = object(root, "event");
        String id = requiredString(o, "id", "event");
        String where = "event " + id;
        Instant start = requiredInstant(o, "start", where);
        Instant end = optionalInstant(o, "end", where);
        if (end != null && end.isBefore(start)) {
            throw new FormatException(where + ": end is before start");
        }
        List<EventNote> notes = new ArrayList<>();
        JsonElement notesElement = o.get("notes");
        if (notesElement != null && !notesElement.isJsonNull()) {
            if (!notesElement.isJsonArray()) {
                throw new FormatException(where + ": notes must be an array");
            }
            for (JsonElement ne : notesElement.getAsJsonArray()) {
                JsonObject no = object(ne, where + " note");
                String noteId = requiredString(no, "id", where + " note");
                String nw = where + " note " + noteId;
                notes.add(new EventNote(noteId, requiredString(no, "author", nw), requiredInstant(no, "createdAt", nw),
                        optionalInstant(no, "editedAt", nw), requiredString(no, "text", nw),
                        enumValue(EventNote.Visibility.class, requiredString(no, "visibility", nw), nw)));
            }
        }
        Instant lastFailureAt = optionalInstant(o, "lastFailureAt", where);
        return new HistoryEvent(id, requiredString(o, "endpointId", where),
                enumValue(HistoryEvent.Kind.class, requiredString(o, "kind", where), where), start, end,
                optionalString(o, "title", where), optionalString(o, "firstFailureMessage", where),
                optionalString(o, "lastFailureMessage", where), lastFailureAt != null ? lastFailureAt : start,
                notes, optionalBoolean(o, "endedUnobserved", where), optionalBoolean(o, "endedByConfigChange", where),
                optionalString(o, "maintenanceId", where));
    }

    private static void putIfSet(JsonObject o, String key, Object value) {
        if (value != null) {
            o.addProperty(key, value.toString());
        }
    }

    private static JsonObject object(JsonElement e, String where) throws FormatException {
        if (e == null || !e.isJsonObject()) {
            throw new FormatException(where + ": expected an object");
        }
        return e.getAsJsonObject();
    }

    private static String optionalString(JsonObject o, String key, String where) throws FormatException {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!(e instanceof JsonPrimitive p) || !p.isString()) {
            throw new FormatException(where + ": " + key + " must be a string");
        }
        return p.getAsString();
    }

    private static String requiredString(JsonObject o, String key, String where) throws FormatException {
        String s = optionalString(o, key, where);
        if (s == null) {
            throw new FormatException(where + ": " + key + " is missing");
        }
        return s;
    }

    private static Instant optionalInstant(JsonObject o, String key, String where) throws FormatException {
        String s = optionalString(o, key, where);
        if (s == null) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            throw new FormatException(where + ": " + key + " is not a valid timestamp");
        }
    }

    private static Instant requiredInstant(JsonObject o, String key, String where) throws FormatException {
        Instant i = optionalInstant(o, key, where);
        if (i == null) {
            throw new FormatException(where + ": " + key + " is missing");
        }
        return i;
    }

    private static boolean optionalBoolean(JsonObject o, String key, String where) throws FormatException {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return false;
        }
        if (!(e instanceof JsonPrimitive p) || !p.isBoolean()) {
            throw new FormatException(where + ": " + key + " must be a boolean");
        }
        return p.getAsBoolean();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String where) throws FormatException {
        for (E constant : type.getEnumConstants()) {
            if (constant.name().toLowerCase(Locale.ROOT).equals(value)) {
                return constant;
            }
        }
        throw new FormatException(where + ": unknown value \"" + value + "\"");
    }
}
