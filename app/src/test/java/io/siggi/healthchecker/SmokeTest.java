package io.siggi.healthchecker;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SmokeTest {
    @Test
    void gsonIsOnTheClasspath() {
        JsonObject obj = new Gson().fromJson("{\"a\":1}", JsonObject.class);
        assertEquals(1, obj.get("a").getAsInt());
    }
}
