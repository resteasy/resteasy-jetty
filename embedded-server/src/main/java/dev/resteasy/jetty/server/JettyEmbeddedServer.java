/*
 * Copyright The RESTEasy Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.resteasy.jetty.server;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.ws.rs.SeBootstrap.Configuration;
import jakarta.ws.rs.core.Application;

import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.ee11.cdi.CdiSpiDecorator;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.jboss.resteasy.plugins.server.embedded.EmbeddedServer;
import org.jboss.resteasy.plugins.server.embedded.EmbeddedServers;
import org.jboss.resteasy.plugins.server.servlet.HttpServlet30Dispatcher;
import org.jboss.resteasy.spi.ResteasyDeployment;
import org.jboss.weld.environment.servlet.Listener;

/**
 * A Jetty backed {@link EmbeddedServer} which deploys the RESTEasy servlet dispatcher to a Jetty
 * {@link ServletContextHandler} and bootstraps a CDI container used to resolve Jakarta REST components.
 *
 * @author <a href="mailto:jperkins@ibm.com">James R. Perkins</a>
 */
public class JettyEmbeddedServer implements EmbeddedServer {

    private final Lock lock = new ReentrantLock();
    private final CdiResteasyDeployment deployment;
    private Server server;

    /**
     * Creates a new embedded server.
     */
    public JettyEmbeddedServer() {
        deployment = new CdiResteasyDeployment();
    }

    @Override
    public void start(final Configuration configuration) {
        lock.lock();
        try {
            if (server != null) {
                throw new IllegalStateException("Server already started");
            }
            final Server server = new Server();
            server.addConnector(createConnector(server, configuration));

            final ContextHandlerCollection contexts = new ContextHandlerCollection();
            contexts.addHandler(createServletContextHandler(configuration));
            server.setHandler(contexts);
            server.setDefaultHandler(new DefaultHandler());

            try {
                server.start();
            } catch (Exception e) {
                deployment.stop();
                throw new RuntimeException("Failed to start the Jetty server", e);
            }
            this.server = server;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void stop() {
        lock.lock();
        try {
            try {
                if (server != null) {
                    server.stop();
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to stop the Jetty server", e);
            } finally {
                server = null;
                deployment.stop();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public ResteasyDeployment getDeployment() {
        return deployment;
    }

    private ServerConnector createConnector(final Server server, final Configuration configuration) {
        final HttpConfiguration httpConfiguration = new HttpConfiguration();
        final HttpConnectionFactory http11 = new HttpConnectionFactory(httpConfiguration);
        final ServerConnector connector;
        if ("HTTPS".equalsIgnoreCase(configuration.protocol())) {
            httpConfiguration.addCustomizer(new SecureRequestCustomizer());
            final SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
            sslContextFactory.setSslContext(configuration.sslContext());
            switch (configuration.sslClientAuthentication()) {
                case MANDATORY:
                    sslContextFactory.setNeedClientAuth(true);
                    break;
                case OPTIONAL:
                    sslContextFactory.setWantClientAuth(true);
                    break;
                case NONE:
                    break;
            }

            // Negotiate HTTP/2 over TLS via ALPN, falling back to HTTP/1.1 for clients that don't support it.
            final HTTP2ServerConnectionFactory http2 = new HTTP2ServerConnectionFactory(httpConfiguration);
            final ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
            alpn.setDefaultProtocol(http11.getProtocol());
            final SslConnectionFactory tls = new SslConnectionFactory(sslContextFactory, alpn.getProtocol());

            connector = new ServerConnector(server, tls, alpn, http2, http11);
        } else {
            // Allow cleartext HTTP/2 (h2c), via upgrade or prior knowledge, alongside HTTP/1.1.
            final HTTP2CServerConnectionFactory h2c = new HTTP2CServerConnectionFactory(httpConfiguration);
            connector = new ServerConnector(server, http11, h2c);
        }
        connector.setHost(configuration.host());
        connector.setPort(configuration.port());
        return connector;
    }

    private ServletContextHandler createServletContextHandler(final Configuration configuration) {
        // Ensure the RESTEasy deployment is started
        EmbeddedServers.validateDeployment(deployment);

        final String contextPath = EmbeddedServers.checkContextPath(configuration.rootPath());
        final ServletContextHandler context = new ServletContextHandler(contextPath);
        final Application application = deployment.getApplication();
        context.setClassLoader(application == null ? JettyEmbeddedServer.class.getClassLoader()
                : application.getClass().getClassLoader());

        // Allow Jetty to inject CDI managed beans into servlets, filters and listeners it creates itself.
        context.getObjectFactory().addDecorator(new CdiSpiDecorator(context));

        // Activate/deactivate the CDI RequestScoped context around each request.
        context.addEventListener(Listener.using(deployment.getBeanManager()));

        String mapping = EmbeddedServers.checkContextPath(deployment);
        if (!mapping.endsWith("/")) {
            mapping += "/";
        }
        mapping = mapping + "*";

        final ServletHolder resteasyServlet = new ServletHolder(HttpServlet30Dispatcher.class);
        resteasyServlet.setAsyncSupported(true);
        resteasyServlet.setInitOrder(1);
        if (!"/*".equals(mapping)) {
            resteasyServlet.setInitParameter("resteasy.servlet.mapping.prefix", mapping.substring(0, mapping.length() - 2));
        }

        context.setAttribute(ResteasyDeployment.class.getName(), deployment);
        context.setAttribute(BeanManager.class.getName(), deployment.getBeanManager());
        context.addServlet(resteasyServlet, mapping);
        return context;
    }
}
