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

import org.eclipse.jetty.client.Response;
import org.jboss.resteasy.client.jaxrs.engines.AsyncClientHttpEngine.ResultExtractor;
import org.jboss.resteasy.client.jaxrs.internal.ClientInvocation;
import org.jboss.resteasy.client.jaxrs.internal.ClientResponse;

/**
 * The response listener of the asynchronous call path. Once Jetty delivers the response headers, it builds the
 * {@link ClientResponse} and dispatches the rest of the processing (buffering, extraction, callback) onto
 * {@code asyncExecutor}, completing {@code future} with the result or failure.
 */
class AsyncResponseListener<T> extends BodyKeepingResponseListener {
    private final ClientInvocation invocation;
    private final boolean buffered;
    private final InvocationCallback<T> callback;
    private final ResultExtractor<T> extractor;
    private final CompletableFuture<T> future;
    private final ExecutorService asyncExecutor;
    private ClientResponse cr;

    AsyncResponseListener(final ClientInvocation invocation, final boolean buffered, final InvocationCallback<T> callback,
            final ResultExtractor<T> extractor, final CompletableFuture<T> future, final ExecutorService asyncExecutor) {
        this.invocation = invocation;
        this.buffered = buffered;
        this.callback = callback;
        this.extractor = extractor;
        this.future = future;
        this.asyncExecutor = asyncExecutor;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onHeaders(final Response response) {
        super.onHeaders(response);
        final InputStream inputStream = getInputStream();
        try {
            cr = JettyClientHttpEngine.buildClientResponse(invocation, response, inputStream);
        } catch (RuntimeException e) {
            onFailure(response, JettyClientHttpEngine.closeSuppressing(inputStream, e));
            return;
        }
        try {
            asyncExecutor.submit(() -> {
                try {
                    if (buffered) {
                        cr.bufferEntity();
                    }
                    complete(extractor == null ? (T) cr : extractor.extractResult(cr));
                } catch (Exception e) {
                    onFailure(response, JettyClientHttpEngine.closeSuppressing(inputStream, e));
                }
            });
        } catch (RejectedExecutionException e) {
            // The executor was shut down (e.g. the client was closed) before the completion
            // task could be queued. Jetty considers the exchange successful and would swallow
            // this exception, leaving the future forever incomplete; fail it explicitly instead.
            onFailure(response, JettyClientHttpEngine.closeSuppressing(inputStream, e));
        }
    }

    @Override
    public void onFailure(final Response response, final Throwable failure) {
        super.onFailure(response, failure);
        JettyClientHttpEngine.failFuture(future, callback, JettyClientHttpEngine.clientException(failure, cr));
    }

    private void complete(final T result) {
        future.complete(result);
        if (callback != null) {
            callback.completed(result);
        }
    }
}
