package org.cloudburstmc.netty.signaling;

import org.cloudburstmc.netty.signaling.control.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Configured production JDK control adapter; protocol verification and intent ownership stay in the coordinator. */
final class JdkProviderControlIo implements ControlClientIo, AutoCloseable {
    private final HttpClient client;
    private final ScheduledExecutorService scheduler;
    private final Executor receiver;
    private final JdkControlHttpTransport http;
    private final ControlledProviderApplication application;
    private final Consumer<ControlFrameDelivery> notice;
    JdkProviderControlIo(HttpClient client, ScheduledExecutorService scheduler, Executor receiver, ControlClientClock clock,
            ControlledProviderApplication application, Consumer<ControlFrameDelivery> notice) {
        this.client = client; this.scheduler = scheduler; this.receiver = receiver; this.application = application; this.notice = notice;
        http = new JdkControlHttpTransport(client, scheduler, clock, 4, 30000);
    }
    @Override public CompletionStage<HttpReply> bootstrap(URI endpoint, ControlSessionCodec.Request request) { return http.bootstrap(endpoint, request); }
    @Override public CompletionStage<HttpReply> authority(URI endpoint, ControlAuthorityCodec.Request request) { return http.authority(endpoint, request); }
    @Override public CompletionStage<HttpReply> operation(URI endpoint, ControlHttpCodec.Request request, byte[] body) { return http.operation(endpoint, request, body); }
    @Override public boolean requiresNativeIntentCancellation(ControlLifecycleCodec.Intent intent, byte[] originalBody) {
        return ControlledProviderApplication.requiresNativeCancellation(intent, originalBody);
    }
    @Override public boolean requiresOutcomeAcknowledgement() { return true; }
    @Override public CompletionStage<Void> acknowledgeCommittedOutcomes(ControlLifecycleCodec.Intent intent, byte[] originalBody, ControlLifecycleCodec.Receipt receipt) {
        return application.acknowledgeOutcomes(intent, originalBody, receipt);
    }
    @Override public Link openWebSocket(URI endpoint, ControlSessionCodec.Request request, Consumer<String> received) {
        return JdkControlLink.connect(client, endpoint, request, new JdkWebSocketTransport.Limits(ControlFrameCodec.MAX_FRAME_BYTES,
                1024, 8, 2L * ControlFrameCodec.MAX_FRAME_BYTES, Duration.ofSeconds(10), Duration.ofSeconds(10),
                Duration.ofSeconds(10), Duration.ofSeconds(2)), receiver, scheduler, received);
    }
    @Override public CompletionStage<ControlSynchronizationResult> synchronize(ControlWriterFence writer, ControlClientJournal.Grant grant,
            ControlAuthorityCodec.Verified authority, Synchronization exchange) { return application.synchronize(exchange); }
    @Override public void onSynchronizationFrame(ControlFrameDelivery delivery) { notice.accept(delivery); }
    @Override public void onVerifiedFrame(ControlFrameDelivery delivery) { notice.accept(delivery); }
    @Override public void close() { http.close(); }
}
