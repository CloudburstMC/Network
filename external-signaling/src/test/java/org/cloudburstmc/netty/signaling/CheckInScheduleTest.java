package org.cloudburstmc.netty.signaling;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;

class CheckInScheduleTest {
    private JsonObject response(long delay) {
        JsonObject response = new JsonObject(), schedule = new JsonObject();
        schedule.addProperty("afterMillis", delay);
        schedule.addProperty("leaseMillis", delay + 30000);
        schedule.addProperty("minUpdateIntervalMillis", 1000);
        response.add("checkIn", schedule);
        return response;
    }

    @Test
    void acceptsChangedPolicyWithoutHardCodedIdleThresholds() throws Exception {
        assertEquals(900000, CheckInSchedule.parse(response(900000)).afterMillis());
        assertEquals(3600000, CheckInSchedule.parse(response(3600000)).afterMillis());
        assertEquals(45000, CheckInSchedule.parse(response(45000)).afterMillis());
        assertEquals(75000, CheckInSchedule.parse(response(45000)).leaseMillis());
    }

    @Test
    void rejectsUnboundedFractionalAndInconsistentSchedules() {
        assertThrows(IOException.class, () -> CheckInSchedule.parse(response(0)));
        assertThrows(IOException.class, () -> CheckInSchedule.parse(response(86400001)));
        JsonObject fractional = response(900000);
        fractional.getAsJsonObject("checkIn").addProperty("afterMillis", 900000.5);
        assertThrows(IOException.class, () -> CheckInSchedule.parse(fractional));
        JsonObject expiredLease = response(900000);
        expiredLease.getAsJsonObject("checkIn").addProperty("leaseMillis", 900000);
        assertThrows(IOException.class, () -> CheckInSchedule.parse(expiredLease));
        JsonObject longLease = response(900000);
        longLease.getAsJsonObject("checkIn").addProperty("leaseMillis", 1200001);
        assertThrows(IOException.class, () -> CheckInSchedule.parse(longLease));
    }
}
