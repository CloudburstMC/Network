package org.cloudburstmc.netty.channel.nethernet.backend;

import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class DataChannelSlotsTest {
    private static final DataChannelSlots.Parameters RELIABLE = new DataChannelSlots.Parameters(
            NetherNetConstants.RELIABLE_CHANNEL_LABEL, true, true, false, 0, 0);
    private static final DataChannelSlots.Parameters UNRELIABLE = new DataChannelSlots.Parameters(
            NetherNetConstants.UNRELIABLE_CHANNEL_LABEL, false, false, false, 0, 0);

    @Test
    void bothExpectedChannelsAreAdmittedWithoutNativeState() {
        DataChannelSlots<FakeChannel> slots = slots();
        FakeChannel reliable = new FakeChannel();
        FakeChannel unreliable = new FakeChannel();

        assertTrue(slots.admit(unreliable, UNRELIABLE));
        assertTrue(slots.admit(reliable, RELIABLE));

        assertSame(reliable, slots.reliable());
        assertSame(unreliable, slots.unreliable());
        assertEquals(0, reliable.closes.get());
        assertEquals(0, unreliable.closes.get());
    }

    @ParameterizedTest
    @MethodSource("invalidParameters")
    void malformedExtraDoesNotReplaceOrBlockThePendingHandshake(DataChannelSlots.Parameters parameters) {
        DataChannelSlots<FakeChannel> slots = slots();
        FakeChannel reliable = new FakeChannel();
        FakeChannel malformed = new FakeChannel();
        FakeChannel unreliable = new FakeChannel();
        assertTrue(slots.admit(reliable, RELIABLE));

        assertFalse(slots.admit(malformed, parameters));

        assertEquals(1, malformed.closes.get());
        assertSame(reliable, slots.reliable());
        assertNull(slots.unreliable());
        assertTrue(slots.admit(unreliable, UNRELIABLE));
        assertSame(unreliable, slots.unreliable());
        assertEquals(0, reliable.closes.get());
    }

    private static Stream<DataChannelSlots.Parameters> invalidParameters() {
        return Stream.of(
                new DataChannelSlots.Parameters("unexpected", true, true, false, 0, 0),
                new DataChannelSlots.Parameters(RELIABLE.label(), false, true, false, 0, 0),
                new DataChannelSlots.Parameters(RELIABLE.label(), true, false, false, 0, 0),
                new DataChannelSlots.Parameters(RELIABLE.label(), true, true, true, 0, 0),
                new DataChannelSlots.Parameters(RELIABLE.label(), true, true, false, 1, 0),
                new DataChannelSlots.Parameters(RELIABLE.label(), true, true, false, 0, 1),
                new DataChannelSlots.Parameters(UNRELIABLE.label(), true, false, false, 0, 0),
                new DataChannelSlots.Parameters(UNRELIABLE.label(), false, true, false, 0, 0),
                new DataChannelSlots.Parameters(UNRELIABLE.label(), false, false, false, 0, 1),
                new DataChannelSlots.Parameters(UNRELIABLE.label(), false, false, false, 1, 0));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void duplicateLabelCannotRedirectAnEstablishedStream(boolean replaceReliable) {
        DataChannelSlots<FakeChannel> slots = slots();
        FakeChannel reliable = new FakeChannel();
        FakeChannel unreliable = new FakeChannel();
        FakeChannel duplicate = new FakeChannel();
        assertTrue(slots.admit(reliable, RELIABLE));
        assertTrue(slots.admit(unreliable, UNRELIABLE));

        assertFalse(slots.admit(duplicate, replaceReliable ? RELIABLE : UNRELIABLE));

        assertEquals(1, duplicate.closes.get());
        assertSame(reliable, slots.reliable());
        assertSame(unreliable, slots.unreliable());
        slots.reliable().send(123);
        assertEquals(123, reliable.sentBytes.get());
        assertEquals(0, duplicate.sentBytes.get());
        assertEquals(0, reliable.closes.get());
        assertEquals(0, unreliable.closes.get());
    }

    @Test
    void repeatedCallbackForTheAcceptedHandleDoesNotCloseIt() {
        DataChannelSlots<FakeChannel> slots = slots();
        FakeChannel channel = new FakeChannel();
        assertTrue(slots.admit(channel, RELIABLE));

        assertFalse(slots.admit(channel, RELIABLE));

        assertSame(channel, slots.reliable());
        assertEquals(0, channel.closes.get());
    }

    @Test
    void channelsArrivingAfterClosureAreClosedInsteadOfPublished() {
        DataChannelSlots<FakeChannel> slots = slots();
        FakeChannel reliable = new FakeChannel();
        FakeChannel unreliable = new FakeChannel();
        slots.stopAccepting();

        assertFalse(slots.admit(reliable, RELIABLE));
        assertFalse(slots.admit(unreliable, UNRELIABLE));

        assertNull(slots.reliable());
        assertNull(slots.unreliable());
        assertEquals(1, reliable.closes.get());
        assertEquals(1, unreliable.closes.get());
    }

    @Test
    void racingDuplicateChannelsHaveExactlyOneOwner() throws Exception {
        DataChannelSlots<FakeChannel> slots = slots();
        FakeChannel first = new FakeChannel();
        FakeChannel second = new FakeChannel();
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var firstAdmission = executor.submit(() -> {
                start.await();
                return slots.admit(first, RELIABLE);
            });
            var secondAdmission = executor.submit(() -> {
                start.await();
                return slots.admit(second, RELIABLE);
            });
            start.countDown();

            boolean acceptedFirst = firstAdmission.get(2, TimeUnit.SECONDS);
            boolean acceptedSecond = secondAdmission.get(2, TimeUnit.SECONDS);

            assertTrue(acceptedFirst ^ acceptedSecond);
            assertSame(acceptedFirst ? first : second, slots.reliable());
            assertEquals(1, first.closes.get() + second.closes.get());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static DataChannelSlots<FakeChannel> slots() {
        return new DataChannelSlots<>(FakeChannel::close);
    }

    private static final class FakeChannel {
        private final AtomicInteger closes = new AtomicInteger();
        private final AtomicInteger sentBytes = new AtomicInteger();

        private void send(int bytes) {
            sentBytes.addAndGet(bytes);
        }

        private void close() {
            closes.incrementAndGet();
        }
    }
}
