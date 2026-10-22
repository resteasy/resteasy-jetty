/*
 * Copyright The RESTEasy Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.resteasy.jetty.client;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.client.AuthenticationProtocolHandler;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.ContinueProtocolHandler;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpRedirector;
import org.eclipse.jetty.client.InputStreamResponseListener;
import org.eclipse.jetty.client.ProtocolHandler;
import org.eclipse.jetty.client.ProxyAuthenticationProtocolHandler;
import org.eclipse.jetty.client.RedirectProtocolHandler;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.client.WWWAuthenticationProtocolHandler;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;

/**
 * The {@link InputStreamResponseListener} of both the synchronous and the asynchronous call paths. Two rules decide
 * what a failure of the exchange does after the response headers arrived.
 * <p>
 * Origin: a live response, which has a status line and which no protocol handler of Jetty took when it began, is the
 * outcome of the call when its headers arrive. The continue handler of Jetty takes every final answer to a request with
 * {@code Expect: 100-continue} that comes without a 100, and always forwards it, also when it fails, so such an answer
 * is a live response too. Another protocol handler replays the response that it took, and Jetty gives a placeholder
 * response without a status when it cannot send the next request of a redirect. Such a response is the outcome only
 * when Jetty reports its end with a success. If Jetty completes the exchange with a failure, the call fails. The
 * exceptions are a redirect without a usable {@code Location} and a 401 or 407 without a challenge that Jetty can
 * parse. The protocol handler cannot use them, and Jetty replays them with a failure, although they are the answer of
 * the server.
 * <p>
 * Framing: after a failure, the body stream ends normally only when the bytes received complete the message. That is
 * the case when the {@code Content-Length} bytes arrived, when the message has no body (a response to HEAD, 1xx, 204 or
 * 304), or when a protocol handler buffered a body that is not empty. Any other body fails when the caller reads it.
 */
class BodyKeepingResponseListener extends InputStreamResponseListener {
    /**
     * The number of body bytes that the listener reads before the caller reads them.
     */
    static final int READ_AHEAD_BYTES = AuthenticationProtocolHandler.DEFAULT_MAX_CONTENT_LENGTH;
    private static final Runnable NO_DEMAND = () -> {
    };

    private final HttpClient client;
    private final Content.Source requestContent;
    private final CompletableFuture<Response> outcome = new CompletableFuture<>();
    private final AtomicBoolean decided = new AtomicBoolean();
    private volatile ProtocolHandler handlerAtBegin;
    private volatile boolean headersReceived;
    private volatile boolean outcomeAtEnd;
    private volatile long expectedLength = -1;
    private volatile long contentReceived;
    private volatile boolean bodyBuffered;

    /**
     * @param client         the client that sends the request, for its protocol handlers
     * @param requestContent the content of the request, or {@code null} if the request has no body
     */
    BodyKeepingResponseListener(final HttpClient client, final Content.Source requestContent) {
        this.client = client;
        this.requestContent = requestContent;
    }

    /**
     * Waits for the outcome of the call: the response, or the failure of the call.
     */
    @Override
    public Response get(final long timeout, final TimeUnit unit)
            throws InterruptedException, TimeoutException, ExecutionException {
        return outcome.get(timeout, unit);
    }

    /**
     * Receives the outcome of the call once: the response, or the failure of the call.
     */
    void onOutcome(final Response response, final Throwable failure) {
        if (failure == null) {
            outcome.complete(response);
        } else {
            // CompletableFuture.get throws a CancellationException failure directly, but get must throw every failure
            // in an ExecutionException, as InputStreamResponseListener does.
            outcome.completeExceptionally(new CompletionException(failure));
        }
    }

    /**
     * Jetty asks the protocol handlers when a response begins, before its headers arrive, so the listener asks them
     * here too. A protocol handler that tests a header can give another answer after the headers.
     */
    @Override
    public void onBegin(final Response response) {
        handlerAtBegin = client.findProtocolHandler(response.getRequest(), response);
        super.onBegin(response);
    }

    @Override
    public void onHeaders(final Response response) {
        expectedLength = hasNoBody(response) ? 0 : response.getHeaders().getLongField(HttpHeader.CONTENT_LENGTH);
        bodyBuffered = response instanceof ContentResponse buffered && buffered.getContent().length > 0;
        headersReceived = true;
        super.onHeaders(response);
        if (isOutcomeAtHeaders(response)) {
            decide(response, null);
        } else {
            outcomeAtEnd = true;
        }
    }

