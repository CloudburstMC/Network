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

package org.cloudburstmc.netty.signaling.admission;

import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * The SDP form of a certificate digest, as published and as accepted. One spelling either way.
 */
final class DtlsFingerprint {
    private static final Pattern PATTERN = Pattern.compile("sha-256 ([0-9A-F]{2}:){31}[0-9A-F]{2}");
    private static final HexFormat HEX = HexFormat.ofDelimiter(":").withUpperCase();

    private DtlsFingerprint() {
    }

    static String format(byte[] digest) {
        return "sha-256 " + HEX.formatHex(digest);
    }

    static boolean valid(String value) {
        return value != null && PATTERN.matcher(value).matches();
    }
}
