package org.cloudburstmc.netty.signalling;

import com.google.gson.JsonObject;
import java.io.IOException;

/** Validates the scheduling contract; policy and idle thresholds belong to the provider. */
record CheckInSchedule(long afterMillis, long controlPollAfterMillis, long minUpdateIntervalMillis) {
    static CheckInSchedule parse(JsonObject response) throws IOException {
        try {
            JsonObject s = response.getAsJsonObject("checkIn");
            if (number(s, "version") != 1) throw new IllegalArgumentException();
            long after = number(s, "afterMillis"), control = number(s, "controlPollAfterMillis"), minimum = number(s, "minUpdateIntervalMillis");
            long next = number(s, "nextCheckInAt"), expires = number(s, "leaseExpiresAt");
            long received = java.time.Instant.parse(response.get("receivedAt").getAsString()).toEpochMilli();
            if (after < 1000 || after > 86400000 || control < 1000 || control > after || minimum < 1000 || minimum > after
                || next - received != after || expires <= next || expires - next > 300000) throw new IllegalArgumentException();
            return new CheckInSchedule(after, control, minimum);
        } catch (RuntimeException invalid) { throw new IOException("Invalid provider check-in schedule", invalid); }
    }
    private static long number(JsonObject object, String field) {
        var value = object.getAsJsonPrimitive(field);
        if (!value.isNumber()) throw new IllegalArgumentException();
        return value.getAsBigDecimal().longValueExact();
    }
}