    /**
     * Over HTTP/1.1, a request failure fails a response whose end Jetty did not parse, and Jetty parses only as far as
     * the reader demands. So while the request has a body, the listener copies and demands the first
     * {@value #READ_AHEAD_BYTES} bytes of the body at once, as the authentication handler of Jetty does. A body that
     * fits then ends when its last byte arrives, also when it has no {@code Content-Length}, for example a chunked or a
     * decoded body.
     */
    @Override
    public void onContent(final Response response, final Content.Chunk chunk, final Runnable demander) {
        contentReceived += chunk.remaining();
        if (requestContent != null && contentReceived <= READ_AHEAD_BYTES) {
            super.onContent(response, copy(chunk), NO_DEMAND);
            demander.run();
        } else {
            super.onContent(response, chunk, demander);
        }
    }

    @Override
    public void onSuccess(final Response response) {
        super.onSuccess(response);
        if (outcomeAtEnd) {
            decide(response, null);
        }
    }

    @Override
    public void onFailure(final Response response, final Throwable failure) {
        if (bodyComplete()) {
            super.onSuccess(response);
        } else {
            super.onFailure(response, failure);
        }
        if (!headersReceived) {
            decide(null, failure);
        }
    }

    /**
     * {@link InputStreamResponseListener} discards queued content on any failure, a request failure included, so a
     * response with a complete body ends as a success here.
     * <p>
     * Jetty reports a request that it cannot send, for example to a scheme other than http or https, only with a
     * failed completion. Nothing then reads the request content, so the content fails here and a body writer stops.
     */
    @Override
    public void onComplete(final Result result) {
        if (!headersReceived && result.isFailed() && requestContent != null) {
            requestContent.fail(result.getFailure());
        }
        if (headersReceived && (result.getResponseFailure() == null || bodyComplete())) {
            super.onComplete(new Result(result.getRequest(), result.getResponse()));
        } else {
            super.onComplete(result);
        }
        if (result.isFailed()) {
            decide(null, result.getFailure());
        } else {
            decide(result.getResponse(), null);
        }
    }

    private void decide(final Response response, final Throwable failure) {
        if (decided.compareAndSet(false, true)) {
            onOutcome(response, failure);
        }
    }

    private boolean isOutcomeAtHeaders(final Response response) {
        if (response.getStatus() <= 0) {
            return false;
        }
        final ProtocolHandler handler = handlerAtBegin;
        return handler == null || handler instanceof ContinueProtocolHandler || isUnusable(handler, response);
    }

    private boolean bodyComplete() {
        return headersReceived && (bodyBuffered || expectedLength >= 0 && contentReceived == expectedLength);
    }

    /**
     * Returns whether the protocol handler that took the response cannot use it: a redirect without a {@code Location}
     * that Jetty can parse, or a 401 or 407 without a challenge that Jetty can parse.
     */
    private boolean isUnusable(final ProtocolHandler handler, final Response response) {
        if (handler instanceof RedirectProtocolHandler) {
            return new HttpRedirector(client).extractRedirectURI(response) == null;
        }
        final HttpHeader challenge;
        if (handler instanceof WWWAuthenticationProtocolHandler) {
            challenge = HttpHeader.WWW_AUTHENTICATE;
        } else if (handler instanceof ProxyAuthenticationProtocolHandler) {
            challenge = HttpHeader.PROXY_AUTHENTICATE;
        } else {
            return false;
        }
        return !new ChallengeParser(client).hasChallenge(response.getHeaders().getValuesList(challenge));
    }

    private static boolean hasNoBody(final Response response) {
        final int status = response.getStatus();
        return HttpMethod.HEAD.is(response.getRequest().getMethod()) || HttpStatus.isInformational(status)
                || status == HttpStatus.NO_CONTENT_204 || status == HttpStatus.NOT_MODIFIED_304;
    }

    /**
     * Gives access to the challenge parser of the authentication handlers of Jetty, which is protected. The parser is
     * the same for {@code WWW-Authenticate} and {@code Proxy-Authenticate}.
     */
    private static final class ChallengeParser extends WWWAuthenticationProtocolHandler {
        ChallengeParser(final HttpClient client) {
            super(client);
        }

        /**
         * Returns whether Jetty can parse a challenge in one of the header {@code values}, as its authentication
         * handler does.
         */
        boolean hasChallenge(final List<String> values) {
            for (final String value : values) {
                try {
                    if (!getHeaderInfo(value).isEmpty()) {
                        return true;
                    }
                } catch (IllegalArgumentException e) {
                    // The authentication handler skips a value that it cannot parse.
                }
            }
            return false;
        }
    }

    private static Content.Chunk copy(final Content.Chunk chunk) {
        final ByteBuffer copy = ByteBuffer.allocate(chunk.remaining()).put(chunk.getByteBuffer().slice()).flip();
        return Content.Chunk.from(copy, chunk.isLast());
    }
}
