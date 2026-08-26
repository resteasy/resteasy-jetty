/*
 * Copyright The RESTEasy Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.resteasy.jetty.client;

import static dev.resteasy.jetty.client.JettyClientProperties.IDLE_TIMEOUT;
import static dev.resteasy.jetty.client.JettyClientProperties.REQUEST_TIMEOUT;
import static org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder.PROPERTY_FOLLOW_REDIRECTS;

import java.io.InputStream;
import java.io.OutputStream;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.client.ResponseProcessingException;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.InputStreamResponseListener;
import org.eclipse.jetty.client.OutputStreamRequestContent;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.http.HttpFields;
import org.jboss.logging.Logger;
import org.jboss.resteasy.client.jaxrs.engines.AsyncClientHttpEngine;
import org.jboss.resteasy.client.jaxrs.internal.ClientInvocation;
import org.jboss.resteasy.client.jaxrs.internal.ClientResponse;
import org.jboss.resteasy.concurrent.ContextualExecutors;

/**
 * An {@link AsyncClientHttpEngine} backed by a Jetty {@link HttpClient}. Instances are normally
 * obtained through {@link JettyClientHttpEngineFactory} rather than constructed directly, by
 * building a Jakarta REST client through the standard {@code ClientBuilder} without registering a custom
 * {@code ClientHttpEngine}.
 */
public class JettyClientHttpEngine implements AsyncClientHttpEngine {

    private static final Logger LOGGER = Logger.getLogger(JettyClientHttpEngine.class);
    private static final MediaType MULTIPART_WILDCARD = new MediaType("multipart", "*");
    private static final Class<?> MULTIPART_OUTPUT;

    static {
        // Check if the org.jboss.resteasy.plugins.providers.multipart.MultipartOutput is on the class path
        final String className = "org.jboss.resteasy.plugins.providers.multipart.MultipartOutput";
        Class<?> multipartOutput = null;
        try {
            multipartOutput = Class.forName(className, false, resolveClassLoader());
        } catch (ClassNotFoundException e) {
            LOGGER.tracef(e, "Failed to load %s", className);
        }

        MULTIPART_OUTPUT = multipartOutput;
    }
    private final HttpClient client;
    private final long readTimeout;
    private final TimeUnit readTimeoutUnit;

