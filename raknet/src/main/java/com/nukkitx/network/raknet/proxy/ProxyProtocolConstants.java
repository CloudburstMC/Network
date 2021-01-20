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

public interface ProxyProtocolConstants {

    /**
     * Command byte constants
     */
    byte COMMAND_LOCAL_BYTE = 0x00;
    byte COMMAND_PROXY_BYTE = 0x01;

    /**
     * Version byte constants
     */
    byte VERSION_ONE_BYTE = 0x10;
    byte VERSION_TWO_BYTE = 0x20;

    /**
     * Transport protocol byte constants
     */
    byte TRANSPORT_UNSPEC_BYTE = 0x00;
    byte TRANSPORT_STREAM_BYTE = 0x01;
    byte TRANSPORT_DGRAM_BYTE = 0x02;

    /**
     * Address family byte constants
     */
    byte AF_UNSPEC_BYTE = 0x00;
    byte AF_IPV4_BYTE = 0x10;
    byte AF_IPV6_BYTE = 0x20;
    byte AF_UNIX_BYTE = 0x30;

    /**
     * Transport protocol and address family byte constants
     */
    byte TPAF_UNKNOWN_BYTE = 0x00;
    byte TPAF_TCP4_BYTE = 0x11;
    byte TPAF_TCP6_BYTE = 0x21;
    byte TPAF_UDP4_BYTE = 0x12;
    byte TPAF_UDP6_BYTE = 0x22;
    byte TPAF_UNIX_STREAM_BYTE = 0x31;
    byte TPAF_UNIX_DGRAM_BYTE = 0x32;
    
    /**
     * V2 protocol binary header prefix
     */
    byte[] BINARY_PREFIX = {
            (byte) 0x0D,
            (byte) 0x0A,
            (byte) 0x0D,
            (byte) 0x0A,
            (byte) 0x00,
            (byte) 0x0D,
            (byte) 0x0A,
            (byte) 0x51,
            (byte) 0x55,
            (byte) 0x49,
            (byte) 0x54,
            (byte) 0x0A
    };

    byte[] TEXT_PREFIX = {
            (byte) 'P',
            (byte) 'R',
            (byte) 'O',
            (byte) 'X',
            (byte) 'Y',
    };

    /**
     * Maximum possible length of a v1 proxy protocol header per spec
     */
    int V1_MAX_LENGTH = 108;

    /**
     * Maximum possible length of a v2 proxy protocol header (fixed 16 bytes + max unsigned short)
     */
    int V2_MAX_LENGTH = 16 + 65535;

    /**
     * Minimum possible length of a fully functioning v2 proxy protocol header (fixed 16 bytes + v2 address info space)
     */
    int V2_MIN_LENGTH = 16 + 216;

    /**
     * Maximum possible length for v2 additional TLV data (max unsigned short - max v2 address info space)
     */
    int V2_MAX_TLV = 65535 - 216;

    /**
     * Binary header prefix length
     */
    int BINARY_PREFIX_LENGTH = BINARY_PREFIX.length;
}
