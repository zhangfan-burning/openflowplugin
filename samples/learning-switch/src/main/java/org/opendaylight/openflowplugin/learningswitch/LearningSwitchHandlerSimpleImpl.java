/**
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.openflowplugin.learningswitch;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.opendaylight.infrautils.utils.concurrent.LoggingFutures;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.yang.types.rev130715.MacAddress;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.FlowId;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.tables.Table;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.tables.table.Flow;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.tables.table.FlowBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.tables.table.FlowKey;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.types.rev131026.FlowCookie;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.NodeConnectorId;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.NodeConnectorRef;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.NodeId;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.NodeRef;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.node.NodeConnector;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.node.NodeConnectorKey;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.nodes.Node;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.nodes.NodeKey;
import org.opendaylight.yang.gen.v1.urn.opendaylight.packet.service.rev130709.PacketProcessingListener;
import org.opendaylight.yang.gen.v1.urn.opendaylight.packet.service.rev130709.PacketProcessingService;
import org.opendaylight.yang.gen.v1.urn.opendaylight.packet.service.rev130709.PacketReceived;
import org.opendaylight.yang.gen.v1.urn.opendaylight.packet.service.rev130709.TransmitPacketInput;
import org.opendaylight.yang.gen.v1.urn.opendaylight.packet.service.rev130709.TransmitPacketInputBuilder;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple Learning Switch implementation which does mac learning for one switch.
 */
public class LearningSwitchHandlerSimpleImpl implements LearningSwitchHandler, PacketProcessingListener {

    private static final Logger LOG = LoggerFactory.getLogger(LearningSwitchHandlerSimpleImpl.class);
    private static final byte[] ETH_TYPE_IPV4 = new byte[] { 0x08, 0x00 };
    private static final int DIRECT_FLOW_PRIORITY = 512;

    private final DataTreeChangeListenerRegistrationHolder registrationPublisher;
    private final FlowCommitWrapper dataStoreAccessor;
    private final PacketProcessingService packetProcessingService;

    private volatile boolean isLearning = false;

    private NodeId nodeId;
    private final AtomicLong flowIdInc = new AtomicLong();
    private final AtomicLong flowCookieInc = new AtomicLong(0x2a00000000000000L);

    private InstanceIdentifier<Node> nodePath;
    private volatile InstanceIdentifier<Table> tablePath;

    private Map<MacAddress, NodeConnectorRef> mac2portMapping;
    private final Set<String> coveredMacPaths = new HashSet<>();

    public LearningSwitchHandlerSimpleImpl(@Nonnull FlowCommitWrapper dataStoreAccessor,
            @Nonnull PacketProcessingService packetProcessingService,
            @Nullable DataTreeChangeListenerRegistrationHolder registrationPublisher) {
        this.dataStoreAccessor = Objects.requireNonNull(dataStoreAccessor);
        this.packetProcessingService = Objects.requireNonNull(packetProcessingService);
        this.registrationPublisher = registrationPublisher;
    }

    @Override
    public synchronized void onSwitchAppeared(InstanceIdentifier<Table> appearedTablePath) {
        if (isLearning) {
            LOG.debug("already learning a node, skipping {}", nodeId.getValue());
            return;
        }

        LOG.debug("expected table acquired, learning ..");

        // disable listening - simple learning handles only one node (switch)
        if (registrationPublisher != null) {
            LOG.debug("closing dataTreeChangeListenerRegistration");
            registrationPublisher.getDataTreeChangeListenerRegistration().close();
        }

        isLearning = true;

        tablePath = appearedTablePath;
        nodePath = tablePath.firstIdentifierOf(Node.class);
        nodeId = nodePath.firstKeyOf(Node.class).getId();
        mac2portMapping = new HashMap<>();

        // start forwarding all packages to controller
        FlowId flowId = new FlowId(String.valueOf(flowIdInc.getAndIncrement()));
        FlowKey flowKey = new FlowKey(flowId);
        InstanceIdentifier<Flow> flowPath = InstanceIdentifierUtils.createFlowPath(tablePath, flowKey);

        int priority = 0;
        // create flow in table with id = 0, priority = 4 (other params are
        // defaulted in OFDataStoreUtil)
        FlowBuilder allToCtrlFlow = FlowUtils.createFwdAllToControllerFlow(
                InstanceIdentifierUtils.getTableId(tablePath), priority, flowId);

        LOG.debug("writing packetForwardToController flow");
        dataStoreAccessor.writeFlowToConfig(flowPath, allToCtrlFlow.build());
    }

