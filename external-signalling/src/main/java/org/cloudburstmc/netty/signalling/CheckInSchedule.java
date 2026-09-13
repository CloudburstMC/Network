package org.cloudburstmc.netty.signalling;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.time.Instant;

/**
 * Validates the scheduling contract; policy and idle thresholds belong to the provider.
 */
record CheckInSchedule(long afterMillis, long minUpdateIntervalMillis) {
    static CheckInSchedule parse(JsonObject response) throws IOException {
        try {
            JsonObject checkIn = response.getAsJsonObject("checkIn");
            if (number(checkIn, "version") != 1) {
                throw new IllegalArgumentException();
            }

            long after = number(checkIn, "afterMillis"), minimum = number(checkIn, "minUpdateIntervalMillis");
            long next = number(checkIn, "nextCheckInAt"), expires = number(checkIn, "leaseExpiresAt");
            long received = Instant.parse(response.get("receivedAt").getAsString()).toEpochMilli();
            if (after < 1000 || after > 86400000 || minimum < 1000 || minimum > after
                    || next - received != after || expires <= next || expires - next > 300000) {
                throw new IllegalArgumentException();
            }

            return new CheckInSchedule(after, minimum);
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid provider check-in schedule", invalid);
        }
    }

    private static long number(JsonObject object, String field) {
        var value = object.getAsJsonPrimitive(field);
        if (!value.isNumber()) {
            throw new IllegalArgumentException();
        }
        return value.getAsBigDecimal().longValueExact();
    }
}
