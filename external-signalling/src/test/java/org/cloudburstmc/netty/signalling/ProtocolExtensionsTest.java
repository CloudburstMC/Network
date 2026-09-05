package org.cloudburstmc.netty.signalling;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolExtensionsTest {
    private static JsonObject document(String envelope) {
        return JsonParser.parseString("{\"extensions\":{\"org.example.feature\":" + envelope + "}}").getAsJsonObject();
    }
    @Test void ignoresUnknownOptionalVersionsWithoutGrantingCoreCapabilities() {
        JsonObject input = document("{\"version\":17,\"critical\":false,\"data\":{\"anything\":true}}");
        assertDoesNotThrow(() -> ProtocolExtensions.validate(input));
        JsonObject copy = ProtocolExtensions.copy(input); copy.remove("org.example.feature");
        assertEquals(1, input.getAsJsonObject("extensions").size());
    }
    @Test void rejectsRequiredOrMalformedExtensions() {
        for (String envelope : new String[]{
            "{\"version\":1,\"critical\":true,\"data\":{}}",
            "{\"version\":1.5,\"critical\":false,\"data\":{}}",
            "{\"version\":1,\"critical\":\"false\",\"data\":{}}",
            "{\"version\":1,\"critical\":false,\"data\":[]}"})
            assertThrows(IllegalArgumentException.class, () -> ProtocolExtensions.validate(document(envelope)));
    }
    @Test void boundsEncodedSizeAndCount() {
        JsonObject value = document("{\"version\":1,\"critical\":false,\"data\":{}}");
        JsonObject extension = value.getAsJsonObject("extensions").getAsJsonObject("org.example.feature");
        extension.getAsJsonObject("data").addProperty("text", "x".repeat(ProtocolExtensions.MAX_BYTES));
        assertThrows(IllegalArgumentException.class, () -> ProtocolExtensions.validate(value));
        extension.getAsJsonObject("data").remove("text");
        for (int i = 0; i < 17; i++) value.getAsJsonObject("extensions").add("org.example.feature" + i, extension.deepCopy());
        assertThrows(IllegalArgumentException.class, () -> ProtocolExtensions.validate(value));
    }
}
