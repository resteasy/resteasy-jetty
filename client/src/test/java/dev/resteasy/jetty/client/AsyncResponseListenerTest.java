/*
 * Copyright The RESTEasy Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.resteasy.jetty.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.InvocationCallback;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpResponseException;
import org.eclipse.jetty.client.OutputStreamRequestContent;
import org.eclipse.jetty.client.RedirectProtocolHandler;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.client.WWWAuthenticationProtocolHandler;
import org.eclipse.jetty.client.transport.HttpResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EofException;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.internal.ClientInvocation;
import org.jboss.resteasy.client.jaxrs.internal.ClientInvocationBuilder;
import org.jboss.resteasy.client.jaxrs.internal.ClientResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Calls the Jetty events of one exchange in a fixed order, and runs the queued completion task only when the test
 * says so. Each test is one order of events that a real exchange can produce. The client has the redirect and the
 * authentication protocol handlers of Jetty. The listener asks them in {@code onBegin}, so a response is live when a
 * test does not call {@code onBegin}.
 */
public class AsyncResponseListenerTest {
    private final ManualExecutor executor = new ManualExecutor();
    private final HttpClient httpClient = new HttpClient();
    private final CompletableFuture<ClientResponse> future = new CompletableFuture<>();
    private final List<String> outcomes = new CopyOnWriteArrayList<>();
    private final OutputStreamRequestContent requestContent = new OutputStreamRequestContent();
    private ResteasyClient client;
    private ClientInvocation invocation;
    private Request request;
    private HttpResponse response;
    private AsyncResponseListener<ClientResponse> listener;

    @BeforeEach
    public void setUp() {
        client = (ResteasyClient) ClientBuilder.newClient();
        invocation = ((ClientInvocationBuilder) client.target("http://localhost/").request()).getClientInvocation();
        httpClient.getProtocolHandlers().put(new RedirectProtocolHandler(httpClient));
        httpClient.getProtocolHandlers().put(new WWWAuthenticationProtocolHandler(httpClient));
        request = httpClient.newRequest("http://localhost/");
        response = new HttpResponse(request).status(401);
        listener = listener(invocation, httpClient);
    }

    private AsyncResponseListener<ClientResponse> listener(final ClientInvocation clientInvocation,
            final HttpClient jettyClient) {
        return new AsyncResponseListener<>(clientInvocation, false, new InvocationCallback<>() {
            @Override
            public void completed(final ClientResponse result) {
                outcomes.add("completed " + result.getStatus());
            }

            @Override
            public void failed(final Throwable throwable) {
                outcomes.add("failed " + throwable);
            }
        }, null, future, executor, jettyClient, requestContent);
    }

    @AfterEach
    public void tearDown() {
        client.close();
    }

    /**
     * Jetty reports a request-side failure, such as a broken pipe on the body write, after the whole response
     * arrived. The body of that response must stay readable.
     */
    @Test
    public void requestFailureAfterWholeResponseKeepsBody() {
        listener.onHeaders(response);
        listener.onContent(response, chunk("nope!"), () -> {
        });
        listener.onSuccess(response);
        listener.onComplete(new Result(request, new EofException("Broken pipe"), response));
        executor.runAll();

        final ClientResponse result = future.getNow(null);
        assertEquals(401, result.getStatus());
        assertEquals("nope!", result.readEntity(String.class));
        assertEquals(List.of("completed 401"), outcomes);
    }

    /**
     * The response fails after its headers and its whole {@code Content-Length} body arrived, before the completion
     * task runs. Jetty does this over HTTP/1.1 when the body write fails before the application read the body, and
     * for a 401 without a challenge. The call returns the response with its body.
     */
    @Test
    public void responseFailureAfterWholeContentLengthBodyKeepsBody() {
        final EofException failure = new EofException("Broken pipe");
        response.headers(headers -> headers.put(HttpHeader.CONTENT_LENGTH, 5));
        listener.onHeaders(response);
        listener.onContent(response, chunk("nope!"), () -> {
        });
        listener.onFailure(response, failure);
        listener.onComplete(new Result(request, failure, response, failure));
        executor.runAll();

        final ClientResponse result = future.getNow(null);
        assertEquals(401, result.getStatus());
        assertEquals("nope!", result.readEntity(String.class));
        assertEquals(List.of("completed 401"), outcomes);
    }

