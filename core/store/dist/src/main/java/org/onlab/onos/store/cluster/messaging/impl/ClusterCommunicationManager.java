package org.onlab.onos.store.cluster.messaging.impl;

import static com.google.common.base.Preconditions.checkArgument;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onlab.onos.cluster.ClusterService;
import org.onlab.onos.cluster.ControllerNode;
import org.onlab.onos.cluster.NodeId;
import org.onlab.onos.store.cluster.impl.ClusterMembershipEvent;
import org.onlab.onos.store.cluster.messaging.ClusterCommunicationService;
import org.onlab.onos.store.cluster.messaging.ClusterMessage;
import org.onlab.onos.store.cluster.messaging.ClusterMessageHandler;
import org.onlab.onos.store.cluster.messaging.ClusterMessageResponse;
import org.onlab.onos.store.cluster.messaging.MessageSubject;
import org.onlab.onos.store.serializers.ClusterMessageSerializer;
import org.onlab.onos.store.serializers.KryoNamespaces;
import org.onlab.onos.store.serializers.KryoSerializer;
import org.onlab.onos.store.serializers.MessageSubjectSerializer;
import org.onlab.util.KryoNamespace;
import org.onlab.netty.Endpoint;
import org.onlab.netty.Message;
import org.onlab.netty.MessageHandler;
import org.onlab.netty.MessagingService;
import org.onlab.netty.NettyMessagingService;
import org.onlab.netty.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(immediate = true)
@Service
public class ClusterCommunicationManager
        implements ClusterCommunicationService {

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    private ClusterService clusterService;

    // TODO: This probably should not be a OSGi service.
    private MessagingService messagingService;

    private static final KryoSerializer SERIALIZER = new KryoSerializer() {
        @Override
        protected void setupKryoPool() {
            serializerPool = KryoNamespace.newBuilder()
                    .register(KryoNamespaces.API)
                    .register(ClusterMessage.class, new ClusterMessageSerializer())
                    .register(ClusterMembershipEvent.class)
                    .register(byte[].class)
                    .register(MessageSubject.class, new MessageSubjectSerializer())
                    .build()
                    .populate(1);
        }

    };

    @Activate
    public void activate() {
        ControllerNode localNode = clusterService.getLocalNode();
        NettyMessagingService netty = new NettyMessagingService(localNode.ip().toString(), localNode.tcpPort());
        // FIXME: workaround until it becomes a service.
        try {
            netty.activate();
        } catch (Exception e) {
            // TODO Auto-generated catch block
            log.error("NettyMessagingService#activate", e);
        }
        messagingService = netty;
        log.info("Started");
    }

    @Deactivate
    public void deactivate() {
        // TODO: cleanup messageingService if needed.
        log.info("Stopped");
    }

    @Override
    public boolean broadcast(ClusterMessage message) throws IOException {
        boolean ok = true;
        final ControllerNode localNode = clusterService.getLocalNode();
        for (ControllerNode node : clusterService.getNodes()) {
            if (!node.equals(localNode)) {
                ok = unicast(message, node.id()) && ok;
            }
        }
        return ok;
    }

    @Override
    public boolean multicast(ClusterMessage message, Set<NodeId> nodes) throws IOException {
        boolean ok = true;
        final ControllerNode localNode = clusterService.getLocalNode();
        for (NodeId nodeId : nodes) {
            if (!nodeId.equals(localNode.id())) {
                ok = unicast(message, nodeId) && ok;
            }
        }
        return ok;
    }

    @Override
    public boolean unicast(ClusterMessage message, NodeId toNodeId) throws IOException {
        ControllerNode node = clusterService.getNode(toNodeId);
        checkArgument(node != null, "Unknown nodeId: %s", toNodeId);
        Endpoint nodeEp = new Endpoint(node.ip().toString(), node.tcpPort());
        try {
            messagingService.sendAsync(nodeEp,
                    message.subject().value(), SERIALIZER.encode(message));
            return true;
        } catch (IOException e) {
            log.trace("Failed to send cluster message to nodeId: " + toNodeId, e);
            throw e;
        }
    }

    @Override
    public ClusterMessageResponse sendAndReceive(ClusterMessage message, NodeId toNodeId) throws IOException {
        ControllerNode node = clusterService.getNode(toNodeId);
        checkArgument(node != null, "Unknown nodeId: %s", toNodeId);
        Endpoint nodeEp = new Endpoint(node.ip().toString(), node.tcpPort());
        try {
            Response responseFuture =
                    messagingService.sendAndReceive(nodeEp, message.subject().value(), SERIALIZER.encode(message));
            return new InternalClusterMessageResponse(toNodeId, responseFuture);

        } catch (IOException e) {
            log.error("Failed interaction with remote nodeId: " + toNodeId, e);
            throw e;
        }
    }

    @Override
    public void addSubscriber(MessageSubject subject,
            ClusterMessageHandler subscriber) {
        messagingService.registerHandler(subject.value(), new InternalClusterMessageHandler(subscriber));
    }

    private final class InternalClusterMessageHandler implements MessageHandler {

        private final ClusterMessageHandler handler;

        public InternalClusterMessageHandler(ClusterMessageHandler handler) {
            this.handler = handler;
        }

        @Override
        public void handle(Message message) {
            try {
                ClusterMessage clusterMessage = SERIALIZER.decode(message.payload());
                handler.handle(new InternalClusterMessage(clusterMessage, message));
            } catch (Exception e) {
                log.error("Exception caught during ClusterMessageHandler", e);
                throw e;
            }
        }
    }

    public static final class InternalClusterMessage extends ClusterMessage {

        private final Message rawMessage;

        public InternalClusterMessage(ClusterMessage clusterMessage, Message rawMessage) {
            super(clusterMessage.sender(), clusterMessage.subject(), clusterMessage.payload());
            this.rawMessage = rawMessage;
        }

        @Override
        public void respond(byte[] response) throws IOException {
            rawMessage.respond(response);
        }
    }

    private static final class InternalClusterMessageResponse
        implements ClusterMessageResponse {

        private final NodeId sender;
        private final Response responseFuture;
        private volatile boolean isCancelled = false;
        private volatile boolean isDone = false;

        public InternalClusterMessageResponse(NodeId sender, Response responseFuture) {
            this.sender = sender;
            this.responseFuture = responseFuture;
        }
        @Override
        public NodeId sender() {
            return sender;
        }

        @Override
        public byte[] get(long timeout, TimeUnit timeunit)
                throws TimeoutException {
            final byte[] result = responseFuture.get(timeout, timeunit);
            isDone = true;
            return result;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (isDone()) {
                return false;
            }
            // doing nothing for now
            // when onlab.netty Response support cancel, call them.
            isCancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return isCancelled;
        }

        @Override
        public boolean isDone() {
            return this.isDone || isCancelled();
        }

        @Override
        public byte[] get() throws InterruptedException, ExecutionException {
            // TODO: consider forbidding this call and force the use of timed get
            //       to enforce handling of remote peer failure scenario
            final byte[] result = responseFuture.get();
            isDone = true;
            return result;
        }
    }
}
