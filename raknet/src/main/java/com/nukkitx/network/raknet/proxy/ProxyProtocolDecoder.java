/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
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
package com.nukkitx.network.raknet.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.ProtocolDetectionResult;
import io.netty.util.CharsetUtil;

import java.net.InetSocketAddress;

/**
 * Decodes an HAProxy proxy protocol header
 *
 * @see <a href="https://haproxy.1wt.eu/download/1.5/doc/proxy-protocol.txt">Proxy Protocol Specification</a>
 * @see <a href="https://github.com/netty/netty/blob/4.1/codec-haproxy/src/main/java/io/netty/handler/codec/haproxy/HAProxyMessageDecoder.java">Netty implementation</a>
 */
public final class ProxyProtocolDecoder implements ProxyProtocolConstants {
    /**
     * {@link ProtocolDetectionResult} for {@link HAProxyProtocolVersion#V1}.
     */
    private static final ProtocolDetectionResult<HAProxyProtocolVersion> DETECTION_RESULT_V1 =
            ProtocolDetectionResult.detected(HAProxyProtocolVersion.V1);

    /**
     * {@link ProtocolDetectionResult} for {@link HAProxyProtocolVersion#V2}.
     */
    private static final ProtocolDetectionResult<HAProxyProtocolVersion> DETECTION_RESULT_V2 =
            ProtocolDetectionResult.detected(HAProxyProtocolVersion.V2);

    /**
     * Used to extract a header frame out of the {@link ByteBuf} and return it.
     */
    private HeaderExtractor headerExtractor;

    /**
     * {@code true} if we're discarding input because we're already over maxLength
     */
    private boolean discarding;

    /**
     * Number of discarded bytes
     */
    private int discardedBytes;

    /**
     * {@code true} if we're finished decoding the proxy protocol header
     */
    private boolean finished;

    /**
     * Protocol specification version
     */
    private int version = -1;

    /**
     * The latest v2 spec (2014/05/18) allows for additional data to be sent in the proxy protocol header beyond the
     * address information block so now we need a configurable max header size
     */
    private final int v2MaxHeaderSize = V2_MAX_LENGTH; // TODO: need to calculate max length if TLVs are desired.

    private ProxyProtocolDecoder() {}

    public static HAProxyMessage decode(ByteBuf packet) {
        ProxyProtocolDecoder decoder = new ProxyProtocolDecoder();
        return decoder.decodeHeader(packet);
    }

    private HAProxyMessage decodeHeader(ByteBuf in) {
        if (version == -1) {
            if ((version = findVersion(in)) == -1) {
                return null;
            }
        }

        final ByteBuf decoded = version == 1 ? decodeLine(in) : decodeStruct(in);
        if (decoded == null) {
            return null;
        }

        finished = true;
        try {
            if (version == 1) {
                return HAProxyMessage.decodeHeader(decoded.toString(CharsetUtil.US_ASCII));
            } else {
                return HAProxyMessage.decodeHeader(decoded);
            }
        } catch (HAProxyProtocolException e) {
            throw fail(null, e);
        }
    }

    private static int findVersion(final ByteBuf buffer) {
        final int n = buffer.readableBytes();
        // per spec, the version number is found in the 13th byte
        if (n < 13) {
            return -1;
        }

        int idx = buffer.readerIndex();
        return match(BINARY_PREFIX, buffer, idx) ? buffer.getByte(idx + BINARY_PREFIX_LENGTH) : 1;
    }

    /**
     * Create a frame out of the {@link ByteBuf} and return it.
     *
     * @param buffer  the {@link ByteBuf} from which to read data
     * @return frame  the {@link ByteBuf} which represent the frame or {@code null} if no frame could
     *                be created
     */
    private ByteBuf decodeStruct(ByteBuf buffer) {
        if (headerExtractor == null) {
            headerExtractor = new StructHeaderExtractor(v2MaxHeaderSize);
        }
        return headerExtractor.extract(buffer);
    }

    /**
     * Create a frame out of the {@link ByteBuf} and return it.
     *
     * @param buffer  the {@link ByteBuf} from which to read data
     * @return frame  the {@link ByteBuf} which represent the frame or {@code null} if no frame could
     *                be created
     */
    private ByteBuf decodeLine(ByteBuf buffer) {
        if (headerExtractor == null) {
            headerExtractor = new LineHeaderExtractor(V1_MAX_LENGTH);
        }
        return headerExtractor.extract(buffer);
    }

    private void failOverLimit(String length) {
        int maxLength = version == 1 ? V1_MAX_LENGTH : v2MaxHeaderSize;
        throw fail("header length (" + length + ") exceeds the allowed maximum (" + maxLength + ')', null);
    }

    private HAProxyProtocolException fail(String errMsg, Exception e) {
        finished = true;
        HAProxyProtocolException ppex;
        if (errMsg != null && e != null) {
            ppex = new HAProxyProtocolException(errMsg, e);
        } else if (errMsg != null) {
            ppex = new HAProxyProtocolException(errMsg);
        } else if (e != null) {
            ppex = new HAProxyProtocolException(e);
        } else {
            ppex = new HAProxyProtocolException();
        }
        return ppex;
    }

