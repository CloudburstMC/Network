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

package org.cloudburstmc.netty.channel;

/**
 * Why a transport channel's connection ended or could not be made, fired as a user event just
 * before the channel closes. Closing the channel from the application fires nothing.
 */
public enum TransportDisconnectReason {
    /** The remote ended the connection. */
    CLOSED_BY_REMOTE_PEER,
    /** This side is shutting down. */
    SHUTTING_DOWN,
    /** This side ended the connection. */
    DISCONNECTED,
    /** The remote stopped answering. */
    TIMED_OUT,
    /** The connection request could not be completed. */
    CONNECTION_REQUEST_FAILED,
    /** The server refused the connection, as this address is already connected. */
    ALREADY_CONNECTED,
    /** The server refused the connection, as it is full. */
    NO_FREE_INCOMING_CONNECTIONS,
    /** The server speaks another protocol version. */
    INCOMPATIBLE_PROTOCOL_VERSION,
    /** The server refused the connection, as this address connected too recently. */
    IP_RECENTLY_CONNECTED,
    /** The remote sent something invalid. */
    BAD_PACKET,
    /** More was waiting to be sent than the transport holds, as when the remote stops reading. */
    QUEUE_TOO_LONG,
    /** The remote sent more split packets than could be reassembled. */
    SPLIT_QUEUE_TOO_LONG,
    /** The remote sent more out of order packets than could be held. */
    ORDERING_QUEUE_TOO_LONG,
    /** The remote aborted the connection rather than closing it cleanly. */
    ABORTED_BY_REMOTE_PEER,
    /** Connectivity to the remote was lost, such as a NetherNet peer no longer answering ICE checks. */
    CONNECTION_LOST,
    /** The transport itself failed, such as a broken DTLS session or a send that could not go out. */
    TRANSPORT_FAILED
}
