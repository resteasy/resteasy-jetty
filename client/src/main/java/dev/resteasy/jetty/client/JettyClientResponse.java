/*
 * Copyright The RESTEasy Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.resteasy.jetty.client;

import java.io.InputStream;

import org.jboss.resteasy.client.jaxrs.internal.ClientConfiguration;
import org.jboss.resteasy.client.jaxrs.internal.ClientResponse;
import org.jboss.resteasy.tracing.RESTEasyTracingLogger;

/**
 * Adapts a Jetty response's {@link InputStream} into RESTEasy's {@link ClientResponse}, so the
 * response entity can be read/deserialized through the usual RESTEasy provider machinery.
 */
class JettyClientResponse extends ClientResponse {

    /**
     * Creates a response wrapping {@code stream} as the entity input.
     *
     * @param configuration the client configuration, used for provider lookup when reading the entity
     * @param stream        the response body, streamed lazily rather than buffered up front
     */
    JettyClientResponse(final ClientConfiguration configuration, final InputStream stream) {
        super(configuration, RESTEasyTracingLogger.empty());
        setInputStream(stream);
    }

    /**
     * {@inheritDoc} Also resets the cached entity, since {@code ClientResponse} does not do so on
     * its own when the input stream is replaced after construction.
     */
    @Override
    protected void setInputStream(final InputStream is) {
        this.is = is;
        resetEntity();
    }
}