    /**
     * Creates a new engine wrapping {@code client}, starting it if it isn't already started.
     *
     * @param client          the Jetty client to use for all requests; its lifecycle (start/stop)
     *                        is managed by this engine from this point on
     * @param readTimeout     the default total exchange timeout applied to every request, or
     *                        {@code <= 0} for none
     * @param readTimeoutUnit the unit of {@code readTimeout}
     */
    public JettyClientHttpEngine(final HttpClient client, final long readTimeout, final TimeUnit readTimeoutUnit) {
        this.readTimeout = readTimeout;
        this.readTimeoutUnit = readTimeoutUnit;
        if (!client.isStarted()) {
            try {
                client.start();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        this.client = client;
    }

    @Override
    public SSLContext getSslContext() {
        return client.getSslContextFactory().getSslContext();
    }

    /**
     * Always throws, since Jetty configures TLS endpoint verification through
     * {@link org.eclipse.jetty.util.ssl.SslContextFactory} rather than the JDK's
     * {@link HostnameVerifier} API, so there is no verifier to return.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    public HostnameVerifier getHostnameVerifier() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ClientResponse invoke(final Invocation invocation) {
        final ClientInvocation clientInvocation = (ClientInvocation) invocation;
        final Request request = prepareRequest(clientInvocation);
        // The body write, when there is one, must run concurrently with reading the response below,
        // not before it: a handler that streams the response back as it reads the request (an echo,
        // for example) can only make progress if both directions are being drained at once. Doing
        // the write synchronously here first, then waiting for the response, can deadlock on such a
        // handler once the unread response backs up enough to stall the handler's own request read.
        attachEntity(clientInvocation, request, clientInvocation.asyncInvocationExecutor());

        final InputStreamResponseListener listener = new InputStreamResponseListener();
        request.send(listener);
        try {
            final Response response = listener.get(blockingTimeoutMs(request), TimeUnit.MILLISECONDS);
            final InputStream inputStream = listener.getInputStream();
            try {
                return buildClientResponse(clientInvocation, response, inputStream);
            } catch (RuntimeException e) {
                throw clientException(closeSuppressing(inputStream, e), null);
            }
        } catch (InterruptedException e) {
            request.abort(e);
            Thread.currentThread().interrupt();
            throw clientException(e, null);
        } catch (TimeoutException e) {
            request.abort(e);
            throw clientException(e, null);
        } catch (ExecutionException e) {
            throw clientException(e.getCause(), null);
        }
    }

    @Override
    public <T> Future<T> submit(final ClientInvocation invocation, final boolean bufIn, final InvocationCallback<T> callback,
            final ResultExtractor<T> extractor) {
        return doSubmit(invocation, bufIn, callback, extractor, null);
    }

    @Override
    public <T> CompletableFuture<T> submit(final ClientInvocation request, final boolean buffered,
            final ResultExtractor<T> extractor,
            ExecutorService executorService) {
        return doSubmit(request, buffered, null, extractor, executorService);
    }

    @Override
    public void close() {
        try {
            client.stop();
        } catch (Exception e) {
            throw new RuntimeException("Unable to close JettyHttpEngine", e);
        }
    }

    /**
     * The timeout to wait when blocking synchronously. Reads back whatever total-exchange timeout
     * {@link #prepareRequest(ClientInvocation)} actually configured on {@code request}, rather than re-deriving it, so
     * the two can't disagree. {@code Long.MAX_VALUE} if none was configured, to block until Jetty resolves the
     * exchange, same as an unbounded blocking call would.
     */
    private long blockingTimeoutMs(final Request request) {
        final long timeoutMs = request.getTimeout();
        return timeoutMs > 0 ? timeoutMs : Long.MAX_VALUE;
    }

    /**
     * Shared implementation backing both {@code submit} overloads: builds and sends the request,
     * then, once Jetty delivers the response headers, builds the {@link ClientResponse} and
     * dispatches the rest of the processing (buffering, extraction, callback) onto
     * {@code asyncExecutor}, completing {@code future} with the result or failure.
     */
    private <T> CompletableFuture<T> doSubmit(final ClientInvocation invocation, final boolean buffered,
            final InvocationCallback<T> callback,
            final ResultExtractor<T> extractor, final ExecutorService executorService) {
        final ExecutorService asyncExecutor = (executorService == null ? invocation.asyncInvocationExecutor()
                : ContextualExecutors.wrap(executorService));
        final Request request = prepareRequest(invocation);
        final CompletableFuture<T> future = new CompletableFuture<>();
        future.whenComplete((result, e) -> {
            if (e != null) {
                request.abort(e);
            }
        });
        attachEntity(invocation, request, asyncExecutor);

        request.send(new InputStreamResponseListener() {
            private ClientResponse cr;

            @Override
            @SuppressWarnings("unchecked")
            public void onHeaders(final Response response) {
                super.onHeaders(response);
                final InputStream inputStream = getInputStream();
                try {
                    cr = buildClientResponse(invocation, response, inputStream);
                } catch (RuntimeException e) {
                    onFailure(response, closeSuppressing(inputStream, e));
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
                            onFailure(response, closeSuppressing(inputStream, e));
                        }
                    });
                } catch (RejectedExecutionException e) {
                    // The executor was shut down (e.g. the client was closed) before the completion
                    // task could be queued. Jetty considers the exchange successful and would swallow
                    // this exception, leaving the future forever incomplete; fail it explicitly instead.
                    onFailure(response, closeSuppressing(inputStream, e));
                }
            }

            @Override
            public void onFailure(final Response response, final Throwable failure) {
                super.onFailure(response, failure);
                failFuture(future, callback, clientException(failure, cr));
            }

            private void complete(final T result) {
                future.complete(result);
                if (callback != null) {
                    callback.completed(result);
                }
            }
        });
        return future;
    }

    /**
     * Builds and configures a {@link Request} from the given invocation -- headers, multipart boundary, timeouts,
     * redirects. Shared by both the synchronous ({@link #invoke(Invocation)}) and asynchronous ({@link #doSubmit})
     * call paths; the entity body, if any, is attached and written by the caller, since whether that write belongs on
     * the calling thread or a background executor depends on which path is calling.
     */
    private Request prepareRequest(final ClientInvocation invocation) {
        final Request request = client.newRequest(invocation.getUri());
        if (readTimeout > 0) {
            request.timeout(readTimeout, readTimeoutUnit);
        }

        // Determine if this is a multipart request
        final boolean addBoundary = isMultipart(invocation) && canSetBoundary(invocation.getEntity());

        invocation.getMutableProperties().forEach(request::attribute);
        request.method(invocation.getMethod());
        request.headers(mutableHeaders -> invocation.getHeaders().asMap()
                .forEach((h, vs) -> vs.forEach(v -> {
                    String headerValue = v;
                    if (addBoundary && h.equalsIgnoreCase("content-type")) {
                        final MediaType mediaType = MediaType.valueOf(v);
                        // Set the boundary if needed
                        if (mediaType.getParameters().get("boundary") == null) {
                            headerValue = headerValue + "; boundary=" + UUID.randomUUID();
                            // Replace the MediaType on the invocation if we've added a boundary
                            invocation.getHeaders().setMediaType(MediaType.valueOf(headerValue));
                        }
                    }
                    mutableHeaders.add(h, headerValue);
                })));
        configureTimeout(request);
        if (Boolean.FALSE.equals(request.getAttributes().get(PROPERTY_FOLLOW_REDIRECTS))) {
            request.followRedirects(false);
        }

        return request;
    }

    /**
     * Attaches the invocation's entity, if any, to {@code request} and schedules the body write on {@code asyncExecutor}.
     * This always runs on the executor, even for the synchronous {@link #invoke(Invocation)} path, because the write
     * must happen concurrently with reading the response (see the note in {@link #invoke(Invocation)}), not
     * sequentially before it.
     */
    private void attachEntity(final ClientInvocation invocation, final Request request, final ExecutorService asyncExecutor) {
        final Object entity = invocation.getEntity();
        if (entity == null) {
            return;
        }
        final OutputStreamRequestContent contentOut = new OutputStreamRequestContent(
                Objects.toString(invocation.getHeaders().getMediaType(), null));
        // A rejection here (executor shut down before the body task is queued) escapes on the
        // calling thread; abort the request instead so the listener that sends it is notified of the
        // failure, rather than leaving the exchange to complete as if there were no body.
        try {
            asyncExecutor.execute(() -> {
                try {
                    try (OutputStream bodyOut = contentOut.getOutputStream()) {
                        invocation.writeRequestBody(bodyOut);
                    }
                } catch (Throwable t) {
                    request.abort(t);
                }
            });
        } catch (RejectedExecutionException e) {
            request.abort(e);
        }
        request.body(contentOut);
    }

    /**
     * Applies the {@link JettyClientProperties#REQUEST_TIMEOUT} and {@link JettyClientProperties#IDLE_TIMEOUT} per-request
     * properties,
     * if set, to {@code request}.
     */
    private void configureTimeout(final Request request) {
        final Object timeout = request.getAttributes().get(REQUEST_TIMEOUT);
        final Object idleTimeout = request.getAttributes().get(IDLE_TIMEOUT);
        final long timeoutMs = parseTimeoutMs(timeout);
        final long idleTimeoutMs = parseTimeoutMs(idleTimeout);
        if (timeoutMs > 0) {
            request.timeout(timeoutMs, TimeUnit.MILLISECONDS);
        }

        if (idleTimeoutMs > 0) {
            request.idleTimeout(idleTimeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Parses a timeout value from a per-request property into milliseconds, accepting a
     * {@link Duration}, a {@link Number}, or a {@link String} parseable as a {@code long}.
     *
     * @return the timeout in milliseconds, or {@code -1} if {@code timeout} is {@code null}
     */
    private long parseTimeoutMs(final Object timeout) {
        final long timeoutMs;
        if (timeout instanceof Duration) {
            timeoutMs = ((Duration) timeout).toMillis();
        } else if (timeout instanceof Number) {
            timeoutMs = ((Number) timeout).longValue();
        } else if (timeout != null) {
            timeoutMs = Long.parseLong(timeout.toString());
        } else {
            timeoutMs = -1L;
        }
        return timeoutMs;
    }

    /**
     * Converts Jetty's {@link HttpFields} into the {@link MultivaluedMap} shape RESTEasy's
     * {@link ClientResponse} expects.
     */
    private MultivaluedMap<String, String> extract(final HttpFields headers) {
        final MultivaluedMap<String, String> extracted = new MultivaluedHashMap<>();
        headers.forEach(h -> extracted.add(h.getName(), h.getValue()));
        return extracted;
    }

    /**
     * Builds a {@link ClientResponse} backed by {@code inputStream}, populated with {@code invocation}'s
     * mutable properties and {@code response}'s status and headers.
     */
    private ClientResponse buildClientResponse(final ClientInvocation invocation, final Response response,
            final InputStream inputStream) {
        final ClientResponse clientResponse = new JettyClientResponse(invocation.getClientConfiguration(), inputStream);
        clientResponse.setProperties(invocation.getMutableProperties());
        clientResponse.setStatus(response.getStatus());
        clientResponse.setHeaders(extract(response.getHeaders()));
        return clientResponse;
    }

    /**
     * Completes {@code future} exceptionally with {@code x} and, if {@code callback} is given,
     * invokes {@link InvocationCallback#failed(Throwable)} on it too.
     */
    private static <T> void failFuture(final CompletableFuture<T> future, final InvocationCallback<T> callback,
            final RuntimeException x) {
        future.completeExceptionally(x);
        if (callback != null) {
            callback.failed(x);
        }
    }

    /**
     * Closes {@code closeable} if non-null, recording any failure as a suppressed exception on
     * {@code primary}, and returns {@code primary} so it can be passed straight to a failure
     * handler without losing the original cause.
     */
    private static Throwable closeSuppressing(final AutoCloseable closeable, final Throwable primary) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                primary.addSuppressed(e);
            }
        }
        return primary;
    }

    /**
     * Wraps {@code ex} as a Jakarta REST exception suitable for surfacing to callers: passed through
     * unchanged if it already is one, wrapped as a {@link ResponseProcessingException} if a
     * response was available, or as a plain {@link ProcessingException} otherwise. A {@code null}
     * {@code ex} (should not normally happen) is turned into a {@link ProcessingException} wrapping
     * a fresh {@link NullPointerException} rather than throwing one directly.
     */
    private static RuntimeException clientException(final Throwable ex, final jakarta.ws.rs.core.Response clientResponse) {
        RuntimeException ret;
        if (ex == null) {
            final NullPointerException e = new NullPointerException();
            e.fillInStackTrace();
            ret = new ProcessingException(e);
        } else if (ex instanceof WebApplicationException) {
            ret = (WebApplicationException) ex;
        } else if (ex instanceof ProcessingException) {
            ret = (ProcessingException) ex;
        } else if (clientResponse != null) {
            ret = new ResponseProcessingException(clientResponse, ex);
        } else {
            ret = new ProcessingException(ex);
        }
        ret.fillInStackTrace();
        return ret;
    }

    /**
     * Returns the context class loader, falling back to this class's own loader and then the
     * system class loader, used to probe for the optional multipart provider in {@link #canSetBoundary}.
     */
    private static ClassLoader resolveClassLoader() {
        if (System.getSecurityManager() == null) {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) {
                cl = JettyClientHttpEngine.class.getClassLoader();
            }
            return cl == null ? ClassLoader.getSystemClassLoader() : cl;
        }
        return AccessController.doPrivileged((PrivilegedAction<ClassLoader>) () -> {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) {
                cl = JettyClientHttpEngine.class.getClassLoader();
            }
            return cl == null ? ClassLoader.getSystemClassLoader() : cl;
        });
    }

    /**
     * Returns whether the invocation's media type is {@code multipart/*}.
     */
    private static boolean isMultipart(final ClientInvocation invocation) {
        return MULTIPART_WILDCARD.isCompatible(invocation.getHeaders().getMediaType());
    }

    /**
     * Returns whether a multipart boundary can be generated and set for {@code entity} -- true for
     * a {@code MultipartOutput} (if the optional multipart provider is on the classpath), an
     * {@link EntityPart}, or a non-empty {@link List} whose first element is an {@link EntityPart}.
     */
    private static boolean canSetBoundary(final Object entity) {
        if (MULTIPART_OUTPUT != null && MULTIPART_OUTPUT.isInstance(entity)) {
            return true;
        }
        if (entity instanceof EntityPart) {
            return true;
        }
        if (entity instanceof final List<?> list) {
            // We're a list, if we're not empty check the first type to see if it's an entity part
            if (!list.isEmpty()) {
                return list.get(0) instanceof EntityPart;
            }
        }
        return false;
    }
}
