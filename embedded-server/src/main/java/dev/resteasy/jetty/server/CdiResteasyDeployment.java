/*
 * Copyright The RESTEasy Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.resteasy.jetty.server;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ext.Provider;

import org.jboss.resteasy.cdi.CdiInjectorFactory;
import org.jboss.resteasy.core.ResteasyDeploymentImpl;
import org.jboss.resteasy.spi.DelegateResteasyDeployment;
import org.jboss.resteasy.spi.ResteasyDeployment;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;

/**
 * A {@link ResteasyDeployment} which bootstraps a Weld SE container and uses it to resolve the RESTEasy
 * {@link org.jboss.resteasy.spi.InjectorFactory}.
 *
 * @author <a href="mailto:jperkins@ibm.com">James R. Perkins</a>
 */
class CdiResteasyDeployment extends DelegateResteasyDeployment {

    private static final AtomicLong COUNTER = new AtomicLong();

    private final ReentrantLock lock = new ReentrantLock();
    private final String containerName;
    private boolean started;
    private Weld weld;
    private WeldContainer container;

    CdiResteasyDeployment() {
        super(new ResteasyDeploymentImpl());
        this.containerName = "resteasy-jetty-%d".formatted(COUNTER.incrementAndGet());
    }

    @Override
    public void start() {
        lock.lock();
        try {
            if (started) {
                return;
            }
            super.setInjectorFactory(new CdiInjectorFactory(getBeanManager()));
            super.start();
            started = true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void stop() {
        lock.lock();
        try {
            try {
                super.stop();
            } finally {
                if (weld != null) {
                    // This will shut down the container as well
                    weld.shutdown();
                    weld = null;
                    container = null;
                }
            }
        } finally {
            started = false;
            lock.unlock();
        }
    }

    BeanManager getBeanManager() {
        return getContainer().getBeanManager();
    }

    @SuppressWarnings("unchecked")
    private WeldContainer getContainer() {
        lock.lock();
        try {
            if (weld == null) {
                weld = new Weld(containerName)
                        // Register these as bean defining annotations
                        .addBeanDefiningAnnotations(Path.class, Provider.class, ApplicationPath.class)
                        // Do not register the shutdown hook as stopping the server may execute in a separate shutdown hook.
                        .skipShutdownHook();
                container = weld.initialize();
            }
            return container;
        } finally {
            lock.unlock();
        }
    }
}
