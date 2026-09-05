package org.cloudburstmc.netty.signalling;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CheckInScheduleTest {
    private JsonObject response(long delay) {
        long now = 1800000000000L;
        JsonObject response = new JsonObject(), schedule = new JsonObject();
        response.addProperty("receivedAt", java.time.Instant.ofEpochMilli(now).toString());
        schedule.addProperty("version", 1); schedule.addProperty("afterMillis", delay);
        schedule.addProperty("nextCheckInAt", now + delay); schedule.addProperty("leaseExpiresAt", now + delay + 30000);
        schedule.addProperty("controlPollAfterMillis", delay); schedule.addProperty("minUpdateIntervalMillis", 1000);
        response.add("checkIn", schedule); return response;
    }
    @Test void acceptsChangedPolicyWithoutHardCodedIdleThresholds() throws Exception {
        assertEquals(900000, CheckInSchedule.parse(response(900000)).afterMillis());
        assertEquals(3600000, CheckInSchedule.parse(response(3600000)).controlPollAfterMillis());
        assertEquals(45000, CheckInSchedule.parse(response(45000)).afterMillis());
    }
    @Test void rejectsUnboundedFractionalAndInconsistentSchedules() {
        assertThrows(java.io.IOException.class, () -> CheckInSchedule.parse(response(0)));
        assertThrows(java.io.IOException.class, () -> CheckInSchedule.parse(response(86400001)));
        JsonObject fractional = response(900000); fractional.getAsJsonObject("checkIn").addProperty("afterMillis", 900000.5);
        assertThrows(java.io.IOException.class, () -> CheckInSchedule.parse(fractional));
        JsonObject wrongDeadline = response(900000); wrongDeadline.getAsJsonObject("checkIn").addProperty("leaseExpiresAt", 1);
        assertThrows(java.io.IOException.class, () -> CheckInSchedule.parse(wrongDeadline));
        JsonObject wrongVersion = response(900000); wrongVersion.getAsJsonObject("checkIn").addProperty("version", 2);
        assertThrows(java.io.IOException.class, () -> CheckInSchedule.parse(wrongVersion));
    }
}