    /**
     * The response fails after its headers arrived but before its whole body arrived, and before the completion
     * task runs. The call returns the response, and the body read reports the failure.
     */
    @Test
    public void responseFailureCutsBodyShort() {
        final EofException failure = new EofException("reset");
        response.headers(headers -> headers.put(HttpHeader.CONTENT_LENGTH, 5));
        listener.onHeaders(response);
        listener.onContent(response, chunk("no"), () -> {
        });
        listener.onFailure(response, failure);
        listener.onComplete(new Result(request, null, response, failure));
        executor.runAll();

        final ClientResponse result = future.getNow(null);
        assertEquals(401, result.getStatus());
        assertThrows(ProcessingException.class, () -> result.readEntity(String.class));
        assertEquals(List.of("completed 401"), outcomes);
    }

    /**
     * The caller cancels the future before the completion task runs. The callback gets no outcome after the
     * cancel, and the engine closes the response that nobody will receive.
     */
    @Test
    public void completionAfterCancelClosesResponseAndSkipsCallback() {
        listener.onHeaders(response);
        assertTrue(future.cancel(false));
        executor.runAll();

        assertEquals(List.of(), outcomes);
        // A closed listener stream aborts the exchange on the next content chunk.
        listener.onContent(response, chunk("late"), () -> {
        });
        assertInstanceOf(AsynchronousCloseException.class, request.getAbortCause());
    }

    /**
     * The callback throws from {@code completed}. The future already has the response, so the listener logs the
     * exception at warn level and leaves the response open for the caller of the future.
     */
    @Test
    public void callbackExceptionKeepsResponseOpen() {
        final IllegalStateException failure = new IllegalStateException("The test callback failed");
        listener = new AsyncResponseListener<>(invocation, false, new InvocationCallback<>() {
            @Override
            public void completed(final ClientResponse result) {
                throw failure;
            }

            @Override
            public void failed(final Throwable throwable) {
                outcomes.add("failed " + throwable);
            }
        }, null, future, executor, httpClient, requestContent);
        final List<LogRecord> records = new CopyOnWriteArrayList<>();
        final Handler handler = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        final Logger log = Logger.getLogger(AsyncResponseListener.class.getName());
        log.addHandler(handler);
        try {
            response.headers(headers -> headers.put(HttpHeader.CONTENT_LENGTH, 5));
            listener.onHeaders(response);
            listener.onContent(response, chunk("nope!"), () -> {
            });
            listener.onSuccess(response);
            listener.onComplete(new Result(request, response));
            executor.runAll();
        } finally {
            log.removeHandler(handler);
        }

        assertEquals("nope!", future.getNow(null).readEntity(String.class));
        assertEquals(List.of(), outcomes);
        assertEquals(1, records.size());
        assertEquals(Level.WARNING, records.get(0).getLevel());
        assertEquals(failure, records.get(0).getThrown());
    }

    /**
     * The caller cancels the future, and then the exchange fails before any response arrived. The callback gets no
     * outcome after the cancel.
     */
    @Test
    public void failureAfterCancelSkipsCallback() {
        assertTrue(future.cancel(false));
        listener.onFailure(response, new EofException("reset"));

        assertEquals(List.of(), outcomes);
    }

    /**
     * A protocol handler buffered the whole body, for example of a 401 without a challenge, forwards it as a
     * {@link ContentResponse}, and then fails the response. The body stays readable.
     */
    @Test
    public void responseFailureOfBufferedResponseKeepsBody() {
        final EofException failure = new EofException("protocol violation");
        final ContentResponse buffered = ContentResponse.from(response, "nope!".getBytes(StandardCharsets.UTF_8),
                "text/plain", "UTF-8");
        listener.onHeaders(buffered);
        listener.onContent(buffered, chunk("nope!", true), () -> {
        });
        listener.onFailure(buffered, failure);
        listener.onComplete(new Result(request, null, buffered, failure));
        executor.runAll();

        final ClientResponse result = future.getNow(null);
        assertEquals(401, result.getStatus());
        assertEquals("nope!", result.readEntity(String.class));
        assertEquals(List.of("completed 401"), outcomes);
    }

    /**
     * A protocol handler forwards an empty {@link ContentResponse} when it could not buffer the body, and also when the
     * body is empty. The listener cannot tell the two apart, so the body read fails.
     */
    @Test
    public void responseFailureOfEmptyBufferedResponseFailsBodyRead() {
        final EofException failure = new EofException("protocol violation");
        final ContentResponse buffered = ContentResponse.from(response, new byte[0], "text/plain", "UTF-8");
        listener.onHeaders(buffered);
        listener.onFailure(buffered, failure);
        listener.onComplete(new Result(request, null, buffered, failure));
        executor.runAll();

        final ClientResponse result = future.getNow(null);
        assertEquals(401, result.getStatus());
        assertThrows(ProcessingException.class, () -> result.readEntity(String.class));
        assertEquals(List.of("completed 401"), outcomes);
    }

