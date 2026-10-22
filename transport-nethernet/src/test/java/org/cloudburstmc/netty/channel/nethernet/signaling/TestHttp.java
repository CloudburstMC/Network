/*
 * Copyright 2026 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.cloudburstmc.netty.channel.nethernet.signaling;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Speaks HTTP over a socket by hand, for tests that need control of the connection itself. */
final class TestHttp {

    private TestHttp() {
    }

    static int readStatus(BufferedReader in) throws IOException {
        String status = in.readLine();
        if (status == null) {
            throw new IOException("the listener closed without answering");
        }
        return Integer.parseInt(status.split(" ")[1]);
    }

    /** Reads the header block, lower cased so a comparison does not depend on how it was spelled. */
    static List<String> headers(BufferedReader in) throws IOException {
        List<String> headers = new ArrayList<>();
        for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
            headers.add(line.toLowerCase(Locale.ROOT));
        }
        return headers;
    }

    /** Reads the headers, then exactly the body they declare. */
    static String readBody(BufferedReader in) throws IOException {
        int length = 0;
        for (String header : headers(in)) {
            if (header.startsWith("content-length:")) {
                length = Integer.parseInt(header.substring("content-length:".length()).trim());
            }
        }
        char[] body = new char[length];
        int read = 0;
        while (read < length) {
            int n = in.read(body, read, length - read);
            if (n < 0) {
                throw new IOException("the body ended early");
            }
            read += n;
        }
        return new String(body);
    }

    /** A PROXY v2 header declaring an IPv4 source. */
    static byte[] proxyV2Header(String source, int sourcePort) {
        byte[] sig = {0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A};
        byte[] out = new byte[sig.length + 4 + 12];
        System.arraycopy(sig, 0, out, 0, sig.length);
        out[12] = 0x21;                     // version 2, PROXY
        out[13] = 0x11;                     // TCP over IPv4
        out[14] = 0;
        out[15] = 12;                       // address block length
        String[] octets = source.split("\\.");
        for (int i = 0; i < 4; i++) {
            out[16 + i] = (byte) Integer.parseInt(octets[i]);
        }
        out[20] = 127; out[21] = 0; out[22] = 0; out[23] = 1;   // destination
        out[24] = (byte) (sourcePort >> 8);
        out[25] = (byte) sourcePort;
        out[26] = (byte) (19190 >> 8);
        out[27] = (byte) 19190;
        return out;
    }
}
