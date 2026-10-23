/*
 * Copyright The RESTEasy Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.resteasy.jetty.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpResponseException;
import org.eclipse.jetty.client.OutputStreamRequestContent;
import org.eclipse.jetty.client.ProtocolHandler;
import org.eclipse.jetty.client.ProxyAuthenticationProtocolHandler;
import org.eclipse.jetty.client.RedirectProtocolHandler;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.client.WWWAuthenticationProtocolHandler;
import org.eclipse.jetty.client.transport.HttpResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EofException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Calls the Jetty events of one exchange in a fixed order on the listener of the synchronous call path, and reads
 * the body that the listener gives to the caller. The client has no protocol handlers, and the listener asks the
 * protocol handlers in {@code onBegin}. So a response is live, unless a test adds a protocol handler and calls
 * {@code onBegin}.
 */
public class BodyKeepingResponseListenerTest {
    private final HttpClient client = new HttpClient();
    private final BodyKeepingResponseListener listener = new BodyKeepingResponseListener(client, null);
    private final Request request = client.newRequest("http://localhost/");
    private final HttpResponse response = new HttpResponse(request).status(401);

    /**
     * Jetty reports a request-side failure, such as a broken pipe on the body write, after the whole response
     * arrived. The body of that response must stay readable.
     */
    @Test
    public void requestFailureAfterWholeResponseKeepsBody() throws IOException {
        listener.onHeaders(response);
        listener.onContent(response, chunk("nope!"), () -> {
        });
        listener.onSuccess(response);
        listener.onComplete(new Result(request, new EofException("Broken pipe"), response));

        assertEquals("nope!", readBody());
    }

    /**
     * The response fails after its headers and its whole {@code Content-Length} body arrived. The body must stay
     * readable.
     */
    @Test
    public void responseFailureAfterWholeContentLengthBodyKeepsBody() throws IOException {
        final EofException failure = new EofException("Broken pipe");
        response.headers(headers -> headers.put(HttpHeader.CONTENT_LENGTH, 5));
        listener.onHeaders(response);
        listener.onContent(response, chunk("nope!"), () -> {
        });
        listener.onFailure(response, failure);
        listener.onComplete(new Result(request, failure, response, failure));

        assertEquals("nope!", readBody());
    }

    /**
     * The response fails before its whole body arrived. The body read reports the failure.
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

        assertThrows(IOException.class, this::readBody);
    }

    /**
     * A protocol handler buffered the whole body, for example of a 401 without a challenge, forwards it as a
     * {@link ContentResponse}, and then fails the response. The body stays readable.
     */
    @Test
    public void responseFailureOfBufferedResponseKeepsBody() throws IOException {
        final EofException failure = new EofException("protocol violation");
        final ContentResponse buffered = ContentResponse.from(response, "nope!".getBytes(StandardCharsets.UTF_8),
                "text/plain", "UTF-8");
        listener.onHeaders(buffered);
        listener.onContent(buffered, chunk("nope!", true), () -> {
        });
        listener.onFailure(buffered, failure);
        listener.onComplete(new Result(request, null, buffered, failure));

        assertEquals("nope!", readBody());
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

        assertThrows(IOException.class, this::readBody);
    }