    /**
     * The engine fails to build the response from the headers. The call fails with that exception, and the callback
     * gets exactly one outcome.
     */
    @Test
    public void responseConstructionFailureFailsCall() {
        final IllegalStateException failure = new IllegalStateException("boom");
        listener = listener(new ClientInvocation(invocation) {
            @Override
            public Map<String, Object> getMutableProperties() {
                throw failure;
            }
        }, httpClient);
        try {
            listener.onHeaders(response);
        } catch (RuntimeException e) {
            // Jetty logs and discards an exception from a listener. The future must not depend on it.
        }
        executor.runAll();

        final CompletionException e = assertThrows(CompletionException.class, () -> future.getNow(null));
        assertEquals(failure, e.getCause().getCause());
        assertEquals(1, outcomes.size());
    }

    /**
     * Jetty cannot send the request, for example to a scheme other than http or https, and reports only a failed
     * completion. The call fails, the callback gets exactly one outcome, and the request content fails, so that a
     * body writer stops.
     */
    @Test
    public void failedCompletionWithoutResponseFailsCallAndRequestContent() {
        final IllegalArgumentException failure = new IllegalArgumentException("Invalid protocol ftp");
        listener.onComplete(new Result(request, failure, response, failure));

        final CompletionException e = assertThrows(CompletionException.class, () -> future.getNow(null));
        assertInstanceOf(ProcessingException.class, e.getCause());
        assertEquals(failure, e.getCause().getCause());
        assertEquals(1, outcomes.size());
        assertTrue(Content.Chunk.isFailure(requestContent.read()));
    }

    /**
     * Jetty reports a redirect chain that fails, such as a redirect loop, with the headers of the last redirect and
     * then the failure. The call fails, also when the executor runs its tasks between the two events.
     */
    @Test
    public void failedRedirectChainFailsCall() {
        final HttpResponse redirect = new HttpResponse(request).status(302);
        redirect.headers(headers -> headers.put(HttpHeader.LOCATION, "http://localhost/")
                .put(HttpHeader.CONTENT_LENGTH, 0));
        final HttpResponseException failure = new HttpResponseException("Max redirects exceeded 8", redirect);
        listener.onBegin(redirect);
        listener.onHeaders(redirect);
        executor.runAll();
        listener.onFailure(redirect, failure);
        listener.onComplete(new Result(request, null, redirect, failure));
        executor.runAll();

        final CompletionException e = assertThrows(CompletionException.class, () -> future.getNow(null));
        assertEquals(failure, e.getCause().getCause());
        assertEquals(1, outcomes.size());
    }

    /**
     * Jetty cannot follow a redirect without a usable {@code Location}, and replays it with a failure and without its
     * body. The redirect is the answer of the server, so the call returns it when its headers arrive, and the body
     * read reports the failure.
     */
    @Test
    public void redirectWithoutLocationCompletesAtHeaders() {
        final HttpResponse redirect = new HttpResponse(request).status(302);
        redirect.headers(headers -> headers.put(HttpHeader.CONTENT_LENGTH, 5));
        final HttpResponseException failure = new HttpResponseException("Invalid 'Location' header: null", redirect);
        listener.onBegin(redirect);
        listener.onHeaders(redirect);
        listener.onFailure(redirect, failure);
        listener.onComplete(new Result(request, null, redirect, failure));
        executor.runAll();

        final ClientResponse result = future.getNow(null);
        assertEquals(302, result.getStatus());
        assertThrows(ProcessingException.class, () -> result.readEntity(String.class));
        assertEquals(List.of("completed 302"), outcomes);
    }

    /**
     * Jetty cannot follow a redirect when it cannot send the request body again. It then reports the redirect as a
     * success, and the call returns the redirect.
     */
    @Test
    public void redirectThatJettyCannotFollowCompletesCall() {
        final HttpResponse redirect = new HttpResponse(request).status(307);
        redirect.headers(headers -> headers.put(HttpHeader.LOCATION, "http://localhost/next"));
        listener.onBegin(redirect);
        listener.onHeaders(redirect);
        executor.runAll();
        assertEquals(List.of(), outcomes);
        assertFalse(future.isDone());
        listener.onSuccess(redirect);
        listener.onComplete(new Result(request, redirect));
        executor.runAll();

        assertEquals(307, future.getNow(null).getStatus());
        assertEquals(List.of("completed 307"), outcomes);
    }

