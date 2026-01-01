/*
 * Copyright 2025 CloudburstMC
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

package org.cloudburstmc.netty.channel.raknet.config;

public enum RakServerCookieMode {
    
    /**
     * Key is generated and managed by the server. 
     * Handles and responds to ID_OPEN_CONNECTION_REQUEST_1 with a stateless cookie.
     */
    ACTIVE,
    
    /**
     * Key is unknown. Ignores ID_OPEN_CONNECTION_REQUEST_1.
     * On ID_OPEN_CONNECTION_REQUEST_2, verifies timestamp is recent. Signature is ignored.
     */
    OFFLOADED,
    
    /**
     * Key is known. Ignores ID_OPEN_CONNECTION_REQUEST_1.
     * On ID_OPEN_CONNECTION_REQUEST_2, verifies timestamp is recent and checks cookie signature.
     */
    OFFLOADED_PSK,
    
    /**
     * Accepts any cookie at ID_OPEN_CONNECTION_REQUEST_2.
     */
    OFF,

    /*
     * Client is invalid if it sends a cookie.
     */
    INVALID
}
