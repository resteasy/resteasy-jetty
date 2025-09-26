/*
 * Copyright The RESTEasy Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.resteasy.jetty.client.engine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.http.HttpCookieStore;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.jboss.resteasy.client.jaxrs.api.ClientBuilderConfiguration;
import org.jboss.resteasy.client.jaxrs.engine.ClientHttpEngineFactory;
import org.jboss.resteasy.client.jaxrs.engines.AsyncClientHttpEngine;
import org.kohsuke.MetaInfServices;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
@MetaInfServices
public class JettyClientHttpEngineFactory implements ClientHttpEngineFactory {
    @Override
    public AsyncClientHttpEngine asyncHttpClientEngine(final ClientBuilderConfiguration configuration) {
        /*
         * , configuration.connectionIdleTime(TimeUnit.MILLISECONDS),
         * configuration.readTimeout(TimeUnit.MILLISECONDS)
         */
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

        final HttpClient httpClient = new HttpClient(new HttpClientTransportDynamic(connector));
        configuration.executorService().ifPresent(httpClient::setExecutor);

        final long connectionTimeout = configuration.connectionTimeout(TimeUnit.MILLISECONDS);
        if (connectionTimeout >= 0L) {
            httpClient.setConnectTimeout(connectionTimeout);
        }

        final String proxyHost = configuration.defaultProxyHostname();
        if (proxyHost != null) {
            final String proxyProtocol = configuration.defaultProxyHostname();
            final int proxyPort = configuration.defaultProxyPort();
            final Origin.Address address = new Origin.Address(proxyHost, proxyPort);
            final HttpProxy proxy = new HttpProxy(address, proxyProtocol.equalsIgnoreCase("https"));
            httpClient.getProxyConfiguration().addProxy(proxy);
        }

        if (configuration.isCookieManagementEnabled()) {
            httpClient.setHttpCookieStore(new HttpCookieStore.Default());
        } else {
            httpClient.setHttpCookieStore(new HttpCookieStore.Empty());
        }

        httpClient.setFollowRedirects(configuration.isFollowRedirects());
        return new JettyClientEngine(httpClient, configuration.readTimeout(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS);
    }
}
