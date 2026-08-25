/*
 * Copyright The RESTEasy Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.resteasy.jetty.client;

import java.time.Duration;

/**
 * Property names for configuring the Jetty client engine via
 * {@link jakarta.ws.rs.client.Invocation.Builder#property(String, Object)}.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
public final class JettyClientProperties {

    /**
     * Per-request property that overrides the total exchange timeout for that request. The value
     * may be a {@link Duration}, a {@link Number} (milliseconds), or a string parseable as a long.
     */
    public static final String REQUEST_TIMEOUT = "dev.resteasy.jetty.client.request.timeout";

    /**
     * Per-request property that overrides the idle timeout for that request -- the maximum gap
     * allowed between bytes before the exchange is considered stalled and failed. This is
     * independent of {@link #REQUEST_TIMEOUT}: a connection can stay open indefinitely as long as
     * it keeps making progress within this gap. Accepts the same value types as
     * {@link #REQUEST_TIMEOUT}.
     */
    public static final String IDLE_TIMEOUT = "dev.resteasy.jetty.client.idle.timeout";

    private JettyClientProperties() {
    }
}
