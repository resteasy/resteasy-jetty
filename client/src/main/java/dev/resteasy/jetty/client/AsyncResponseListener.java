/*
 * Copyright The RESTEasy Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.resteasy.jetty.client;

import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import jakarta.ws.rs.client.InvocationCallback;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.io.Content;
import org.jboss.logging.Logger;
import org.jboss.resteasy.client.jaxrs.engines.AsyncClientHttpEngine.ResultExtractor;
import org.jboss.resteasy.client.jaxrs.internal.ClientInvocation;
import org.jboss.resteasy.client.jaxrs.internal.ClientResponse;

/**
 * The response listener of the asynchronous call path. When the outcome of the call is a response, it builds the
 * {@link ClientResponse} and dispatches the rest of the processing (buffering, extraction, callback) onto
 * {@code asyncExecutor}, completing {@code future} with the result or failure. A later failure of the exchange does
 * not fail the call, unless the call reads the entity before it completes and the failure loses the body. The future
 * and the callback get exactly one outcome.
 */
class AsyncResponseListener<T> extends BodyKeepingResponseListener {
    private static final Logger LOGGER = Logger.getLogger(AsyncResponseListener.class);

    private final ClientInvocation invocation;
    private final boolean buffered;
    private final InvocationCallback<T> callback;
    private final ResultExtractor<T> extractor;
    private final CompletableFuture<T> future;
    private final ExecutorService asyncExecutor;

    AsyncResponseListener(final ClientInvocation invocation, final boolean buffered, final InvocationCallback<T> callback,
            final ResultExtractor<T> extractor, final CompletableFuture<T> future, final ExecutorService asyncExecutor,
            final HttpClient client, final Content.Source requestContent) {
        super(client, requestContent);
        this.invocation = invocation;
        this.buffered = buffered;
        this.callback = callback;
        this.extractor = extractor;
        this.future = future;
        this.asyncExecutor = asyncExecutor;
    }

    @Override
    @SuppressWarnings("unchecked")
    void onOutcome(final Response response, final Throwable failure) {
        if (failure != null) {
            JettyClientHttpEngine.failFuture(future, callback, JettyClientHttpEngine.clientException(failure, null));
            return;
        }
        final InputStream inputStream = getInputStream();
        final ClientResponse cr;
        try {
            cr = JettyClientHttpEngine.buildClientResponse(invocation, response, inputStream);
        } catch (RuntimeException e) {
            JettyClientHttpEngine.failFuture(future, callback,
                    JettyClientHttpEngine.clientException(JettyClientHttpEngine.closeSuppressing(inputStream, e), null));
            return;
        }
        try {
            asyncExecutor.submit(() -> {
                try {
                    if (buffered) {
                        cr.bufferEntity();
                    }
                    complete(cr, extractor == null ? (T) cr : extractor.extractResult(cr));
                } catch (Exception e) {
                    fail(cr, JettyClientHttpEngine.closeSuppressing(inputStream, e));
                }
            });
        } catch (RejectedExecutionException e) {
            // The executor was shut down (e.g. the client was closed) before the completion
            // task could be queued. Jetty considers the exchange successful and would swallow
            // this exception, leaving the future forever incomplete; fail it explicitly instead.
            fail(cr, JettyClientHttpEngine.closeSuppressing(inputStream, e));
        }
    }

    /**
     * Completes {@code future} with {@code result} and notifies the callback, or closes the response that nobody
     * will receive if the future already has an outcome. An exception of the callback cannot change the outcome of the
     * future, and the caller of the future can still read the response, so the listener only logs it.
     */
    private void complete(final ClientResponse cr, final T result) {
        if (future.complete(result)) {
            if (callback != null) {
                try {
                    callback.completed(result);
                } catch (RuntimeException e) {
                    LOGGER.warn("The invocation callback failed to process the response", e);
                }
            }
        } else {
            cr.close();
        }
    }

    private void fail(final ClientResponse cr, final Throwable failure) {
        JettyClientHttpEngine.failFuture(future, callback, JettyClientHttpEngine.clientException(failure, cr));
    }
}
