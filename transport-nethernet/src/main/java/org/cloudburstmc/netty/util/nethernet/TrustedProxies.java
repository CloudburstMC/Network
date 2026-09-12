package org.cloudburstmc.netty.util.nethernet;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The proxies allowed to speak for a client, with any {@code http} or {@code https} entry fetched
 * and expanded into the addresses it lists, one per line.
 * <p>
 * Resolved once per start and shared, because a host may bind several listeners and each would
 * otherwise fetch the same list again.
 */
public final class TrustedProxies {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(TrustedProxies.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static volatile IpRangeSet resolved;

    private TrustedProxies() {
    }

    /**
     * @param entries The configured entries, each an address, a CIDR range or a URL to fetch
     * @return The addresses that may speak for a client, empty when nothing is configured
     */
    public static synchronized IpRangeSet parse(Collection<String> entries) {
        IpRangeSet cached = resolved;
        if (cached != null) {
            return cached;
        }
        return resolved = IpRangeSet.parse(expand(entries));
    }

    /**
     * Forgets the resolved list so the next listener to start fetches it again.
     */
    public static synchronized void invalidate() {
        resolved = null;
    }

    private static List<String> expand(Collection<String> entries) {
        List<String> out = new ArrayList<>();
        for (String entry : entries) {
            String trimmed = entry == null ? "" : entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                out.add(trimmed);
                continue;
            }

            try {
                fetch(trimmed).lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .forEach(out::add);
            } catch (Exception e) {
                // A list that cannot be fetched must not widen trust, so it contributes nothing
                log.error("Could not fetch the trusted proxy list at {}, no addresses taken from it", trimmed, e);
            }
        }
        return out;
    }

    private static String fetch(String url) throws IOException, InterruptedException {
        HttpResponse<String> response = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
                .send(HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build(),
                        HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("returned HTTP " + response.statusCode());
        }
        return response.body();
    }
}
