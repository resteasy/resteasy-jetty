/*
 * Copyright The RESTEasy Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.resteasy.jetty.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.transport.HttpClientConnectionFactory;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.http.HttpCookieStore;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.ClientConnectionFactoryOverHTTP2;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.jboss.resteasy.client.jaxrs.api.ClientBuilderConfiguration;
import org.jboss.resteasy.client.jaxrs.engine.ClientHttpEngineFactory;
import org.jboss.resteasy.client.jaxrs.engines.AsyncClientHttpEngine;
import org.kohsuke.MetaInfServices;

/**
 * A {@link ClientHttpEngineFactory} that builds a Jetty-backed {@link JettyClientHttpEngine}.
 * Registered as a Jakarta REST {@code ClientHttpEngineFactory} service provider, so it is discovered and
 * used automatically by RESTEasy's {@code ClientBuilder} -- applications do not need to reference
 * this class directly.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
@MetaInfServices
public class JettyClientHttpEngineFactory implements ClientHttpEngineFactory {

    @Override
    public AsyncClientHttpEngine asyncHttpClientEngine(final ClientBuilderConfiguration configuration) {
        final ClientConnector connector = new ClientConnector();
        if (configuration.sslContext() != null) {
            final SslContextFactory.Client sslClient = new SslContextFactory.Client();
            sslClient.setSslContext(configuration.sslContext());

            if (!configuration.sniHostNames().isEmpty()) {
                final SslContextFactory.Client.SniProvider provider = (sslEngine, serverNames) -> {
                    final List<SNIServerName> sniServerNames = new ArrayList<>();
                    for (String name : configuration.sniHostNames()) {
                        sniServerNames.add(new SNIHostName(name));
                    }
                    return List.copyOf(sniServerNames);

                };
                sslClient.setSNIProvider(provider);
            }
            connector.setSslContextFactory(sslClient);
        }
        if (configuration.connectionIdleTime(TimeUnit.MILLISECONDS) > 0) {
            connector.setIdleTimeout(Duration.ofMillis(configuration.connectionIdleTime(TimeUnit.MILLISECONDS)));
        }

        // Negotiate HTTP/2 over TLS via ALPN when the server supports it (order doesn't affect
        // ALPN negotiation), while defaulting plaintext requests to HTTP/1.1: for a non-secure
        // request Jetty picks the *first* listed protocol outright, with no negotiation, so listing
        // HTTP/2 first here would make every plaintext request try cleartext HTTP/2 (h2c) against
        // servers that, overwhelmingly, only speak HTTP/1.1.
        final ClientConnectionFactory.Info h1 = HttpClientConnectionFactory.HTTP11;
        final ClientConnectionFactory.Info h2 = new ClientConnectionFactoryOverHTTP2.HTTP2(new HTTP2Client(connector));
        final HttpClient httpClient = new HttpClient(new HttpClientTransportDynamic(connector, h1, h2));
        // We do not use configuration.executorService() here in favor of the HttpClient's own transport executor.
        // The configuration.executorService() is used for async methods outside of the created HttpClient.

        final long connectionTimeout = configuration.connectionTimeout(TimeUnit.MILLISECONDS);
        if (connectionTimeout >= 0L) {
            httpClient.setConnectTimeout(connectionTimeout);
        }

        final String proxyHost = configuration.defaultProxyHostname();
        if (proxyHost != null) {
            final String proxyProtocol = configuration.defaultProxyScheme();
            final int proxyPort = configuration.defaultProxyPort();
            final Origin.Address address = new Origin.Address(proxyHost, proxyPort);
            final HttpProxy proxy = new HttpProxy(address, "https".equalsIgnoreCase(proxyProtocol));
            httpClient.getProxyConfiguration().addProxy(proxy);
        }

        if (configuration.isCookieManagementEnabled()) {
            httpClient.setHttpCookieStore(new HttpCookieStore.Default());
        } else {
            httpClient.setHttpCookieStore(new HttpCookieStore.Empty());
        }

        httpClient.setFollowRedirects(configuration.isFollowRedirects());
        return new JettyClientHttpEngine(httpClient, configuration.readTimeout(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS);
    }
}