    /**
     * Without the redirect handler of Jetty, a redirect arrives as a live response, and the call returns it when its
     * headers arrive.
     */
    @Test
    public void redirectWithoutRedirectHandlerCompletesAtHeaders() {
        listener = listener(invocation, new HttpClient());
        final HttpResponse redirect = new HttpResponse(request).status(302);
        listener.onBegin(redirect);
        listener.onHeaders(redirect);
        executor.runAll();

        assertEquals(302, future.getNow(null).getStatus());
        assertEquals(List.of("completed 302"), outcomes);
    }

    /**
     * Jetty cannot send the request that follows a redirect, for example to a scheme other than http or https, and
     * reports that failure with a placeholder response without a status. The call fails.
     */
    @Test
    public void placeholderResponseFailsCall() {
        final HttpResponse placeholder = new HttpResponse(httpClient.newRequest("myapp://callback"));
        final IllegalArgumentException failure = new IllegalArgumentException("Invalid protocol myapp");
        listener.onHeaders(placeholder);
        executor.runAll();
        listener.onFailure(placeholder, failure);
        listener.onComplete(new Result(placeholder.getRequest(), failure, placeholder, failure));
        executor.runAll();

        final CompletionException e = assertThrows(CompletionException.class, () -> future.getNow(null));
        assertEquals(failure, e.getCause().getCause());
        assertEquals(1, outcomes.size());
    }

    /**
     * The authentication handler of Jetty replays a 401 with a challenge, with its buffered body, when the
     * authentication fails. The call fails with that failure.
     */
    @Test
    public void replayedChallengeWithFailureFailsCall() {
        response.headers(headers -> headers.put(HttpHeader.WWW_AUTHENTICATE, "Basic realm=\"test\""));
        final ContentResponse buffered = ContentResponse.from(response, "nope!".getBytes(StandardCharsets.UTF_8),
                "text/plain", "UTF-8");
        final IllegalStateException failure = new IllegalStateException("authentication failure");
        listener.onBegin(buffered);
        listener.onHeaders(buffered);
        listener.onContent(buffered, chunk("nope!", true), () -> {
        });
        executor.runAll();
        listener.onFailure(buffered, failure);
        listener.onComplete(new Result(request, null, buffered, failure));
        executor.runAll();

        final CompletionException e = assertThrows(CompletionException.class, () -> future.getNow(null));
        assertEquals(failure, e.getCause().getCause());
        assertEquals(1, outcomes.size());
    }

    /**
     * The authentication handler of Jetty replays a 401 with a challenge as a success when it has no credentials. The
     * call returns the 401 when Jetty reports the end of the replay.
     */
    @Test
    public void replayedChallengeCompletesAtSuccess() {
        response.headers(headers -> headers.put(HttpHeader.WWW_AUTHENTICATE, "Basic realm=\"test\""));
        final ContentResponse buffered = ContentResponse.from(response, "nope!".getBytes(StandardCharsets.UTF_8),
                "text/plain", "UTF-8");
        listener.onBegin(buffered);
        listener.onHeaders(buffered);
        listener.onContent(buffered, chunk("nope!", true), () -> {
        });
        executor.runAll();
        assertEquals(List.of(), outcomes);

        listener.onSuccess(buffered);
        executor.runAll();

        final ClientResponse result = future.getNow(null);
        assertEquals(401, result.getStatus());
        assertEquals("nope!", result.readEntity(String.class));
        assertEquals(List.of("completed 401"), outcomes);
    }

    /**
     * The response fails before its headers arrived. The call fails at once, also while the request still sends and
     * Jetty does not report the completion yet.
     */
    @Test
    public void responseFailureBeforeHeadersFailsCall() {
        final EofException failure = new EofException("reset");
        listener.onFailure(response, failure);

        final CompletionException e = assertThrows(CompletionException.class, () -> future.getNow(null));
        assertEquals(failure, e.getCause().getCause());
        assertEquals(1, outcomes.size());
    }

    private static Content.Chunk chunk(final String content) {
        return chunk(content, false);
    }

    private static Content.Chunk chunk(final String content, final boolean last) {
        return Content.Chunk.from(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)), last);
    }

    /**
     * Holds submitted tasks until {@link #runAll()}, so the test controls when the completion task runs.
     */
    private static final class ManualExecutor extends AbstractExecutorService {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        void runAll() {
            for (Runnable task; (task = tasks.poll()) != null;) {
                task.run();
            }
        }

        @Override
        public void execute(final Runnable command) {
            tasks.add(command);
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(final long timeout, final TimeUnit unit) {
            return false;
        }
    }
}