    @Override
    public void onPacketReceived(PacketReceived notification) {
        if (!isLearning) {
            // ignoring packets - this should not happen
            return;
        }

        LOG.debug("Received packet via match: {}", notification.getMatch());

        // detect and compare node - we support one switch
        if (!nodePath.contains(notification.getIngress().getValue())) {
            return;
        }

        // read src MAC and dst MAC
        byte[] dstMacRaw = PacketUtils.extractDstMac(notification.getPayload());
        byte[] srcMacRaw = PacketUtils.extractSrcMac(notification.getPayload());
        byte[] etherType = PacketUtils.extractEtherType(notification.getPayload());

        MacAddress dstMac = PacketUtils.rawMacToMac(dstMacRaw);
        MacAddress srcMac = PacketUtils.rawMacToMac(srcMacRaw);

        NodeConnectorKey ingressKey = InstanceIdentifierUtils.getNodeConnectorKey(notification.getIngress().getValue());

        LOG.debug("Received packet from MAC match: {}, ingress: {}", srcMac, ingressKey.getId());
        LOG.debug("Received packet to   MAC match: {}", dstMac);
        LOG.debug("Ethertype: {}", Integer.toHexString(0x0000ffff & ByteBuffer.wrap(etherType).getShort()));

        // learn by IPv4 traffic only
        if (Arrays.equals(ETH_TYPE_IPV4, etherType)) {
            NodeConnectorRef previousPort = mac2portMapping.put(srcMac, notification.getIngress());
            if (previousPort != null && !notification.getIngress().equals(previousPort)) {
                NodeConnectorKey previousPortKey = InstanceIdentifierUtils.getNodeConnectorKey(previousPort.getValue());
                LOG.debug("mac2port mapping changed by mac {}: {} -> {}", srcMac, previousPortKey, ingressKey.getId());
            }
            // if dst MAC mapped:
            NodeConnectorRef destNodeConnector = mac2portMapping.get(dstMac);
            if (destNodeConnector != null) {
                synchronized (coveredMacPaths) {
                    if (!destNodeConnector.equals(notification.getIngress())) {
                        // add flow
                        addBridgeFlow(srcMac, dstMac, destNodeConnector);
                        addBridgeFlow(dstMac, srcMac, notification.getIngress());
                    } else {
                        LOG.debug("useless rule ignoring - both MACs are behind the same port");
                    }
                }
                LOG.debug("packetIn-directing.. to {}",
                        InstanceIdentifierUtils.getNodeConnectorKey(destNodeConnector.getValue()).getId());
                sendPacketOut(notification.getPayload(), notification.getIngress(), destNodeConnector);
            } else {
                // flood
                LOG.debug("packetIn-still flooding.. ");
                flood(notification.getPayload(), notification.getIngress());
            }
        } else {
            // non IPv4 package
            flood(notification.getPayload(), notification.getIngress());
        }

    }

    private void addBridgeFlow(MacAddress srcMac, MacAddress dstMac, NodeConnectorRef destNodeConnector) {
        synchronized (coveredMacPaths) {
            String macPath = srcMac.toString() + dstMac.toString();
            if (!coveredMacPaths.contains(macPath)) {
                LOG.debug("covering mac path: {} by [{}]", macPath,
                        destNodeConnector.getValue().firstKeyOf(NodeConnector.class).getId());

                coveredMacPaths.add(macPath);
                FlowId flowId = new FlowId(String.valueOf(flowIdInc.getAndIncrement()));
                FlowKey flowKey = new FlowKey(flowId);
                /**
                 * Path to the flow we want to program.
                 */
                InstanceIdentifier<Flow> flowPath = InstanceIdentifierUtils.createFlowPath(tablePath, flowKey);

                Short tableId = InstanceIdentifierUtils.getTableId(tablePath);
                FlowBuilder srcToDstFlow = FlowUtils.createDirectMacToMacFlow(tableId, DIRECT_FLOW_PRIORITY, srcMac,
                        dstMac, destNodeConnector);
                srcToDstFlow.setCookie(new FlowCookie(BigInteger.valueOf(flowCookieInc.getAndIncrement())));

                dataStoreAccessor.writeFlowToConfig(flowPath, srcToDstFlow.build());
            }
        }
    }

    private void flood(byte[] payload, NodeConnectorRef ingress) {
        NodeConnectorKey nodeConnectorKey = new NodeConnectorKey(nodeConnectorId("0xfffffffb"));
        InstanceIdentifier<?> nodeConnectorPath = InstanceIdentifierUtils.createNodeConnectorPath(
                nodePath, nodeConnectorKey);
        NodeConnectorRef egressConnectorRef = new NodeConnectorRef(nodeConnectorPath);

        sendPacketOut(payload, ingress, egressConnectorRef);
    }

    private NodeConnectorId nodeConnectorId(String connectorId) {
        NodeKey nodeKey = nodePath.firstKeyOf(Node.class);
        StringBuilder stringId = new StringBuilder(nodeKey.getId().getValue()).append(":").append(connectorId);
        return new NodeConnectorId(stringId.toString());
    }

    private void sendPacketOut(byte[] payload, NodeConnectorRef ingress, NodeConnectorRef egress) {
        InstanceIdentifier<Node> egressNodePath = InstanceIdentifierUtils.getNodePath(egress.getValue());
        TransmitPacketInput input = new TransmitPacketInputBuilder()
                .setPayload(payload)
                .setNode(new NodeRef(egressNodePath))
                .setEgress(egress)
                .setIngress(ingress)
                .build();
        LoggingFutures.addErrorLogging(packetProcessingService.transmitPacket(input), LOG, "transmitPacket");
    }
}
