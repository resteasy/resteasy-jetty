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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.AsyncInvoker;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.client.CompletionStageRxInvoker;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

import org.apache.http.entity.ContentType;
import org.eclipse.jetty.client.Authentication;
import org.eclipse.jetty.client.BasicAuthentication;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.HttpResponseException;
import org.eclipse.jetty.client.ProtocolHandler;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Callback;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.engines.AsyncClientHttpEngine;
import org.jboss.resteasy.client.jaxrs.internal.ClientInvocation;
import org.jboss.resteasy.client.jaxrs.internal.ClientResponse;
import org.jboss.resteasy.client.jaxrs.internal.ResteasyClientBuilderImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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

    /**
     * A server can reject a request with {@code Connection: close} before it reads the chunked body. The response
     * that already arrived must be the result of the call, although the body write then fails. Jetty stops the body
     * of a 401 itself, in its authentication handler. A 500 has no protocol handler, so the write fails on the closed
     * connection.
     * <p>
     * The body writer waits until the server closed the connection and the client received the response headers.
     * Only then does it write until a write fails.
     * <p>
     * The test does not read the body. Over HTTP/1.1, a request failure also fails a response that the application did
     * not read to its end, so Jetty can discard the body of the 500.
     * {@link #earlyChallengeKeepsBodyAfterBodyWriteFailure()} covers the body of a 401.
     */
    @ParameterizedTest
    @ValueSource(ints = { 401, 500 })
    public void earlyResponseWinsOverBodyWriteFailure(final int status) throws Exception {
        final CountDownLatch serverClosed = startEarlyRejectingServer(status, "nope!");
        final CountDownLatch headersReceived = new CountDownLatch(1);
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        client = clientWithExecutor(executor, notifyingHttpClient(headersReceived));
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
                final Response response = client.target(baseUri()).request()
                        .post(Entity.entity(writeUntilFailure(serverClosed, headersReceived), MediaType.TEXT_PLAIN_TYPE));
                assertEquals(status, response.getStatus());
                response.close();
            });
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Jetty buffers a 401 in its authentication handler, so the whole response arrived before the body write
     * fails. The body must stay readable. The handler forwards the 401 as a success, so this test does not cover a
     * request-only failure that arrives with the response. The listener unit tests cover that case.
     */
    @Test
    public void earlyChallengeKeepsBodyAfterBodyWriteFailure() throws Exception {
        final CountDownLatch serverClosed = startEarlyRejectingServer(401, "nope!");
        final CountDownLatch headersReceived = new CountDownLatch(1);
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        client = clientWithExecutor(executor, notifyingHttpClient(headersReceived));
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
                final Response response = client.target(baseUri()).request()
                        .post(Entity.entity(writeUntilFailure(serverClosed, headersReceived), MediaType.TEXT_PLAIN_TYPE));
                assertEquals(401, response.getStatus());
                assertEquals("nope!", response.readEntity(String.class));
            });
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * The asynchronous variant of {@link #earlyResponseWinsOverBodyWriteFailure}: the callback gets exactly one
     * outcome, and that outcome is the response with its body.
     * <p>
     * The single-thread executor makes the order deterministic. The body writer holds the only thread until the
     * server closed the connection and the client received the body, which is whole by its {@code Content-Length}.
     * Only then does it write until a write fails. The completion task runs after the body writer.
     */
    @ParameterizedTest
    @ValueSource(ints = { 401, 500 })
    public void earlyResponseWinsOverBodyWriteFailureAsync(final int status) throws Exception {
        final CountDownLatch serverClosed = startEarlyRejectingServer(status, "nope!");
        final CountDownLatch contentReceived = new CountDownLatch(1);
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        client = clientWithExecutor(executor, new HttpClient() {
            @Override
            public org.eclipse.jetty.client.Request newRequest(final URI uri) {
                return super.newRequest(uri).onResponseContent((response, content) -> contentReceived.countDown());
            }
        });
        final List<String> outcomes = new CopyOnWriteArrayList<>();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
                final Future<Response> future = client.target(baseUri()).request().async()
                        .post(Entity.entity(writeUntilFailure(serverClosed, contentReceived),
                                MediaType.TEXT_PLAIN_TYPE), new InvocationCallback<Response>() {
                                    @Override
                                    public void completed(final Response response) {
                                        outcomes.add("completed " + response.getStatus() + " "
                                                + response.readEntity(String.class));
                                    }

                                    @Override
                                    public void failed(final Throwable throwable) {
                                        outcomes.add("failed " + throwable);
                                    }
                                });
                try {
                    future.get(10, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    // The outcomes assertion reports the failure.
                }
                // Both callback paths run on the single executor thread, so a task queued now runs after them.
                executor.submit(() -> {
                }).get(10, TimeUnit.SECONDS);
            });
        } finally {
            executor.shutdownNow();
        }
        assertEquals(List.of("completed " + status + " nope!"), outcomes);
    }

    /**
     * Jetty fails the response of a 401 without a {@code WWW-Authenticate} challenge, after it delivered the whole
     * body. The call must return the 401 with its body.
     */
    @Test
    public void unchallengedUnauthorizedKeepsBody() throws Exception {
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(final Request request, final org.eclipse.jetty.server.Response response,
                    final Callback callback) {
                response.setStatus(401);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, MediaType.TEXT_PLAIN);
                response.getHeaders().put(HttpHeader.CONTENT_LENGTH, 5);
                Content.Sink.write(response, true, "nope!", callback);
                return true;
            }
        });
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            final Response response = client().target(baseUri()).request().get();
            assertEquals(401, response.getStatus());
            assertEquals("nope!", response.readEntity(String.class));
        });
    }

    /**
     * The asynchronous variant of {@link #unchallengedUnauthorizedKeepsBody()}: the callback gets exactly one
     * outcome, the 401 with its body.
     * <p>
     * The executor holds the completion task until the exchange is complete. Jetty notifies the failure to every
     * listener before it notifies completion, so the completion task always runs after the failure.
     */
    @Test
    public void unchallengedUnauthorizedKeepsBodyAsync() throws Exception {
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(final Request request, final org.eclipse.jetty.server.Response response,
                    final Callback callback) {
                response.setStatus(401);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, MediaType.TEXT_PLAIN);
                response.getHeaders().put(HttpHeader.CONTENT_LENGTH, 5);
                Content.Sink.write(response, true, "nope!", callback);
                return true;
            }
        });
        server.start();
        final CountDownLatch exchangeComplete = new CountDownLatch(1);
        final CompletionGatedExecutor executor = new CompletionGatedExecutor(Executors.newSingleThreadExecutor(),
                exchangeComplete);
        client = clientWithExecutor(executor, new HttpClient() {
            @Override
            public org.eclipse.jetty.client.Request newRequest(final URI uri) {
                return super.newRequest(uri).onComplete(result -> exchangeComplete.countDown());
            }
        });
        final List<String> outcomes = new CopyOnWriteArrayList<>();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
                final Future<Response> future = client.target(baseUri()).request().async()
                        .get(new InvocationCallback<Response>() {
                            @Override
                            public void completed(final Response response) {
                                outcomes.add("completed " + response.getStatus() + " " + response.readEntity(String.class));
                            }

                            @Override
                            public void failed(final Throwable throwable) {
                                outcomes.add("failed " + throwable);
                            }
                        });
                try {
                    future.get(10, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    // The outcomes assertion reports the failure.
                }
                executor.submit(() -> {
                }).get(10, TimeUnit.SECONDS);
            });
        } finally {
            executor.shutdownNow();
        }
        assertEquals(List.of("completed 401 nope!"), outcomes);
    }

    /**
     * If the entity writer fails, the server must not get a well-formed request whose body is cut short, and the
     * caller must get the failure of the writer. This is also true when the writer closes the stream itself, as a
     * try-with-resources block does.
     */
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    public void bodyWriterFailureDoesNotSendTruncatedBody(final boolean writerCloses) throws Exception {
        final CompletableFuture<String> serverRead = new CompletableFuture<>();
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(final Request request, final org.eclipse.jetty.server.Response response,
                    final Callback callback) {
                try {
                    serverRead.complete("read " + Content.Source.asString(request));
                } catch (Throwable t) {
                    serverRead.complete("failed");
                }
                response.setStatus(400);
                callback.succeeded();
                return true;
            }
        });

        final StreamingOutput failingWriter = output -> {
            if (writerCloses) {
                try (OutputStream out = output) {
                    out.write("partial".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    throw new IOException("application failure");
                }
            }
            output.write("partial".getBytes(StandardCharsets.UTF_8));
            output.flush();
            throw new IOException("application failure");
        };
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            final ProcessingException e = assertThrows(ProcessingException.class,
                    () -> client().target(baseUri()).request().post(Entity.entity(failingWriter, MediaType.TEXT_PLAIN_TYPE)));
            assertEquals("application failure", e.getCause().getMessage());
            assertEquals("failed", serverRead.get(10, TimeUnit.SECONDS));
        });
    }

    /**
     * Over HTTP/2, a server can answer before it reads the request body, and then reset the stream. The response
     * that arrived must be the result of the call, with its body.
     */
    @ParameterizedTest
    @ValueSource(ints = { 401, 500 })
    public void earlyResponseWinsOverBodyWriteFailureHttp2(final int status) throws Exception {
        final Server h2Server = new Server();
        h2Server.addConnector(new ServerConnector(h2Server, new HTTP2CServerConnectionFactory(new HttpConfiguration())));
        h2Server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(final Request request, final org.eclipse.jetty.server.Response response,
                    final Callback callback) {
                response.setStatus(status);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, MediaType.TEXT_PLAIN);
                response.getHeaders().put(HttpHeader.CONTENT_LENGTH, 5);
                if (status == 401) {
                    response.getHeaders().put(HttpHeader.WWW_AUTHENTICATE, "Bearer realm=\"test\"");
                }
                Content.Sink.write(response, true, "nope!", callback);
                return true;
            }
        });
        h2Server.start();
        final CountDownLatch headersReceived = new CountDownLatch(1);
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        client = clientWithExecutor(executor, new HttpClient(new HttpClientTransportOverHTTP2(new HTTP2Client())) {
            @Override
            public org.eclipse.jetty.client.Request newRequest(final URI uri) {
                return super.newRequest(uri).onResponseHeaders(response -> headersReceived.countDown());
            }
        });
        try {
            final URI uri = URI.create("http://localhost:" + ((ServerConnector) h2Server.getConnectors()[0]).getLocalPort());
            assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
                final Response response = client.target(uri).request()
                        .post(Entity.entity(writeUntilFailure(headersReceived), MediaType.TEXT_PLAIN_TYPE));
                assertEquals(status, response.getStatus());
                assertEquals("nope!", response.readEntity(String.class));
            });
        } finally {
            executor.shutdownNow();
            h2Server.stop();
        }
    }

    /**
     * Jetty fails a request whose destination it cannot resolve, for example for a scheme other than http or https,
     * and it reports only the completion. The call must fail promptly, and a body writer must stop.
     */
    @ParameterizedTest
    @CsvSource({ "false,false", "false,true", "true,false", "true,true" })
    public void unresolvableDestinationFailsPromptly(final boolean async, final boolean withBody) throws Exception {
        final ExecutorService executor = Executors.newCachedThreadPool();
        client = clientWithExecutor(executor);
        final CountDownLatch writerDone = new CountDownLatch(1);
        final StreamingOutput writer = output -> {
            try {
                final byte[] chunk = new byte[8 * 1024];
                while (true) {
                    output.write(chunk);
                    output.flush();
                }
            } finally {
                writerDone.countDown();
            }
        };
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
                final Invocation.Builder builder = client.target("ftp://localhost:1/").request();
                final Entity<StreamingOutput> entity = Entity.entity(writer, MediaType.TEXT_PLAIN_TYPE);
                final Throwable failure;
                if (async) {
                    final Future<Response> future = withBody ? builder.async().post(entity) : builder.async().get();
                    failure = assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS)).getCause();
                } else {
                    failure = assertThrows(ProcessingException.class,
                            () -> (withBody ? builder.post(entity) : builder.get()).close());
                }
                assertInstanceOf(ProcessingException.class, failure);
                if (withBody) {
                    await(writerDone);
                }
            });
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * The entity writer can fail at once, for example when no message body writer fits. The call must fail promptly
     * with the failure of the writer. The inline executor runs the body writer on the calling thread, right after the
     * engine sends the request.
     */
    @Test
    public void bodyWriterFailureOnCallingThreadFailsPromptly() throws Exception {
        server.setHandler(new ReadBodyHandler());
        server.start();
        client = clientWithExecutor(new InlineExecutor());

        final StreamingOutput failingWriter = output -> {
            throw new IOException("application failure");
        };
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            final ProcessingException e = assertThrows(ProcessingException.class,
                    () -> client.target(baseUri()).request().post(Entity.entity(failingWriter, MediaType.TEXT_PLAIN_TYPE)));
            assertEquals("application failure", e.getCause().getMessage());
        });
    }

    /**
     * A request can wait in the destination queue for a connection. If its entity writer fails there, the call must
     * fail promptly and must not wait for a connection.
     * <p>
     * A first request holds the only connection until the end of the test. The executor starts the body writer of the
     * second request after Jetty queued both requests, or runs it on the calling thread right after the engine sends
     * the request. The second request uses the synchronous or the asynchronous call path.
     */
    @ParameterizedTest
    @CsvSource({ "false,false", "true,false", "true,true" })
    public void bodyWriterFailureWhileQueuedFailsPromptly(final boolean inline, final boolean async) throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(final Request request, final org.eclipse.jetty.server.Response response,
                    final Callback callback) {
                await(release);
                response.setStatus(200);
                callback.succeeded();
                return true;
            }
        });
        server.start();
        final CountDownLatch queued = new CountDownLatch(2);
        final HttpClient httpClient = new HttpClient() {
            @Override
            public org.eclipse.jetty.client.Request newRequest(final URI uri) {
                return super.newRequest(uri).onRequestQueued(request -> queued.countDown());
            }
        };
        httpClient.setMaxConnectionsPerDestination(1);
        final ExecutorService executor = inline ? new InlineExecutor()
                : new GatedExecutor(Executors.newCachedThreadPool(), queued);
        client = clientWithExecutor(executor, httpClient);

        final StreamingOutput failingWriter = output -> {
            throw new IOException("application failure");
        };
        try {
            httpClient.newRequest(baseUri()).send(result -> {
            });
            // Shorter than the 10 second hold of the first request, so a call that waits for the connection fails.
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                final Entity<StreamingOutput> entity = Entity.entity(failingWriter, MediaType.TEXT_PLAIN_TYPE);
                final Throwable failure;
                if (async) {
                    failure = assertThrows(ExecutionException.class,
                            () -> client.target(baseUri()).request().async().post(entity).get(10, TimeUnit.SECONDS))
                                    .getCause();
                } else {
                    failure = assertThrows(ProcessingException.class, () -> client.target(baseUri()).request().post(entity));
                }
                assertInstanceOf(ProcessingException.class, failure);
                assertEquals("application failure", failure.getCause().getMessage());
            });
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * An {@link Error} from the entity writer must fail the call promptly as a {@link ProcessingException}, both
     * when the writer fails at once on the calling thread and when it fails after Jetty read its first write.
     */
    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    public void bodyWriterErrorFailsPromptly(final boolean inline) throws Exception {
        server.setHandler(new ReadBodyHandler());
        server.start();
        final ExecutorService executor = inline ? new InlineExecutor() : Executors.newSingleThreadExecutor();
        client = clientWithExecutor(executor);

        final StreamingOutput failingWriter = output -> {
            if (!inline) {
                // The flush returns only after Jetty reads the content.
                output.write('x');
                output.flush();
            }
            throw new NoClassDefFoundError("missing");
        };
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
                final ProcessingException e = assertThrows(ProcessingException.class,
                        () -> client.target(baseUri()).request()
                                .post(Entity.entity(failingWriter, MediaType.TEXT_PLAIN_TYPE)));
                assertTrue(hasCause(e, NoClassDefFoundError.class), () -> "unexpected cause chain: " + e);
            });
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * A cancel of the future gives the callback exactly one outcome, a {@link ProcessingException}, as the RESTEasy
     * Apache engine does.
     */
    @Test
    public void cancelNotifiesCallbackOnce() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(final Request request, final org.eclipse.jetty.server.Response response,
                    final Callback callback) {
                await(release);
                response.setStatus(200);
                callback.succeeded();
                return true;
            }
        });
        server.start();
        final CountDownLatch exchangeComplete = new CountDownLatch(1);
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        client = clientWithExecutor(executor, new HttpClient() {
            @Override
            public org.eclipse.jetty.client.Request newRequest(final URI uri) {
                return super.newRequest(uri).onComplete(result -> exchangeComplete.countDown());
            }
        });
        final List<String> outcomes = new CopyOnWriteArrayList<>();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
                final ClientInvocation invocation = (ClientInvocation) client.target(baseUri()).request().buildGet();
                final Future<ClientResponse> future = ((AsyncClientHttpEngine) ((ResteasyClient) client).httpEngine())
                        .submit(invocation, false, new InvocationCallback<ClientResponse>() {
                            @Override
                            public void completed(final ClientResponse response) {
                                outcomes.add("completed " + response.getStatus());
                            }

                            @Override
                            public void failed(final Throwable throwable) {
                                outcomes.add("failed " + throwable.getClass().getSimpleName());
                            }
                        }, null);
                assertTrue(future.cancel(true));
                await(exchangeComplete);
                executor.submit(() -> {
                }).get(10, TimeUnit.SECONDS);
            });
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertEquals(List.of("failed ProcessingException"), outcomes);
    }

    /**
     * A request listener aborts the request with a {@link CancellationException} when Jetty queues it. The call must
     * fail with that cause, and the synchronous call with a {@link ProcessingException}, as for any other failure.
     */
    @ParameterizedTest
    @ValueSource(strings = { "sync", "future", "callback" })
    public void cancellationAbortFailsWithProcessingException(final String kind) throws Exception {
        final HttpClient httpClient = new HttpClient();
        httpClient.getRequestListeners().addListener(new org.eclipse.jetty.client.Request.Listener() {
            @Override
            public void onQueued(final org.eclipse.jetty.client.Request request) {
                request.abort(new CancellationException("aborted before it was sent"));
            }
        });
        final ExecutorService executor = Executors.newCachedThreadPool();
        client = clientWithExecutor(executor, httpClient);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> assertCallFails(client.target("http://localhost:1/").request(), kind,
                            CancellationException.class));
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Jetty buffers a chunked 401 without a {@code WWW-Authenticate} challenge, forwards it, and then fails the
     * response. The call must return the 401 with its body.
     */
    @Test
    public void chunkedUnchallengedUnauthorizedKeepsBody() throws Exception {
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(final Request request, final org.eclipse.jetty.server.Response response,
                    final Callback callback) throws Exception {
                response.setStatus(401);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, MediaType.TEXT_PLAIN);
                // A first write without the last flag commits the headers without a Content-Length.
                final Callback.Completable first = new Callback.Completable();
                Content.Sink.write(response, false, "no", first);
                first.get(10, TimeUnit.SECONDS);
                Content.Sink.write(response, true, "pe!", callback);
                return true;
            }
        });
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            final Response response = client().target(baseUri()).request().get();
            assertEquals(401, response.getStatus());
            assertEquals(null, response.getHeaderString(HttpHeaders.CONTENT_LENGTH));
            assertEquals("nope!", response.readEntity(String.class));
        });
    }

    private static boolean hasCause(final Throwable throwable, final Class<? extends Throwable> type) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A server can answer a chunked POST early with a redirect and close the connection before the 303 is complete.
     * Jetty then fails the request body and the 303, and still follows the redirect. The failure that the body writer
     * then gets must not fail the request that follows the redirect.
     * <p>
     * The server sends only part of the 303 body before it closes the connection. The body writer sends one byte, and
     * waits until Jetty follows the redirect before it writes more. The redirect goes to another destination, and the
     * test resolves its address only after the body writer task is complete. Thus the request that follows the
     * redirect still waits for a connection when the engine handles the failure of the body writer.
     */
    @Test
    public void bodyWriteFailureDoesNotFailRedirect() throws Exception {
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(final Request request, final org.eclipse.jetty.server.Response response,
                    final Callback callback) throws IOException {
                if ("/b".equals(Request.getPathInContext(request))) {
                    response.setStatus(200);
                    Content.Sink.write(response, true, "ok", callback);
                    return true;
                }
                Content.Source.asInputStream(request).read();
                response.setStatus(303);
                response.getHeaders().put(HttpHeader.LOCATION,
                        "http://127.0.0.1:" + ((ServerConnector) server.getConnectors()[0]).getLocalPort() + "/b");
                response.getHeaders().put(HttpHeader.CONTENT_LENGTH, 10);
                final Callback.Completable sent = new Callback.Completable();
                Content.Sink.write(response, false, "short", sent);
                try {
                    sent.get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IOException(e);
                }
                request.getConnectionMetaData().getConnection().getEndPoint().close();
                callback.failed(new IOException("The test closed the connection"));
                return true;
            }
        });
        server.start();
        final TaskDoneExecutor executor = new TaskDoneExecutor(Executors.newCachedThreadPool());
        final CountDownLatch redirectResolving = new CountDownLatch(1);
        final HttpClient httpClient = new HttpClient();
        httpClient.setSocketAddressResolver((host, port, context, promise) -> {
            final Runnable resolve = () -> promise.succeeded(List.of(new InetSocketAddress(host, port)));
            if ("127.0.0.1".equals(host)) {
                redirectResolving.countDown();
                executor.execute(() -> {
                    await(executor.firstTaskDone);
                    resolve.run();
                });
            } else {
                resolve.run();
            }
        });
        client = clientWithExecutor(executor, httpClient);

        final StreamingOutput writer = output -> {
            output.write('x');
            output.flush();
            await(redirectResolving);
            final byte[] chunk = new byte[8 * 1024];
            while (true) {
                output.write(chunk);
                output.flush();
            }
        };
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
                final Response response = client.target(baseUri()).request()
                        .post(Entity.entity(writer, MediaType.TEXT_PLAIN_TYPE));
                assertEquals(200, response.getStatus());
                assertEquals("ok", response.readEntity(String.class));
            });
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Jetty's body stream can throw without a failure of the request, for an interrupt or for a bad argument to a
     * write. The call must still fail promptly, and the server must not get a complete body.
     */
    @ParameterizedTest
    @ValueSource(strings = { "interrupt", "badIndex" })
    public void bodyStreamFailureWithoutJettyFailureFailsPromptly(final String kind) throws Exception {
        final CompletableFuture<String> serverRead = new CompletableFuture<>();
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(final Request request, final org.eclipse.jetty.server.Response response,
                    final Callback callback) {
                try {
                    serverRead.complete("read " + Content.Source.asString(request));
                } catch (Throwable t) {
                    serverRead.complete("failed");
                }
                response.setStatus(200);
                callback.succeeded();
                return true;
            }
        });

        final StreamingOutput writer = output -> {
            output.write("partial".getBytes(StandardCharsets.UTF_8));
            output.flush();
            if ("interrupt".equals(kind)) {
                Thread.currentThread().interrupt();
                output.write('y');
            } else {
                output.write(new byte[1], 5, 1);
            }
        };
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            assertThrows(ProcessingException.class,
                    () -> client().target(baseUri()).request().post(Entity.entity(writer, MediaType.TEXT_PLAIN_TYPE)).close());
            assertEquals("failed", serverRead.get(5, TimeUnit.SECONDS));
        });
    }

    /**
     * Jetty's authentication handler forwards an empty response when it could not buffer the body of a 401 without a
     * challenge: a body larger than its 16 KiB buffer, with or without a {@code Content-Length}, or a body cut short.
     * The call returns the 401, on the synchronous and the asynchronous path, and the body read must fail, because the
     * body is lost. An empty chunked body gives the same empty response, so its read fails too.
     */
    @ParameterizedTest
    @CsvSource({ "large,false", "truncated,false", "chunkedLarge,false", "chunkedEmpty,false", "large,true",
            "truncated,true", "chunkedLarge,true", "chunkedEmpty,true" })
    public void unchallengedUnauthorizedLostBodyFailsRead(final String kind, final boolean async) throws Exception {
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(final Request request, final org.eclipse.jetty.server.Response response,
                    final Callback callback) throws Exception {
                response.setStatus(401);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, MediaType.TEXT_PLAIN);
                if ("large".equals(kind)) {
                    response.getHeaders().put(HttpHeader.CONTENT_LENGTH, 20_000);
                    Content.Sink.write(response, true, "a".repeat(20_000), callback);
                } else if (kind.startsWith("chunked")) {
                    // A first write without the last flag commits the headers without a Content-Length.
                    final String half = "chunkedLarge".equals(kind) ? "a".repeat(10_000) : "";
                    final Callback.Completable first = new Callback.Completable();
                    Content.Sink.write(response, false, half, first);
                    first.get(10, TimeUnit.SECONDS);
                    Content.Sink.write(response, true, half, callback);
                } else {
                    response.getHeaders().put(HttpHeader.CONTENT_LENGTH, 10);
                    final Callback.Completable sent = new Callback.Completable();
                    Content.Sink.write(response, false, "nope!", sent);
                    sent.get(10, TimeUnit.SECONDS);
                    request.getConnectionMetaData().getConnection().getEndPoint().close();
                    callback.failed(new IOException("The test closed the connection"));
                }
                return true;
            }
        });
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            final Invocation.Builder request = client().target(baseUri()).request();
            final Response response = async ? request.async().get().get(5, TimeUnit.SECONDS) : request.get();
            assertEquals(401, response.getStatus());
            assertThrows(ProcessingException.class, () -> response.readEntity(String.class));
        });
    }

    /**
     * A HEAD response has a {@code Content-Length} but no body. A 401 without a challenge to a HEAD request returns
     * the 401 on the asynchronous path, also with a callback, which reads the entity before the call completes.
     */
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    public void unchallengedUnauthorizedHeadReturnsResponse(final boolean withCallback) throws Exception {
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(final Request request, final org.eclipse.jetty.server.Response response,
                    final Callback callback) {
                response.setStatus(401);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, MediaType.TEXT_PLAIN);
                response.getHeaders().put(HttpHeader.CONTENT_LENGTH, 5);
                callback.succeeded();
                return true;
            }
        });
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            final AsyncInvoker invoker = client().target(baseUri()).request().async();
            if (withCallback) {
                final CompletableFuture<String> outcome = new CompletableFuture<>();
                invoker.method("HEAD", recordingCallback(outcome));
                assertEquals("completed 401", outcome.get(5, TimeUnit.SECONDS));
            } else {
                assertEquals(401, invoker.head().get(5, TimeUnit.SECONDS).getStatus());
            }
        });
    }

    /**
     * Jetty reports a redirect chain that fails, such as a redirect loop, with the last redirect as the response. With
     * redirects on, that redirect is not the answer of the server, so the call fails, also when the redirect has an
     * empty body, and also when the executor runs a completion task at once.
     */
    @ParameterizedTest
    @CsvSource({ "future,", "future,moved", "callback,", "inline,", "sync,", "sync,moved" })
    public void failedRedirectChainFails(final String kind, final String body) throws Exception {
        final String content = body == null ? "" : body;
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(final Request request, final org.eclipse.jetty.server.Response response,
                    final Callback callback) {
                response.setStatus(302);
                response.getHeaders().put(HttpHeader.LOCATION, baseUri().toString());
                response.getHeaders().put(HttpHeader.CONTENT_LENGTH, content.length());
                Content.Sink.write(response, true, content, callback);
                return true;
            }
        });
        server.start();
        final ExecutorService executor = "inline".equals(kind) ? new InlineExecutor() : Executors.newCachedThreadPool();
        client = new ResteasyClientBuilderImpl()
                .executorService(executor)
                .setFollowRedirects(true)
                .httpEngine(new JettyClientHttpEngine(new HttpClient(), 0, TimeUnit.MILLISECONDS))
                .build();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> assertCallFails(client.target(baseUri()).request(), "inline".equals(kind) ? "future" : kind,
                            HttpResponseException.class));
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Jetty follows a redirect to a {@code Location} with a scheme that it cannot send, and reports the failure with a
     * placeholder response without a status. The call must fail.
     */
    @ParameterizedTest
    @ValueSource(strings = { "sync", "future", "callback" })
    public void redirectToUnsupportedSchemeFails(final String kind) throws Exception {
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(final Request request, final org.eclipse.jetty.server.Response response,
                    final Callback callback) {
                response.setStatus(302);
                response.getHeaders().put(HttpHeader.LOCATION, "myapp://callback?code=1");
                response.getHeaders().put(HttpHeader.CONTENT_LENGTH, 0);
                callback.succeeded();
                return true;
            }
        });
        server.start();
        client = new ResteasyClientBuilderImpl().setFollowRedirects(true).build();
        assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> assertCallFails(client.target(baseUri()).request(), kind, IllegalArgumentException.class));
    }

    /**
     * Jetty cannot follow a redirect without a {@code Location}, or with a {@code Location} that it cannot parse, and
     * replays the redirect with a failure and without its body. The redirect is the answer of the server, so the call
     * returns it, and the body read reports the failure.
     */
    @ParameterizedTest
    @CsvSource({ "sync,", "sync,http://[bad", "future,", "future,http://[bad" })
    public void redirectWithoutUsableLocationReturnsRedirect(final String kind, final String location) throws Exception {
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(final Request request, final org.eclipse.jetty.server.Response response,
                    final Callback callback) {
                response.setStatus(302);
                if (location != null) {
                    response.getHeaders().put(HttpHeader.LOCATION, location);
                }
                response.getHeaders().put(HttpHeader.CONTENT_LENGTH, 5);
                Content.Sink.write(response, true, "moved", callback);
                return true;
            }
        });
        server.start();
        client = new ResteasyClientBuilderImpl().setFollowRedirects(true)
                .httpEngine(new JettyClientHttpEngine(new HttpClient(), 0, TimeUnit.MILLISECONDS))
                .build();
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            final Invocation.Builder request = client.target(baseUri()).request();
            final Response response = "sync".equals(kind) ? request.get()
                    : request.async().get().get(5, TimeUnit.SECONDS);
            assertEquals(302, response.getStatus());
            final ProcessingException failure = assertThrows(ProcessingException.class,
                    () -> response.readEntity(String.class));
            assertTrue(hasCause(failure, HttpResponseException.class), () -> "unexpected cause chain: " + failure);
        });
    }

    /**
     * Jetty cannot parse the challenge of a 401 or a 407 in these headers, and replays the response with a failure and
     * with its buffered body. The first header has parameters without a scheme, and the second header does not match
     * the challenge syntax. The response is the answer of the server, so the call returns it with its body.
     */
    @ParameterizedTest
    @CsvSource({
            "401,sync,realm=\"test\"",
            "401,future,realm=\"test\"",
            "401,callback,realm=\"test\"",
            "401,sync,\"test\"",
            "407,sync,realm=\"test\"",
            "407,future,realm=\"test\"",
            "407,callback,\"test\"",
    })
    public void unparseableChallengeReturnsResponse(final int status, final String kind, final String challenge)
            throws Exception {
        final HttpHeader header = status == 401 ? HttpHeader.WWW_AUTHENTICATE : HttpHeader.PROXY_AUTHENTICATE;
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(final Request request, final org.eclipse.jetty.server.Response response,
                    final Callback callback) {
                response.setStatus(status);
                response.getHeaders().put(header, challenge);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, MediaType.TEXT_PLAIN);
                response.getHeaders().put(HttpHeader.CONTENT_LENGTH, 5);
                Content.Sink.write(response, true, "nope!", callback);
                return true;
            }
        });
        server.start();
        final HttpClient httpClient = new HttpClient();
        final URI target = challengeTarget(status, httpClient);
        client = new ResteasyClientBuilderImpl()
                .httpEngine(new JettyClientHttpEngine(httpClient, 0, TimeUnit.MILLISECONDS))
                .build();
        assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> assertEquals(status + " nope!", statusAndBody(client.target(target).request(), kind)));
    }

    /**
     * The authentication handler of Jetty sends the request again with the credentials for a parseable challenge of a
     * 401 or a 407, and the call returns the response to that request. Without credentials, the call returns the
     * challenge with its body.
     */
    @ParameterizedTest
    @CsvSource({
            "401,sync,true",
            "401,future,true",
            "407,callback,true",
            "401,callback,false",
            "407,sync,false",
            "407,future,false",
    })
    public void parseableChallengeUsesCredentials(final int status, final String kind, final boolean credentials)
            throws Exception {
        final HttpHeader challenge = status == 401 ? HttpHeader.WWW_AUTHENTICATE : HttpHeader.PROXY_AUTHENTICATE;
        final HttpHeader authorization = status == 401 ? HttpHeader.AUTHORIZATION : HttpHeader.PROXY_AUTHORIZATION;
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(final Request request, final org.eclipse.jetty.server.Response response,
                    final Callback callback) {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, MediaType.TEXT_PLAIN);
                if (request.getHeaders().contains(authorization)) {
                    Content.Sink.write(response, true, "ok", callback);
                } else {
                    response.setStatus(status);
                    response.getHeaders().put(challenge, "Basic realm=\"test\"");
                    Content.Sink.write(response, true, "nope!", callback);
                }
                return true;
            }
        });
        server.start();
        final HttpClient httpClient = new HttpClient();
        final URI target = challengeTarget(status, httpClient);
        if (credentials) {
            httpClient.getAuthenticationStore()
                    .addAuthentication(new BasicAuthentication(baseUri(), "test", "user", "secret"));
        }
        final ExecutorService executor = Executors.newCachedThreadPool();
        client = clientWithExecutor(executor, httpClient);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> assertEquals(credentials ? "200 ok" : status + " nope!",
                            statusAndBody(client.target(target).request(), kind)));
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Jetty asks the protocol handlers when a response begins, before the headers arrive. A protocol handler that
     * tests a response header then does not take the response, so the response is live, and the call returns it
     * without a wait for the end of the body.
     */
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    public void protocolHandlerThatTestsHeaderDoesNotTakeLiveResponse(final boolean async) throws Exception {
        final String body = "a".repeat(256 * 1024);
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(final Request request, final org.eclipse.jetty.server.Response response,
                    final Callback callback) {
                response.setStatus(200);
                response.getHeaders().put("X-Handled", "true");
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, MediaType.TEXT_PLAIN);
                Content.Sink.write(response, true, body, callback);
                return true;
            }
        });
        server.start();
        final HttpClient httpClient = new HttpClient();
        httpClient.getProtocolHandlers().put(new ProtocolHandler() {
            @Override
            public String getName() {
                return "header";
            }

            @Override
            public boolean accept(final org.eclipse.jetty.client.Request request,
                    final org.eclipse.jetty.client.Response response) {
                return response.getHeaders().contains("X-Handled");
            }

            @Override
            public org.eclipse.jetty.client.Response.Listener getResponseListener() {
                return new org.eclipse.jetty.client.Response.Listener() {
                };
            }
        });
        client = new ResteasyClientBuilderImpl()
                .httpEngine(new JettyClientHttpEngine(httpClient, 5, TimeUnit.SECONDS))
                .build();
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            final Invocation.Builder request = client.target(baseUri()).request();
            final Response response = async ? request.async().get().get(5, TimeUnit.SECONDS) : request.get();
            assertEquals(200, response.getStatus());
            assertEquals(body, response.readEntity(String.class));
        });
    }

    /**
     * Jetty replays a 401 with a challenge when its {@link Authentication} fails, and then reports that failure. The
     * 401 is not the answer to the request, so the call must fail.
     */
    @ParameterizedTest
    @ValueSource(strings = { "sync", "future", "callback" })
    public void authenticationFailureFails(final String kind) throws Exception {
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(final Request request, final org.eclipse.jetty.server.Response response,
                    final Callback callback) {
                response.setStatus(401);
                response.getHeaders().put(HttpHeader.WWW_AUTHENTICATE, "Basic realm=\"test\"");
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, MediaType.TEXT_PLAIN);
                Content.Sink.write(response, true, "denied", callback);
                return true;
            }
        });
        server.start();
        final HttpClient httpClient = new HttpClient();
        httpClient.getAuthenticationStore().addAuthentication(new Authentication() {
            @Override
            public boolean matches(final String type, final URI uri, final String realm) {
                return true;
            }

            @Override
            public Result authenticate(final org.eclipse.jetty.client.Request request, final ContentResponse response,
                    final HeaderInfo headerInfo, final Attributes context) {
                throw new IllegalStateException("The test failed the authentication");
            }
        });
        final ExecutorService executor = Executors.newCachedThreadPool();
        client = clientWithExecutor(executor, httpClient);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> assertCallFails(client.target(baseUri()).request(), kind, IllegalStateException.class));
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * A 204 or a 304 has no body. A request failure after its headers arrived must not fail the call, also with a
     * callback, which reads the entity before the call completes.
     * <p>
     * The server answers before it reads the request body. A hook fails the request when the response headers arrive,
     * while the body writer waits. Jetty then fails the response before it parses the end of the message.
     */
    @ParameterizedTest
    @ValueSource(ints = { 204, 304 })
    public void noBodyResponseSurvivesRequestFailure(final int status) throws Exception {
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(final Request request, final org.eclipse.jetty.server.Response response,
                    final Callback callback) {
                response.setStatus(status);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, MediaType.TEXT_PLAIN);
                if (status == 304) {
                    // The length of the representation that the 304 refers to.
                    response.getHeaders().put(HttpHeader.CONTENT_LENGTH, 5);
                }
                callback.succeeded();
                return true;
            }
        });
        server.start();
        final CountDownLatch release = new CountDownLatch(1);
        final ExecutorService executor = Executors.newCachedThreadPool();
        client = clientWithExecutor(executor, new HttpClient() {
            @Override
            public org.eclipse.jetty.client.Request newRequest(final URI uri) {
                return super.newRequest(uri).onResponseHeaders(
                        response -> response.getRequest().fail(new IOException("The test failed the request")));
            }
        });
        final StreamingOutput writer = output -> {
            output.write('x');
            output.flush();
            await(release);
        };
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                final CompletableFuture<String> outcome = new CompletableFuture<>();
                client.target(baseUri()).request().async()
                        .post(Entity.entity(writer, MediaType.TEXT_PLAIN_TYPE), recordingCallback(outcome));
                assertEquals("completed " + status, outcome.get(5, TimeUnit.SECONDS));
            });
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * A server can answer early with a body that has no {@code Content-Length}: a chunked body, or a gzip body that
     * Jetty decodes. The body must stay readable when the body write fails after the whole response arrived, and
     * before the application read the body.
     * <p>
     * The body writer waits until the server closed the connection and Jetty reported the end of the response, or at
     * most 5 seconds. Jetty reports that end before the application reads only if the listener reads ahead. Then the
     * writer writes until a write fails, and only then does the test read the body.
     */
    @ParameterizedTest
    @CsvSource({ "chunked,false", "chunked,true", "gzip,false", "gzip,true" })
    public void earlyResponseKeepsBodyWithoutContentLength(final String framing, final boolean async) throws Exception {
        final CountDownLatch serverClosed = startEarlyRejectingServer(500, "nope!", framing);
        final CountDownLatch responseComplete = new CountDownLatch(1);
        final CountDownLatch writeFailed = new CountDownLatch(1);
        final ExecutorService executor = Executors.newCachedThreadPool();
        client = clientWithExecutor(executor, new HttpClient() {
            @Override
            public org.eclipse.jetty.client.Request newRequest(final URI uri) {
                return super.newRequest(uri).onResponseSuccess(response -> responseComplete.countDown());
            }
        });
        final StreamingOutput writer = output -> {
            try {
                output.write('x');
                output.flush();
                await(serverClosed);
                awaitAtMost(responseComplete, 5);
                final byte[] chunk = new byte[8 * 1024];
                while (true) {
                    output.write(chunk);
                    output.flush();
                }
            } finally {
                writeFailed.countDown();
            }
        };
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
                final Invocation.Builder request = client.target(baseUri()).request();
                final Entity<StreamingOutput> entity = Entity.entity(writer, MediaType.TEXT_PLAIN_TYPE);
                final Response response = async ? request.async().post(entity).get(10, TimeUnit.SECONDS)
                        : request.post(entity);
                await(writeFailed);
                assertEquals(500, response.getStatus());
                assertEquals("nope!", response.readEntity(String.class));
            });
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * With {@code Expect: 100-continue}, the continue handler of Jetty takes a final answer that comes without a 100,
     * and replays it, also when the response fails. A 500 or a 401 whose body the server cuts short is the answer of
     * the server, so the call returns its status, and the body read fails.
     */
    @ParameterizedTest
    @CsvSource({ "500,false", "500,true", "401,false", "401,true" })
    public void expectContinueTruncatedResponseKeepsStatus(final int status, final boolean async) throws Exception {
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(final Request request, final org.eclipse.jetty.server.Response response,
                    final Callback callback) throws Exception {
                response.setStatus(status);
                if (status == 401) {
                    response.getHeaders().put(HttpHeader.WWW_AUTHENTICATE, "Basic realm=\"test\"");
                }
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, MediaType.TEXT_PLAIN);
                response.getHeaders().put(HttpHeader.CONTENT_LENGTH, 10);
                final Callback.Completable sent = new Callback.Completable();
                Content.Sink.write(response, false, "nope!", sent);
                sent.get(10, TimeUnit.SECONDS);
                request.getConnectionMetaData().getConnection().getEndPoint().close();
                callback.failed(new IOException("The test closed the connection"));
                return true;
            }
        });
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            final Invocation.Builder request = client().target(baseUri()).request()
                    .header(HttpHeader.EXPECT.asString(), HttpHeaderValue.CONTINUE.asString());
            final Entity<String> entity = Entity.text("hello");
            final Response response = async ? request.async().post(entity).get(5, TimeUnit.SECONDS)
                    : request.post(entity);
            assertEquals(status, response.getStatus());
            assertThrows(ProcessingException.class, () -> response.readEntity(String.class));
        });
    }

    /**
     * Returns the target of a call that gets a {@code status} from the server. Jetty 12.1.14 and later take a 407 only
     * from a proxy. So for a 407, {@code httpClient} uses the server as its HTTP proxy, and the target host is one that
     * only the proxy can reach.
     */
    private URI challengeTarget(final int status, final HttpClient httpClient) {
        if (status != 407) {
            return baseUri();
        }
        httpClient.getProxyConfiguration().addProxy(new HttpProxy("localhost", baseUri().getPort()));
        return URI.create("http://origin.test/");
    }

    /**
     * Asserts that the GET call of {@code kind} (sync, future or callback) fails with {@code cause} in its cause
     * chain.
     */
    private static void assertCallFails(final Invocation.Builder request, final String kind,
            final Class<? extends Throwable> cause) throws Exception {
        final Throwable failure;
        if ("sync".equals(kind)) {
            failure = assertThrows(ProcessingException.class, () -> request.get().close());
        } else if ("future".equals(kind)) {
            failure = assertThrows(ExecutionException.class, () -> request.async().get().get(5, TimeUnit.SECONDS));
        } else {
            final CompletableFuture<Throwable> failed = new CompletableFuture<>();
            request.async().get(new InvocationCallback<Response>() {
                @Override
                public void completed(final Response response) {
                    failed.completeExceptionally(new AssertionError("completed " + response.getStatus()));
                }

                @Override
                public void failed(final Throwable throwable) {
                    failed.complete(throwable);
                }
            });
            failure = failed.get(5, TimeUnit.SECONDS);
        }
        assertTrue(hasCause(failure, cause), () -> "unexpected cause chain: " + failure);
    }

    /**
     * Makes the GET call of {@code kind} (sync, future or callback), and returns the status and the body of the
     * response, separated by a space.
     */
    private static String statusAndBody(final Invocation.Builder request, final String kind) throws Exception {
        if ("sync".equals(kind)) {
            return statusAndBody(request.get());
        }
        if ("future".equals(kind)) {
            return statusAndBody(request.async().get().get(5, TimeUnit.SECONDS));
        }
        final CompletableFuture<String> outcome = new CompletableFuture<>();
        request.async().get(new InvocationCallback<Response>() {
            @Override
            public void completed(final Response response) {
                try {
                    outcome.complete(statusAndBody(response));
                } catch (RuntimeException e) {
                    outcome.complete("read failed " + e);
                }
            }

            @Override
            public void failed(final Throwable throwable) {
                outcome.complete("failed " + throwable);
            }
        });
        return outcome.get(5, TimeUnit.SECONDS);
    }

    private static String statusAndBody(final Response response) {
        return response.getStatus() + " " + response.readEntity(String.class);
    }

    /**
     * A callback that records its outcome in {@code outcome}, with the status of a response.
     */
    private static InvocationCallback<Response> recordingCallback(final CompletableFuture<String> outcome) {
        return new InvocationCallback<>() {
            @Override
            public void completed(final Response response) {
                outcome.complete("completed " + response.getStatus());
            }

            @Override
            public void failed(final Throwable throwable) {
                outcome.complete("failed " + throwable);
            }
        };
    }

    /**
     * Starts a server that reads one byte of the request body, answers {@code status} and {@code body} with
     * {@code Connection: close}, and closes the connection. A 401 carries a challenge, so that the authentication
     * handler of Jetty forwards it as a success. The body has a {@code Content-Length}.
     *
     * @return a latch that opens when the server closed the connection
     */
    private CountDownLatch startEarlyRejectingServer(final int status, final String body) throws Exception {
        return startEarlyRejectingServer(status, body, "length");
    }

    /**
     * The variant of {@link #startEarlyRejectingServer(int, String)} with the framing of the body: {@code length},
     * {@code chunked}, or {@code gzip}, which is chunked too. A chunked body goes out in two writes.
     */
    private CountDownLatch startEarlyRejectingServer(final int status, final String body, final String framing)
            throws Exception {
        final byte[] bytes = "gzip".equals(framing) ? gzip(body) : body.getBytes(StandardCharsets.UTF_8);
        final CountDownLatch serverClosed = new CountDownLatch(1);
        server.getConnectors()[0].addBean(new Connection.Listener() {
            @Override
            public void onClosed(final Connection connection) {
                serverClosed.countDown();
            }
        });
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(final Request request, final org.eclipse.jetty.server.Response response,
                    final Callback callback) throws IOException {
                // Read the byte that the client sent first, so that the close is a FIN and not a reset that could
                // discard the response on the client.
                Content.Source.asInputStream(request).read();
                response.setStatus(status);
                response.getHeaders().put(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString());
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, MediaType.TEXT_PLAIN);
                if (status == 401) {
                    response.getHeaders().put(HttpHeader.WWW_AUTHENTICATE, "Bearer realm=\"test\"");
                }
                int first = 0;
                if ("length".equals(framing)) {
                    response.getHeaders().put(HttpHeader.CONTENT_LENGTH, bytes.length);
                } else {
                    if ("gzip".equals(framing)) {
                        response.getHeaders().put(HttpHeader.CONTENT_ENCODING, "gzip");
                    }
                    // A first write without the last flag commits the headers without a Content-Length.
                    first = bytes.length / 2;
                    final Callback.Completable sent = new Callback.Completable();
                    response.write(false, ByteBuffer.wrap(bytes, 0, first), sent);
                    try {
                        sent.get(10, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IOException(e);
                    }
                }
                // With only Connection: close, the server shuts down its output and continues to read, so the
                // body writes of the client never fail.
                final EndPoint endPoint = request.getConnectionMetaData().getConnection().getEndPoint();
                response.write(true, ByteBuffer.wrap(bytes, first, bytes.length - first), Callback.from(() -> {
                    endPoint.close();
                    callback.succeeded();
                }, callback::failed));
                return true;
            }
        });
        server.start();
        return serverClosed;
    }

    /**
     * An entity that sends one byte, waits for {@code latches}, and then writes until a write fails.
     */
    private static StreamingOutput writeUntilFailure(final CountDownLatch... latches) {
        return output -> {
            output.write('x');
            output.flush();
            for (CountDownLatch latch : latches) {
                await(latch);
            }
            final byte[] chunk = new byte[8 * 1024];
            while (true) {
                output.write(chunk);
                output.flush();
            }
        };
    }

    private static byte[] gzip(final String content) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    /**
     * Waits until {@code latch} opens, or at most {@code seconds}.
     */
    private static void awaitAtMost(final CountDownLatch latch, final long seconds) {
        try {
            latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch did not open");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * An {@link HttpClient} whose requests open {@code headersReceived} when the response headers arrived.
     */
    private static HttpClient notifyingHttpClient(final CountDownLatch headersReceived) {
        return new HttpClient() {
            @Override
            public org.eclipse.jetty.client.Request newRequest(final URI uri) {
                return super.newRequest(uri).onResponseHeaders(response -> headersReceived.countDown());
            }
        };
    }

    private static ResteasyClient clientWithExecutor(final ExecutorService executor) {
        return clientWithExecutor(executor, new HttpClient());
    }

    private static ResteasyClient clientWithExecutor(final ExecutorService executor, final HttpClient httpClient) {
        return (ResteasyClient) new ResteasyClientBuilderImpl()
                .executorService(executor)
                .httpEngine(new JettyClientHttpEngine(httpClient, 0, TimeUnit.MILLISECONDS))
                .build();
    }

    /**
     * Delegating {@link ExecutorService} that runs each task only after {@code gate} opens.
     */
    private static final class GatedExecutor extends AbstractExecutorService {
        private final ExecutorService delegate;
        private final CountDownLatch gate;

        GatedExecutor(final ExecutorService delegate, final CountDownLatch gate) {
            this.delegate = delegate;
            this.gate = gate;
        }

        @Override
        public void execute(final Runnable command) {
            delegate.execute(() -> {
                await(gate);
                command.run();
            });
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

    /**
     * Delegating {@link ExecutorService} that opens {@link #firstTaskDone} when the first task it runs is complete. On
     * the synchronous call path, the first task is the body writer.
     */
    private static final class TaskDoneExecutor extends AbstractExecutorService {
        private final ExecutorService delegate;
        private final AtomicBoolean first = new AtomicBoolean(true);
        final CountDownLatch firstTaskDone = new CountDownLatch(1);

        TaskDoneExecutor(final ExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(final Runnable command) {
            final boolean isFirst = first.getAndSet(false);
            delegate.execute(() -> {
                try {
                    command.run();
                } finally {
                    if (isFirst) {
                        firstTaskDone.countDown();
                    }
                }
            });
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

    /**
     * {@link ExecutorService} that runs each task on the calling thread.
     */
    private static final class InlineExecutor extends AbstractExecutorService {
        private volatile boolean shutdown;

        @Override
        public void execute(final Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(final long timeout, final TimeUnit unit) {
            return true;
        }
    }

    /**
     * Delegating {@link ExecutorService} that runs a submitted task only after {@code gate} opens. The body writer and
     * the tasks of RESTEasy use {@code execute}, and only the completion task uses {@code submit}.
     */
    private static final class CompletionGatedExecutor extends AbstractExecutorService {
        private final ExecutorService delegate;
        private final CountDownLatch gate;

        CompletionGatedExecutor(final ExecutorService delegate, final CountDownLatch gate) {
            this.delegate = delegate;
            this.gate = gate;
        }

        @Override
        protected <T> RunnableFuture<T> newTaskFor(final Runnable runnable, final T value) {
            return super.newTaskFor(() -> {
                await(gate);
                runnable.run();
            }, value);
        }

        @Override
        public void execute(final Runnable command) {
            delegate.execute(command);
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

    /**
     * Reads the whole request body, and only then answers 200.
     */
    static class ReadBodyHandler extends Handler.Abstract {

        @Override
        public boolean handle(final Request request, final org.eclipse.jetty.server.Response response, final Callback callback)
                throws Exception {
            Content.Source.asString(request);
            response.setStatus(200);
            callback.succeeded();
            return true;
        }
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