    /**
     * Returns the {@link ProtocolDetectionResult} for the given {@link ByteBuf}.
     */
    public static ProtocolDetectionResult<HAProxyProtocolVersion> detectProtocol(ByteBuf buffer) {
        if (buffer.readableBytes() < 12) {
            return ProtocolDetectionResult.needsMoreData();
        }

        int idx = buffer.readerIndex();

        if (match(BINARY_PREFIX, buffer, idx)) {
            return DETECTION_RESULT_V2;
        }
        if (match(TEXT_PREFIX, buffer, idx)) {
            return DETECTION_RESULT_V1;
        }
        return ProtocolDetectionResult.invalid();
    }

    private static boolean match(byte[] prefix, ByteBuf buffer, int idx) {
        for (int i = 0; i < prefix.length; i++) {
            final byte b = buffer.getByte(idx + i);
            if (b != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * HeaderExtractor create a header frame out of the {@link ByteBuf}.
     */
    private abstract class HeaderExtractor {
        /** Header max size */
        private final int maxHeaderSize;

        protected HeaderExtractor(int maxHeaderSize) {
            this.maxHeaderSize = maxHeaderSize;
        }

        /**
         * Create a frame out of the {@link ByteBuf} and return it.
         *
         * @param buffer  the {@link ByteBuf} from which to read data
         * @return frame  the {@link ByteBuf} which represent the frame or {@code null} if no frame could
         *                be created
         * @throws Exception if exceed maxLength
         */
        public ByteBuf extract(ByteBuf buffer) {
            final int eoh = findEndOfHeader(buffer);
            if (!discarding) {
                if (eoh >= 0) {
                    final int length = eoh - buffer.readerIndex();
                    if (length > maxHeaderSize) {
                        buffer.readerIndex(eoh + delimiterLength(buffer, eoh));
                        failOverLimit(String.valueOf(length));
                        return null;
                    }
                    ByteBuf frame = buffer.readSlice(length);
                    buffer.skipBytes(delimiterLength(buffer, eoh));
                    return frame;
                } else {
                    final int length = buffer.readableBytes();
                    if (length > maxHeaderSize) {
                        discardedBytes = length;
                        buffer.skipBytes(length);
                        discarding = true;
                        failOverLimit("over " + discardedBytes);
                    }
                    return null;
                }
            } else {
                if (eoh >= 0) {
                    final int length = discardedBytes + eoh - buffer.readerIndex();
                    buffer.readerIndex(eoh + delimiterLength(buffer, eoh));
                    discardedBytes = 0;
                    discarding = false;
                    failOverLimit("over " + length);
                } else {
                    discardedBytes += buffer.readableBytes();
                    buffer.skipBytes(buffer.readableBytes());
                }
                return null;
            }
        }

        /**
         * Find the end of the header from the given {@link ByteBuf}，the end may be a CRLF, or the length given by the
         * header.
         *
         * @param buffer the buffer to be searched
         * @return {@code -1} if can not find the end, otherwise return the buffer index of end
         */
        protected abstract int findEndOfHeader(ByteBuf buffer);

        /**
         * Get the length of the header delimiter.
         *
         * @param buffer the buffer where delimiter is located
         * @param eoh index of delimiter
         * @return length of the delimiter
         */
        protected abstract int delimiterLength(ByteBuf buffer, int eoh);
    }

    private final class LineHeaderExtractor extends HeaderExtractor {

        LineHeaderExtractor(int maxHeaderSize) {
            super(maxHeaderSize);
        }

        /**
         * Returns the index in the buffer of the end of line found.
         * Returns -1 if no end of line was found in the buffer.
         */
        @Override
        protected int findEndOfHeader(ByteBuf buffer) {
            final int n = buffer.writerIndex();
            for (int i = buffer.readerIndex(); i < n; i++) {
                final byte b = buffer.getByte(i);
                if (b == '\r' && i < n - 1 && buffer.getByte(i + 1) == '\n') {
                    return i;  // \r\n
                }
            }
            return -1;  // Not found.
        }

        @Override
        protected int delimiterLength(ByteBuf buffer, int eoh) {
            return buffer.getByte(eoh) == '\r' ? 2 : 1;
        }
    }

    private final class StructHeaderExtractor extends HeaderExtractor {

        StructHeaderExtractor(int maxHeaderSize) {
            super(maxHeaderSize);
        }

        /**
         * Returns the index in the buffer of the end of header if found.
         * Returns -1 if no end of header was found in the buffer.
         */
        @Override
        protected int findEndOfHeader(ByteBuf buffer) {
            final int n = buffer.readableBytes();

            // per spec, the 15th and 16th bytes contain the address length in bytes
            if (n < 16) {
                return -1;
            }

            int offset = buffer.readerIndex() + 14;

            // the total header length will be a fixed 16 byte sequence + the dynamic address information block
            int totalHeaderBytes = 16 + buffer.getUnsignedShort(offset);

            // ensure we actually have the full header available
            if (n >= totalHeaderBytes) {
                return totalHeaderBytes;
            } else {
                return -1;
            }
        }

        @Override
        protected int delimiterLength(ByteBuf buffer, int eoh) {
            return 0;
        }
    }
}
