/*
 * Copyright (c) 2018 Ericsson India Global Services Pvt Ltd. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.openflowplugin.applications.frm.impl;

import static org.opendaylight.openflowplugin.applications.frm.util.FrmUtil.buildGroupInstanceIdentifier;
import static org.opendaylight.openflowplugin.applications.frm.util.FrmUtil.getFlowId;
import static org.opendaylight.openflowplugin.applications.frm.util.FrmUtil.getNodeIdFromNodeIdentifier;
import static org.opendaylight.openflowplugin.applications.frm.util.FrmUtil.isFlowDependentOnGroup;
import static org.opendaylight.openflowplugin.applications.frm.util.FrmUtil.isGroupExistsOnDevice;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.opendaylight.infrautils.utils.concurrent.LoggingFutures;
import org.opendaylight.mdsal.binding.api.ReadTransaction;
import org.opendaylight.mdsal.common.api.LogicalDatastoreType;
import org.opendaylight.openflowplugin.applications.frm.ForwardingRulesManager;
import org.opendaylight.openflowplugin.applications.frm.NodeConfigurator;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.Uri;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.FlowCapableNode;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.tables.table.Flow;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.types.rev131026.FlowRef;
import org.opendaylight.yang.gen.v1.urn.opendaylight.group.service.rev130918.AddGroupInputBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.group.types.rev131018.GroupRef;
import org.opendaylight.yang.gen.v1.urn.opendaylight.group.types.rev131018.groups.Group;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.NodeId;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.NodeRef;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.nodes.Node;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflowplugin.extension.onf.bundle.service.rev170124.AddBundleMessagesInput;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflowplugin.extension.onf.bundle.service.rev170124.AddBundleMessagesInputBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflowplugin.extension.onf.bundle.service.rev170124.AddBundleMessagesOutput;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflowplugin.extension.onf.bundle.service.rev170124.add.bundle.messages.input.MessagesBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflowplugin.extension.onf.bundle.service.rev170124.add.bundle.messages.input.messages.Message;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflowplugin.extension.onf.bundle.service.rev170124.add.bundle.messages.input.messages.MessageBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflowplugin.extension.onf.bundle.service.rev170124.bundle.inner.message.grouping.BundleInnerMessage;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflowplugin.extension.onf.bundle.service.rev170124.bundle.inner.message.grouping.bundle.inner.message.BundleAddFlowCaseBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflowplugin.extension.onf.bundle.service.rev170124.bundle.inner.message.grouping.bundle.inner.message.BundleAddGroupCaseBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflowplugin.extension.onf.bundle.service.rev170124.bundle.inner.message.grouping.bundle.inner.message.BundleRemoveFlowCaseBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflowplugin.extension.onf.bundle.service.rev170124.bundle.inner.message.grouping.bundle.inner.message.BundleUpdateFlowCaseBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflowplugin.extension.onf.bundle.service.rev170124.bundle.inner.message.grouping.bundle.inner.message.bundle.add.flow._case.AddFlowCaseDataBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflowplugin.extension.onf.bundle.service.rev170124.bundle.inner.message.grouping.bundle.inner.message.bundle.add.group._case.AddGroupCaseDataBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflowplugin.extension.onf.bundle.service.rev170124.bundle.inner.message.grouping.bundle.inner.message.bundle.remove.flow._case.RemoveFlowCaseDataBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflowplugin.extension.onf.bundle.service.rev170124.bundle.inner.message.grouping.bundle.inner.message.bundle.update.flow._case.UpdateFlowCaseDataBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflowplugin.extension.onf.rev170124.BundleFlags;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflowplugin.extension.onf.rev170124.BundleId;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.opendaylight.yangtools.yang.common.RpcResult;
import org.opendaylight.yangtools.yang.common.RpcResultBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BundleFlowForwarder {

    private static final Logger LOG = LoggerFactory.getLogger(BundleFlowForwarder.class);
    private static final BundleFlags BUNDLE_FLAGS = new BundleFlags(true, true);
    private final ForwardingRulesManager forwardingRulesManager;
    private final NodeConfigurator nodeConfigurator;

    public BundleFlowForwarder(ForwardingRulesManager forwardingRulesManager) {
        this.forwardingRulesManager = Preconditions.checkNotNull(forwardingRulesManager,
                "ForwardingRulesManager can not be null!");
        this.nodeConfigurator = Preconditions.checkNotNull(forwardingRulesManager.getNodeConfigurator(),
                "NodeConfigurator can not be null!");
    }

    public void remove(final InstanceIdentifier<Flow> identifier, final Flow flow,
            final InstanceIdentifier<FlowCapableNode> nodeIdent, final BundleId bundleId) {
        final List<Message> messages = new ArrayList<>(1);
        String node = nodeIdent.firstKeyOf(Node.class).getId().getValue();
        BundleInnerMessage bundleInnerMessage = new BundleRemoveFlowCaseBuilder()
                .setRemoveFlowCaseData(new RemoveFlowCaseDataBuilder(flow).build()).build();
        Message message = new MessageBuilder().setNode(new NodeRef(nodeIdent.firstIdentifierOf(Node.class)))
                .setBundleInnerMessage(bundleInnerMessage).build();
        messages.add(message);
        AddBundleMessagesInput addBundleMessagesInput = new AddBundleMessagesInputBuilder()
                .setNode(new NodeRef(nodeIdent.firstIdentifierOf(Node.class))).setBundleId(bundleId)
                .setFlags(BUNDLE_FLAGS).setMessages(new MessagesBuilder().setMessage(messages).build()).build();
        final ListenableFuture<RpcResult<AddBundleMessagesOutput>> resultFuture = forwardingRulesManager
                .getSalBundleService().addBundleMessages(addBundleMessagesInput);
        LOG.trace("Pushing flow remove message {} to bundle {} for device {}", addBundleMessagesInput,
                bundleId.getValue(), node);
        LoggingFutures.addErrorLogging(resultFuture, LOG, "removeBundleFlow");
    }

    public void update(final InstanceIdentifier<Flow> identifier, final Flow originalFlow, final Flow updatedFlow,
            final InstanceIdentifier<FlowCapableNode> nodeIdent, final BundleId bundleId) {
        final NodeId nodeId = getNodeIdFromNodeIdentifier(nodeIdent);
        nodeConfigurator.enqueueJob(nodeId.getValue(), () -> {
            BundleInnerMessage innerDeleteMessage = new BundleRemoveFlowCaseBuilder()
                    .setRemoveFlowCaseData(new RemoveFlowCaseDataBuilder(originalFlow).build()).build();
            Message deleteMessage = new MessageBuilder().setNode(new NodeRef(nodeIdent.firstIdentifierOf(Node.class)))
                    .setBundleInnerMessage(innerDeleteMessage).build();
            BundleInnerMessage innerUpdateMessage = new BundleUpdateFlowCaseBuilder()
                    .setUpdateFlowCaseData(new UpdateFlowCaseDataBuilder(updatedFlow).build()).build();
            Message updateMessage = new MessageBuilder().setNode(new NodeRef(nodeIdent.firstIdentifierOf(Node.class)))
                    .setBundleInnerMessage(innerUpdateMessage).build();
            ListenableFuture<RpcResult<AddBundleMessagesOutput>> groupFuture = pushDependentGroup(nodeIdent,
                    updatedFlow, identifier, bundleId);
            List<Message> messages = Arrays.asList(deleteMessage, updateMessage);
            SettableFuture<RpcResult<AddBundleMessagesOutput>> resultFuture = SettableFuture.create();
            Futures.addCallback(groupFuture, new BundleFlowCallBack(nodeIdent, bundleId, messages, resultFuture),
                    MoreExecutors.directExecutor());
            return resultFuture;
        });
    }

    public Future<? extends RpcResult<?>> add(final InstanceIdentifier<Flow> identifier, final Flow flow,
            final InstanceIdentifier<FlowCapableNode> nodeIdent, final BundleId bundleId) {
        final NodeId nodeId = getNodeIdFromNodeIdentifier(nodeIdent);
        return nodeConfigurator.enqueueJob(nodeId.getValue(), () -> {
            BundleInnerMessage bundleInnerMessage = new BundleAddFlowCaseBuilder()
                    .setAddFlowCaseData(new AddFlowCaseDataBuilder(flow).build()).build();
            Message message = new MessageBuilder().setNode(new NodeRef(nodeIdent.firstIdentifierOf(Node.class)))
                    .setBundleInnerMessage(bundleInnerMessage).build();
            ListenableFuture<RpcResult<AddBundleMessagesOutput>> groupFuture = pushDependentGroup(nodeIdent, flow,
                    identifier, bundleId);
            SettableFuture<RpcResult<AddBundleMessagesOutput>> resultFuture = SettableFuture.create();
            Futures.addCallback(groupFuture, new BundleFlowCallBack(nodeIdent, bundleId,
                            Collections.singletonList(message), resultFuture),
                    MoreExecutors.directExecutor());
            return resultFuture;
        });
    }

    private ListenableFuture<RpcResult<AddBundleMessagesOutput>> pushDependentGroup(
            final InstanceIdentifier<FlowCapableNode> nodeIdent, Flow updatedFlow, InstanceIdentifier<Flow> identifier,
            BundleId bundleId) {
        //TODO This read to the DS might have a performance impact.
        //if the dependent group is not installed than we should just cache the parent group,
        //till we receive the dependent group DTCN and then push it.
        Long groupId = isFlowDependentOnGroup(updatedFlow);
        ListenableFuture<RpcResult<AddBundleMessagesOutput>> resultFuture;
        if (groupId != null) {
            LOG.trace("The flow {} is dependent on group {}. Checking if the group is already present",
                    getFlowId(new FlowRef(identifier)), groupId);
            if (isGroupExistsOnDevice(nodeIdent, groupId, forwardingRulesManager)) {
                LOG.trace("The dependent group {} is already programmed. Updating the flow {}", groupId,
                        getFlowId(new FlowRef(identifier)));
                resultFuture = Futures.immediateFuture(RpcResultBuilder.<AddBundleMessagesOutput>success().build());
            } else {
                LOG.trace("The dependent group {} isn't programmed yet. Pushing the group", groupId);
                InstanceIdentifier<Group> groupIdent = buildGroupInstanceIdentifier(nodeIdent, groupId);
                LOG.info("Reading the group from config inventory: {}", groupId);
                try (ReadTransaction readTransaction = forwardingRulesManager.getReadTransaction()) {
                    Optional<Group> group = readTransaction.read(LogicalDatastoreType.CONFIGURATION, groupIdent).get();
                    if (group.isPresent()) {
                        final AddGroupInputBuilder builder = new AddGroupInputBuilder(group.get());
                        builder.setNode(new NodeRef(nodeIdent.firstIdentifierOf(Node.class)));
                        builder.setGroupRef(new GroupRef(nodeIdent));
                        builder.setTransactionUri(new Uri(forwardingRulesManager.getNewTransactionId()));
                        BundleInnerMessage bundleInnerMessage = new BundleAddGroupCaseBuilder()
                                .setAddGroupCaseData(new AddGroupCaseDataBuilder(group.get()).build()).build();
                        Message groupMessage = new MessageBuilder().setNode(
                                new NodeRef(nodeIdent.firstIdentifierOf(Node.class)))
                                .setBundleInnerMessage(bundleInnerMessage).build();
                        final List<Message> messages = new ArrayList<>(1);
                        messages.add(groupMessage);
                        AddBundleMessagesInput addBundleMessagesInput = new AddBundleMessagesInputBuilder()
                                .setNode(new NodeRef(nodeIdent.firstIdentifierOf(Node.class))).setBundleId(bundleId)
                                .setFlags(BUNDLE_FLAGS).setMessages(new MessagesBuilder().setMessage(messages).build())
                                .build();
                        LOG.trace("Pushing flow update message {} to bundle {} for device {}", addBundleMessagesInput,
                                bundleId.getValue(), getNodeIdFromNodeIdentifier(nodeIdent));
                        resultFuture = forwardingRulesManager
                                .getSalBundleService().addBundleMessages(addBundleMessagesInput);
                        Futures.transformAsync(resultFuture, rpcResult -> {
                            if (rpcResult.isSuccessful()) {
                                forwardingRulesManager.getDevicesGroupRegistry()
                                        .storeGroup(getNodeIdFromNodeIdentifier(nodeIdent), groupId);
                                LOG.trace("Group {} stored in cache", groupId);
                            }
                            return Futures.immediateFuture(null);
                        }, MoreExecutors.directExecutor());
                    } else {
                        LOG.debug("Group {} not present in the config inventory", groupId);
                        resultFuture = Futures.immediateFuture(RpcResultBuilder.<AddBundleMessagesOutput>success()
                                .build());
                    }
                } catch (InterruptedException | ExecutionException e) {
                    LOG.error("Error while reading group from config datastore for the group ID {}", groupId, e);
                    resultFuture = Futures.immediateFuture(RpcResultBuilder.<AddBundleMessagesOutput>success().build());
                }
            }
        } else {
            resultFuture = Futures.immediateFuture(RpcResultBuilder.<AddBundleMessagesOutput>success().build());
        }
        return resultFuture;
    }

    private final class BundleFlowCallBack implements FutureCallback<RpcResult<AddBundleMessagesOutput>> {
        private final InstanceIdentifier<FlowCapableNode> nodeIdent;
        private final BundleId bundleId;
        private final List<Message> messages;
        private final NodeId nodeId;
        private final SettableFuture<RpcResult<AddBundleMessagesOutput>> resultFuture;

        BundleFlowCallBack(InstanceIdentifier<FlowCapableNode> nodeIdent, BundleId bundleId, List<Message> messages,
                SettableFuture<RpcResult<AddBundleMessagesOutput>> resultFuture) {
            this.nodeIdent = nodeIdent;
            this.bundleId = bundleId;
            this.messages = messages;
            this.resultFuture = resultFuture;
            nodeId = getNodeIdFromNodeIdentifier(nodeIdent);
        }

        @Override
        public void onSuccess(RpcResult<AddBundleMessagesOutput> rpcResult) {
            if (rpcResult.isSuccessful()) {
                AddBundleMessagesInput addBundleMessagesInput = new AddBundleMessagesInputBuilder()
                        .setNode(new NodeRef(nodeIdent.firstIdentifierOf(Node.class))).setBundleId(bundleId)
                        .setFlags(BUNDLE_FLAGS).setMessages(new MessagesBuilder().setMessage(messages).build()).build();

                LOG.trace("Pushing flow add message {} to bundle {} for device {}", addBundleMessagesInput,
                        bundleId.getValue(), nodeId.getValue());

                final ListenableFuture<RpcResult<AddBundleMessagesOutput>> addFuture =
                        forwardingRulesManager.getSalBundleService().addBundleMessages(addBundleMessagesInput);
                Futures.addCallback(addFuture, new FutureCallback<RpcResult<AddBundleMessagesOutput>>() {
                    @Override
                    public void onSuccess(RpcResult<AddBundleMessagesOutput> result) {
                        resultFuture.set(result);
                    }

                    @Override
                    public void onFailure(Throwable failure) {
                        resultFuture.setException(failure);
                    }
                },  MoreExecutors.directExecutor());
            } else {
                resultFuture.set(rpcResult);
            }
        }

        @Override
        public void onFailure(Throwable throwable) {
            LOG.error("Error while pushing flow add bundle {} for device {}", messages, nodeId);
            resultFuture.setException(throwable);
        }
    }
}
