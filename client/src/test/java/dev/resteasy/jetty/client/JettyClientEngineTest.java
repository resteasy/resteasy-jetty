/*
 * Copyright The RESTEasy Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.resteasy.jetty.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.client.CompletionStageRxInvoker;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

import org.apache.http.entity.ContentType;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.internal.ResteasyClientBuilderImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class JettyClientEngineTest {
    Server server = new Server(0);
    Client client;

    @AfterEach
    public void stop() throws Exception {
        if (client != null) {
            client.close();
        }
        server.stop();
    }

    private Client client() throws Exception {
        if (!server.isStarted()) {
            server.start();
        }
        if (client == null) {
            client = ClientBuilder.newClient();
        }
        return client;
    }

    @Test
    public void clientCheck() throws Exception {
        Assertions.assertInstanceOf(JettyClientHttpEngine.class, ((ResteasyClient) client()).httpEngine());
    }

    @Test
    public void testSimple() throws Exception {
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(final Request request, final org.eclipse.jetty.server.Response response,
                    final Callback callback) throws Exception {

                if (request.getHeaders().get("User-Agent").contains("Apache")) {
                    response.setStatus(503);
                } else if (!"abracadabra".equals(request.getHeaders().get("Password"))) {
                    response.setStatus(403);
                } else {
                    response.setStatus(200);
                    Content.Sink.write(response, true, "Success", callback);
                }

                return true;
            }
        });

        final Response response = client().target(baseUri()).request()
                .header("Password", "abracadabra")
                .get();

        assertEquals(200, response.getStatus());
        assertEquals("Success", response.readEntity(String.class));
    }

    @Test
    public void testSimpleResponseRx() throws Exception {
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(final Request request, final org.eclipse.jetty.server.Response response,
                    final Callback callback) throws Exception {

                if (request.getHeaders().get("User-Agent").contains("Apache")) {
                    response.setStatus(503);
                } else if (!"abracadabra".equals(request.getHeaders().get("Password"))) {
                    response.setStatus(403);
                } else {
                    response.setStatus(200);
                    response.getHeaders().put(HttpHeaders.CONTENT_TYPE, ContentType.TEXT_PLAIN.getMimeType());
                    Content.Sink.write(response, true, "Success", callback);
                }
                return true;
            }
        });

        final CompletionStage<Response> cs = client().target(baseUri()).request()
                .header("Password", "abracadabra").rx(CompletionStageRxInvoker.class)
                .get();

        Response response = cs.toCompletableFuture().get();
        assertEquals(200, response.getStatus());
        assertEquals("Success", response.readEntity(String.class));
    }

    @Test
    public void testSimpleStringRx() throws Exception {
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(final Request request, final org.eclipse.jetty.server.Response response,
                    final Callback callback) throws Exception {

                if (request.getHeaders().get("User-Agent").contains("Apache")) {
                    response.setStatus(503);
                } else if (!"abracadabra".equals(request.getHeaders().get("Password"))) {
                    response.setStatus(403);
                } else {
                    response.setStatus(200);
                    response.getHeaders().put(HttpHeaders.CONTENT_TYPE, ContentType.TEXT_PLAIN.getMimeType());
                    Content.Sink.write(response, true, "Success", callback);
                }
                return true;
            }
        });

        final CompletionStage<String> cs = client().target(baseUri()).request()
                .header("Password", "abracadabra").rx(CompletionStageRxInvoker.class)
                .get(String.class);

        String response = cs.toCompletableFuture().get();
        assertEquals("Success", response);
    }

    @Test
    public void testBigly() throws Exception {
        server.setHandler(new EchoHandler());
        final byte[] valuableData = randomAlpha().getBytes(StandardCharsets.UTF_8);
        final Response response = client().target(baseUri()).request()
                .post(Entity.entity(valuableData, MediaType.APPLICATION_OCTET_STREAM_TYPE));

        assertEquals(200, response.getStatus());
        assertArrayEquals(valuableData, response.readEntity(byte[].class));
    }

    @Test
    public void testFutureResponse() throws Exception {
        server.setHandler(new EchoHandler());
        final String valuableData = randomAlpha();
        final Future<Response> response = client().target(baseUri()).request()
                .buildPost(Entity.entity(valuableData, MediaType.APPLICATION_OCTET_STREAM_TYPE))
                .submit();

        final Response resp = response.get(10, TimeUnit.SECONDS);
        assertEquals(200, resp.getStatus());
        assertEquals(valuableData, resp.readEntity(String.class));
    }

    @Test
    public void testFutureString() throws Exception {
        server.setHandler(new EchoHandler());
        final String valuableData = randomAlpha();
        final Future<String> response = client().target(baseUri()).request()
                .buildPost(Entity.entity(valuableData, MediaType.APPLICATION_OCTET_STREAM_TYPE))
                .submit(String.class);

        final String result = response.get(10, TimeUnit.SECONDS);
        assertEquals(valuableData.length(), result.length());
        assertEquals(valuableData, result);
    }

    private String randomAlpha() {
        final StringBuilder builder = new StringBuilder();
        final Random r = new Random();
        for (int i = 0; i < 20 * 1024 * 1024; i++) {
            builder.append((char) ('a' + (char) r.nextInt('z' - 'a')));
            if (i % 100 == 0)
                builder.append('\n');
        }
        return builder.toString();
    }

    @Test
    public void testTimeout() throws Exception {
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(final Request request, final org.eclipse.jetty.server.Response response,
                    final Callback callback) throws Exception {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    callback.failed(e);
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return true;
            }
        });

        try {
            client().target(baseUri()).request()
                    .property(JettyClientProperties.REQUEST_TIMEOUT, Duration.ofMillis(500))
                    .get();
            fail();
        } catch (ProcessingException e) {
            assertTrue(e.getCause() instanceof TimeoutException);
        }
    }

    @Test
    public void testIdleTimeout() throws Exception {
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(final Request request, final org.eclipse.jetty.server.Response response,
                    final Callback callback) throws Exception {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                response.setStatus(200);
                Content.Sink.write(response, true, "Success", callback);
                return true;
            }
        });

        try {
            client().target(baseUri()).request()
                    .property(JettyClientProperties.REQUEST_TIMEOUT, Duration.ofMillis(2000))
                    .property(JettyClientProperties.IDLE_TIMEOUT, Duration.ofMillis(500))
                    .get();
            fail();
        } catch (ProcessingException e) {
            assertTrue(e.getCause() instanceof TimeoutException);
        }

        final Response response = client().target(baseUri()).request()
                .property(JettyClientProperties.REQUEST_TIMEOUT, Duration.ofMillis(2000))
                .property(JettyClientProperties.IDLE_TIMEOUT, Duration.ofMillis(1500))
                .get();

        assertEquals(200, response.getStatus());
        assertEquals("Success", response.readEntity(String.class));

    }

    /**
     * If the async executor is shut down while a request is in flight, the completion task
     * submitted from {@code onHeaders} is rejected. Jetty treats the exchange as successful and
     * swallows that {@link java.util.concurrent.RejectedExecutionException}, so the response
     * future was previously left forever incomplete. This path is only reachable via the async
     * {@code submit(...)} entry points --
     * {@link org.jboss.resteasy.client.jaxrs.ClientHttpEngine#invoke(Invocation)} processes the response
     * directly on the calling thread and never touches the async executor for a GET with no
     * entity, so a plain synchronous call would no longer exercise this at all. The engine must
     * fail the future, so the async call completes exceptionally promptly instead of hanging.
     */
    @Test
    public void testExecutorShutdownWhileInFlightDoesNotHang() throws Exception {
        final ExecutorService delegate = Executors.newSingleThreadExecutor();
        final RejectionRecordingExecutor executor = new RejectionRecordingExecutor(delegate);

        // The handler shuts the executor down *before* writing any response bytes. Because this
        // runs only after the request has been fully sent and received, the sole remaining executor
        // interaction is the onHeaders completion submit on the client side, which is therefore
        // guaranteed to be rejected -- no race, and the pre-send body path (GET has no entity) is
        // never involved.
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(final Request request, final org.eclipse.jetty.server.Response response,
                    final Callback callback) throws Exception {
                executor.shutdownNow();
                response.setStatus(200);
                Content.Sink.write(response, true, "Success", callback);
                return true;
            }
        });
        if (!server.isStarted()) {
            server.start();
        }
        client = clientWithExecutor(executor);

        final Future<String> future = client.target(baseUri()).request().buildGet().submit(String.class);
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            final ExecutionException ee = assertThrows(ExecutionException.class, future::get);
            assertInstanceOf(ProcessingException.class, ee.getCause());
        }, "async call must fail fast, not hang on a swallowed rejection");
        // Prove the failure was produced by the guarded reject-at-onHeaders path, not some other
        // fast failure, so the test cannot pass for the wrong reason.
        assertTrue(executor.rejectedAfterShutdown(),
                "expected the onHeaders completion submit to be rejected by the shut-down executor");
    }

    /**
     * The empty-body variant of {@link #testExecutorShutdownWhileInFlightDoesNotHang}: the response
     * carries no entity, so there is never a body read that a request timeout or idle timeout could
     * interrupt. The exchange completes cleanly from Jetty's point of view the instant the (empty)
     * response arrives, so the swallowed rejection can only be caught by the engine's own guard --
     * no timeout can rescue this shape. The async call must still fail fast rather than hang.
     */
    @Test
    public void testExecutorShutdownWithEmptyBodyDoesNotHang() throws Exception {
        final ExecutorService delegate = Executors.newSingleThreadExecutor();
        final RejectionRecordingExecutor executor = new RejectionRecordingExecutor(delegate);

        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(final Request request, final org.eclipse.jetty.server.Response response,
                    final Callback callback) throws Exception {
                executor.shutdownNow();
                // 204 with no content: the response has no entity body at all.
                response.setStatus(204);
                callback.succeeded();
                return true;
            }
        });
        if (!server.isStarted()) {
            server.start();
        }
        client = clientWithExecutor(executor);

        final Future<Response> future = client.target(baseUri()).request().buildGet().submit();
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            final ExecutionException ee = assertThrows(ExecutionException.class, future::get);
            assertInstanceOf(ProcessingException.class, ee.getCause());
        }, "empty-body async call must fail fast; no timeout can rescue a body-less response");
        assertTrue(executor.rejectedAfterShutdown(),
                "expected the onHeaders completion submit to be rejected by the shut-down executor");
    }

    /**
     * A request with an entity hands the body-writing task to the async executor before the request
     * is sent. If that executor is already shut down, the submission is rejected on the calling
     * thread; the engine must translate that into a {@link ProcessingException} and complete the
     * future exceptionally rather than letting a raw {@code RejectedExecutionException} escape and
     * leaving the future dangling.
     */
    @Test
    public void testEntityRequestFailsFastWhenExecutorShutDown() throws Exception {
        server.setHandler(new EchoHandler());
        if (!server.isStarted()) {
            server.start();
        }

        final ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.shutdownNow();
        client = clientWithExecutor(executor);

        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> assertThrows(ProcessingException.class,
                () -> client.target(baseUri()).request()
                        .post(Entity.entity("body", MediaType.TEXT_PLAIN_TYPE), String.class)));
    }

    private static ResteasyClient clientWithExecutor(final ExecutorService executor) {
        return (ResteasyClient) new ResteasyClientBuilderImpl()
                .executorService(executor)
                .httpEngine(new JettyClientHttpEngine(new HttpClient(), 0, TimeUnit.MILLISECONDS))
                .build();
    }

    /**
     * Delegating {@link ExecutorService} that records whether any {@code submit}/{@code execute}
     * was rejected after the executor had been shut down, so a test can assert it actually hit the
     * rejection path rather than passing via some unrelated fast failure.
     */
    private static final class RejectionRecordingExecutor extends AbstractExecutorService {
        private final ExecutorService delegate;
        private final AtomicBoolean rejectedAfterShutdown = new AtomicBoolean();

        RejectionRecordingExecutor(final ExecutorService delegate) {
            this.delegate = delegate;
        }

        boolean rejectedAfterShutdown() {
            return rejectedAfterShutdown.get();
        }

        @Override
        public void execute(final Runnable command) {
            try {
                delegate.execute(command);
            } catch (RejectedExecutionException e) {
                if (delegate.isShutdown()) {
                    rejectedAfterShutdown.set(true);
                }
                throw e;
            }
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(final long timeout, final TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }

    @Test
    public void testDeferContent() throws Exception {
        server.setHandler(new EchoHandler());
        final byte[] valuableData = randomAlpha().getBytes(StandardCharsets.UTF_8);
        final Response response = client().target(baseUri()).request()
                .post(Entity.entity(new StreamingOutput() {
                    @Override
                    public void write(OutputStream output) throws IOException, WebApplicationException {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(e);
                        }
                        output.write(valuableData);
                    }
                }, MediaType.APPLICATION_OCTET_STREAM_TYPE));

        assertEquals(200, response.getStatus());
        assertArrayEquals(valuableData, response.readEntity(byte[].class));
    }

    @Test
    public void testFilterBufferReplay() throws Exception {
        final String greeting = "Success";
        final byte[] expected = (greeting).getBytes(StandardCharsets.UTF_8);
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(final Request request, final org.eclipse.jetty.server.Response response,
                    final Callback callback) throws Exception {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                response.setStatus(200);
                response.getHeaders().put(HttpHeaders.CONTENT_TYPE, ContentType.TEXT_PLAIN.getMimeType());
                Content.Sink.write(response, true, greeting, callback);
                return true;
            }
        });

        final byte[] content = new byte[expected.length];
        final ClientResponseFilter capturer = new ClientResponseFilter() {
            @Override
            public void filter(ClientRequestContext requestContext, ClientResponseContext responseContext) throws IOException {
                responseContext.getEntityStream().read(content);
            }
        };

        try (
                InputStream response = client().register(capturer).target(baseUri()).request()
                        .get(InputStream.class)) {
            // ignored, we are checking filter
        }

        assertArrayEquals(expected, content);
    }

    public URI baseUri() {
        return URI.create("http://localhost:" + ((ServerConnector) server.getConnectors()[0]).getLocalPort());
    }

    static class EchoHandler extends Handler.Abstract {

        @Override
        public boolean handle(final Request request, final org.eclipse.jetty.server.Response response, final Callback callback)
                throws Exception {
            response.setStatus(200);
            long contentLength = -1;
            for (HttpField field : request.getHeaders()) {
                if (field.getHeader() != null) {
                    switch (field.getHeader()) {
                        case CONTENT_LENGTH -> {
                            response.getHeaders().add(field);
                            contentLength = field.getLongValue();
                        }
                        case CONTENT_TYPE -> response.getHeaders().add(field);
                        case TRAILER -> response.setTrailersSupplier(HttpFields.build());
                        case TRANSFER_ENCODING -> contentLength = Long.MAX_VALUE;
                    }
                }
            }
            if (contentLength > 0)
                Content.copy(request, response, org.eclipse.jetty.server.Response.newTrailersChunkProcessor(response),
                        callback);
            else
                callback.succeeded();
            return true;
        }
    }
}
