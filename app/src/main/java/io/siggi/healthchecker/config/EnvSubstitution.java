package io.siggi.healthchecker.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces {@code ${env:NAME}} in every string value of a JSON tree (not in keys).
 * A reference may be the whole string or part of it. Unresolved references are
 * errors (the string is then left as is); an error names the variable but never shows
 * any resolved value.
 */
final class EnvSubstitution {
    private static final Pattern REF = Pattern.compile("\\$\\{env:([^}]*)}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final Function<String, String> env;
    private final Problems problems;

    EnvSubstitution(Function<String, String> env, Problems problems) {
        this.env = env;
        this.problems = problems;
    }

    JsonElement apply(JsonElement e, String path) {
        if (e.isJsonObject()) {
            JsonObject out = new JsonObject();
            for (var entry : e.getAsJsonObject().entrySet()) {
                String childPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
                out.add(entry.getKey(), apply(entry.getValue(), childPath));
            }
            return out;
        }
        if (e.isJsonArray()) {
            JsonArray in = e.getAsJsonArray();
            JsonArray out = new JsonArray(in.size());
            for (int i = 0; i < in.size(); i++) {
                out.add(apply(in.get(i), path + "[" + i + "]"));
            }
            return out;
        }
        if (Section.isString(e)) {
            String s = e.getAsString();
            if (!s.contains("${env:")) return e;
            return new JsonPrimitive(substitute(s, path));
        }
        return e;
    }

    private String substitute(String s, String path) {
        Matcher m = REF.matcher(s);
        StringBuilder sb = new StringBuilder();
        boolean failed = false;
        while (m.find()) {
            String name = m.group(1);
            String value = null;
            if (!NAME.matcher(name).matches()) {
                problems.error(path, "invalid environment variable name in ${env:" + name + "}");
            } else {
                value = env.apply(name);
                if (value == null) {
                    problems.error(path, "unresolved ${env:" + name + "} (environment variable " + name + " is not set)");
                }
            }
            if (value == null) failed = true;
            m.appendReplacement(sb, Matcher.quoteReplacement(value == null ? "" : value));
        }
        m.appendTail(sb);
        String result = sb.toString();
        if (REF.matcher(s).replaceAll("").contains("${env:")) {
            problems.error(path, "unterminated ${env:…} reference");
            failed = true;
        }
        // On failure keep the original text, so later checks don't report follow-up errors.
        return failed ? s : result;
    }
}