    /**
     * Jetty cannot send the request and reports only a failed completion. The request content fails, so that a body
     * writer stops.
     */
    @Test
    public void failedCompletionWithoutResponseFailsRequestContent() {
        final OutputStreamRequestContent requestContent = new OutputStreamRequestContent();
        final BodyKeepingResponseListener listener = new BodyKeepingResponseListener(client, requestContent);
        final IllegalArgumentException failure = new IllegalArgumentException("Invalid protocol ftp");
        listener.onComplete(new Result(request, failure, response, failure));

        assertTrue(Content.Chunk.isFailure(requestContent.read()));
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> listener.getInputStream().read()));
    }

    /**
     * A response to HEAD, a 304 and a 204 have no body, also with a {@code Content-Length}. A failure after their
     * headers ends the body stream normally.
     */
    @ParameterizedTest
    @ValueSource(strings = { "HEAD", "304", "204" })
    public void responseFailureOfResponseWithoutBodyEndsBodyNormally(final String kind) throws Exception {
        final HttpResponse noBody = "HEAD".equals(kind)
                ? new HttpResponse(client.newRequest("http://localhost/").method("HEAD")).status(401)
                : new HttpResponse(request).status(Integer.parseInt(kind));
        final EofException failure = new EofException("Broken pipe");
        noBody.headers(headers -> headers.put(HttpHeader.CONTENT_LENGTH, 5));
        listener.onHeaders(noBody);
        listener.onFailure(noBody, failure);
        listener.onComplete(new Result(noBody.getRequest(), failure, noBody, failure));

        assertSame(noBody, listener.get(0, TimeUnit.SECONDS));
        assertEquals("", readBody());
    }

    /**
     * The response fails before Jetty reported the end of a body without a {@code Content-Length}, such as a chunked
     * or a decoded body. The listener cannot know that the body is complete, so the body read reports the failure.
     */
    @Test
    public void responseFailureOfBodyWithoutLengthFailsBodyRead() {
        final EofException failure = new EofException("Broken pipe");
        listener.onHeaders(response);
        listener.onContent(response, chunk("nope!"), () -> {
        });
        listener.onFailure(response, failure);
        listener.onComplete(new Result(request, failure, response, failure));

        assertThrows(IOException.class, this::readBody);
    }

    /**
     * A protocol handler took the live response, and Jetty replays it with a failure, as for a failed redirect chain.
     * The call waits for the end of the replay, and fails.
     */
    @Test
    public void replayedResponseWithFailureFailsCall() throws Exception {
        client.getProtocolHandlers().put(new RedirectProtocolHandler(client));
        final HttpResponse redirect = new HttpResponse(request).status(302);
        redirect.headers(headers -> headers.put(HttpHeader.LOCATION, "http://localhost/")
                .put(HttpHeader.CONTENT_LENGTH, 0));
        final HttpResponseException failure = new HttpResponseException("Max redirects exceeded 8", redirect);
        listener.onBegin(redirect);
        listener.onHeaders(redirect);

        assertThrows(TimeoutException.class, () -> listener.get(0, TimeUnit.SECONDS));
        listener.onFailure(redirect, failure);
        listener.onComplete(new Result(request, null, redirect, failure));
        assertSame(failure, assertThrows(ExecutionException.class, () -> listener.get(0, TimeUnit.SECONDS)).getCause());
    }

    /**
     * Jetty asks the protocol handlers when the response begins, before the headers arrive. A protocol handler that
     * tests a header then does not take the response, so the response is live and is the outcome at its headers.
     */
    @Test
    public void handlerThatTestsHeaderDoesNotTakeLiveResponse() throws Exception {
        client.getProtocolHandlers().put(new ProtocolHandler() {
            @Override
            public String getName() {
                return "header";
            }

            @Override
            public boolean accept(final Request request, final Response response) {
                return response.getHeaders().contains("X-Handled");
            }

            @Override
            public Response.Listener getResponseListener() {
                return new Response.Listener() {
                };
            }
        });
        listener.onBegin(response);
        response.headers(headers -> headers.put("X-Handled", "true"));
        listener.onHeaders(response);

        assertSame(response, listener.get(0, TimeUnit.SECONDS));
    }

    /**
     * The authentication handler of Jetty cannot use a 401 or a 407 when it cannot parse a challenge in any of the
     * challenge headers, and replays the response with a failure. The response is the outcome at its headers.
     * Otherwise, the outcome waits for the end of the replay.
     */
    @ParameterizedTest
    @CsvSource({
            "401,realm=\"test\",true",
            "401,\"test\",true",
            "407,realm=\"test\",true",
            "401,Basic realm=\"test\",false",
            "407,Basic realm=\"test\",false",
    })
    public void unparseableChallengeIsOutcomeAtHeaders(final int status, final String challenge,
            final boolean atHeaders) throws Exception {
        final HttpResponse challenged = challengeResponse(status, challenge);
        listener.onBegin(challenged);
        listener.onHeaders(challenged);

        if (atHeaders) {
            assertSame(challenged, listener.get(0, TimeUnit.SECONDS));
        } else {
            assertThrows(TimeoutException.class, () -> listener.get(0, TimeUnit.SECONDS));
        }
    }

    /**
     * The authentication handler of Jetty parses each challenge header on its own, and uses the challenges that it
     * can parse.
     */
    @Test
    public void parseableChallengeInOtherHeaderWaitsForReplay() {
        final HttpResponse challenged = challengeResponse(401, "realm=\"test\"");
        challenged.headers(headers -> headers.add(HttpHeader.WWW_AUTHENTICATE, "Basic realm=\"test\""));
        listener.onBegin(challenged);
        listener.onHeaders(challenged);

        assertThrows(TimeoutException.class, () -> listener.get(0, TimeUnit.SECONDS));
    }

    /**
     * Jetty cannot send the request that follows a redirect, and reports that failure with a placeholder response
     * without a status. The call fails.
     */
    @Test
    public void placeholderResponseFailsCall() {
        final HttpResponse placeholder = new HttpResponse(client.newRequest("myapp://callback"));
        final IllegalArgumentException failure = new IllegalArgumentException("Invalid protocol myapp");
        listener.onHeaders(placeholder);
        listener.onFailure(placeholder, failure);
        listener.onComplete(new Result(placeholder.getRequest(), failure, placeholder, failure));

        assertSame(failure, assertThrows(ExecutionException.class, () -> listener.get(0, TimeUnit.SECONDS)).getCause());
    }

    /**
     * {@code CompletableFuture.get} throws a {@link CancellationException} failure directly, and
     * {@code InputStreamResponseListener.get} wraps every failure in an {@link ExecutionException}. The listener must
     * wrap a {@link CancellationException} too.
     */
    @Test
    public void cancellationFailsCallWithExecutionException() {
        final CancellationException failure = new CancellationException("aborted");
        listener.onFailure(response, failure);
        listener.onComplete(new Result(request, failure, response, failure));

        assertSame(failure, assertThrows(ExecutionException.class, () -> listener.get(0, TimeUnit.SECONDS)).getCause());
    }

    /**
     * While the request has a body, the listener copies the body and demands more content at once, so that Jetty can
     * parse the end of the response before the caller reads.
     */
    @Test
    public void readAheadDemandsBodyBeforeRead() throws IOException {
        final BodyKeepingResponseListener listener = new BodyKeepingResponseListener(client,
                new OutputStreamRequestContent());
        final AtomicInteger demands = new AtomicInteger();
        final Content.Chunk first = chunk("no");
        listener.onHeaders(response);
        listener.onContent(response, first, demands::incrementAndGet);
        listener.onContent(response, chunk("pe!"), demands::incrementAndGet);
        listener.onSuccess(response);

        assertEquals(2, demands.get());
        assertTrue(first.release(), "the listener must not retain the chunk that it copied");
        assertEquals("nope!", new String(listener.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    /**
     * The read ahead stops at {@value BodyKeepingResponseListener#READ_AHEAD_BYTES} bytes. Then the listener demands
     * more content only when the caller read the content that it holds.
     */
    @Test
    public void readAheadStopsAtLimit() throws IOException {
        final BodyKeepingResponseListener listener = new BodyKeepingResponseListener(client,
                new OutputStreamRequestContent());
        final AtomicInteger demands = new AtomicInteger();
        listener.onHeaders(response);
        listener.onContent(response, Content.Chunk.from(ByteBuffer.allocate(BodyKeepingResponseListener.READ_AHEAD_BYTES),
                false), demands::incrementAndGet);
        listener.onContent(response, chunk("x"), demands::incrementAndGet);
        assertEquals(1, demands.get());

        final InputStream body = listener.getInputStream();
        assertEquals(BodyKeepingResponseListener.READ_AHEAD_BYTES,
                body.readNBytes(BodyKeepingResponseListener.READ_AHEAD_BYTES).length);
        assertEquals(1, demands.get());
        assertEquals('x', body.read());
        assertEquals(2, demands.get());
    }

    /**
     * Without a request body, no request failure can follow the response, so the listener demands content only when
     * the caller reads.
     */
    @Test
    public void noReadAheadWithoutRequestBody() throws IOException {
        final AtomicInteger demands = new AtomicInteger();
        listener.onHeaders(response);
        listener.onContent(response, chunk("nope!"), demands::incrementAndGet);
        assertEquals(0, demands.get());

        final byte[] body = new byte[5];
        assertEquals(5, listener.getInputStream().read(body));
        assertEquals("nope!", new String(body, StandardCharsets.UTF_8));
        assertEquals(1, demands.get());
    }

    /**
     * Adds the authentication handlers of Jetty to the client, and returns a {@code status} response with
     * {@code challenge} in its challenge header.
     */
    private HttpResponse challengeResponse(final int status, final String challenge) {
        client.getProtocolHandlers().put(new WWWAuthenticationProtocolHandler(client));
        // The request of a unit test has no connection, so it has no proxy. Jetty 12.1.14 and later take a 407 only
        // for a request that went to a proxy, so this handler takes every 407.
        client.getProtocolHandlers().put(new ProxyAuthenticationProtocolHandler(client) {
            @Override
            public boolean accept(final Request request, final Response response) {
                return response.getStatus() == HttpStatus.PROXY_AUTHENTICATION_REQUIRED_407;
            }
        });
        final HttpHeader header = status == 401 ? HttpHeader.WWW_AUTHENTICATE : HttpHeader.PROXY_AUTHENTICATE;
        return new HttpResponse(request).status(status).headers(headers -> headers.put(header, challenge));
    }

    private String readBody() throws IOException {
        return new String(listener.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static Content.Chunk chunk(final String content) {
        return chunk(content, false);
    }

    private static Content.Chunk chunk(final String content, final boolean last) {
        return Content.Chunk.from(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)), last);
    }
}
