/*
 * Copyright (c) 2016 Pantheon Technologies s.r.o. and others. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.openflowplugin.api.openflow.lifecycle;

import com.google.common.util.concurrent.ListenableFuture;
import javax.annotation.Nonnull;
import org.opendaylight.mdsal.singleton.common.api.ClusterSingletonServiceProvider;
import org.opendaylight.openflowplugin.api.openflow.OFPContext;

/**
 * Chain of contexts, hold references to the contexts.
 */
public interface ContextChain extends AutoCloseable {

    /**
     * Add context to the chain, if reference already exist ignore it.
     * @param context child of OFPContext
     */
    <T extends OFPContext> void addContext(final T context);

    void addLifecycleService(final LifecycleService lifecycleService);

    /**
     * Stop the working contexts, but not release them.
     * @param connectionDropped true if stop the chain due to connection drop
     * @return Future
     */
    ListenableFuture<Void> stopChain(boolean connectionDropped);

    @Override
    void close();

    /**
     * Method need to be called if connection is dropped to stop the chain.
     * @return future
     */
    ListenableFuture<Void> connectionDropped();

    /**
     * Slave was successfully set.
     */
    void makeContextChainStateSlave();

    /**
     * Registers context chain into cluster singleton service.
     * @param clusterSingletonServiceProvider provider
     */
    void registerServices(final ClusterSingletonServiceProvider clusterSingletonServiceProvider);

    /**
     * After connect of device make this device SLAVE.
     */
    void makeDeviceSlave();

    boolean isMastered(@Nonnull final ContextChainMastershipState mastershipState);
}
