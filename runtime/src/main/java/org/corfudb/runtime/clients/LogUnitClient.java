package org.corfudb.runtime.clients;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.corfudb.protocols.service.CorfuProtocolMessage.ClusterIdCheck;
import org.corfudb.protocols.service.CorfuProtocolMessage.EpochCheck;
import org.corfudb.protocols.wireprotocol.DataType;
import org.corfudb.protocols.wireprotocol.ILogData;
import org.corfudb.protocols.wireprotocol.InspectAddressesResponse;
import org.corfudb.protocols.wireprotocol.LogData;
import org.corfudb.protocols.wireprotocol.KnownAddressResponse;
import org.corfudb.protocols.wireprotocol.ReadResponse;
import org.corfudb.protocols.wireprotocol.StreamsAddressResponse;
import org.corfudb.protocols.wireprotocol.TailsResponse;
import org.corfudb.protocols.wireprotocol.Token;
import org.corfudb.runtime.proto.service.LogUnit.TailRequestMsg.Type;
import org.corfudb.util.serializer.Serializers;

import static org.corfudb.protocols.service.CorfuProtocolLogUnit.getCommittedTailRequestMsg;
import static org.corfudb.protocols.service.CorfuProtocolLogUnit.getCompactRequestMsg;
import static org.corfudb.protocols.service.CorfuProtocolLogUnit.getFlushCacheRequestMsg;
import static org.corfudb.protocols.service.CorfuProtocolLogUnit.getInspectAddressesRequestMsg;
import static org.corfudb.protocols.service.CorfuProtocolLogUnit.getKnownAddressRequestMsg;
import static org.corfudb.protocols.service.CorfuProtocolLogUnit.getLogAddressSpaceRequestMsg;
import static org.corfudb.protocols.service.CorfuProtocolLogUnit.getRangeWriteLogRequestMsg;
import static org.corfudb.protocols.service.CorfuProtocolLogUnit.getReadLogRequestMsg;
import static org.corfudb.protocols.service.CorfuProtocolLogUnit.getResetLogUnitRequestMsg;
import static org.corfudb.protocols.service.CorfuProtocolLogUnit.getTailRequestMsg;
import static org.corfudb.protocols.service.CorfuProtocolLogUnit.getTrimLogRequestMsg;
import static org.corfudb.protocols.service.CorfuProtocolLogUnit.getTrimMarkRequestMsg;
import static org.corfudb.protocols.service.CorfuProtocolLogUnit.getUpdateCommittedTailRequestMsg;
import static org.corfudb.protocols.service.CorfuProtocolLogUnit.getWriteLogRequestMsg;

import java.io.FileOutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.ibm.darpc.DaRPCClientEndpoint;
import com.ibm.darpc.DaRPCClientGroup;
import com.ibm.darpc.DaRPCEndpoint;
import com.ibm.darpc.DaRPCFuture;
import com.ibm.darpc.DaRPCStream;
import com.ibm.disni.util.*;

import org.corfudb.protocols.rdma.RdmaRpcRequest;
import org.corfudb.protocols.rdma.RdmaRpcResponse;
import org.corfudb.protocols.rdma.RdmaRpcProtocol;

import lombok.extern.slf4j.Slf4j;

import static org.corfudb.protocols.service.CorfuProtocolLogUnit.getRdmaServerRequestMsg;
import static org.corfudb.protocols.service.CorfuProtocolLogUnit.getRdmaServerResponseMsg;

import org.corfudb.runtime.proto.service.LogUnit.RdmaServerRequestMsg;
import org.corfudb.runtime.proto.service.LogUnit.RdmaServerResponseMsg;

import org.corfudb.util.CFUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A client to send messages to a LogUnit.
 *
 * <p>This class provides access to operations on a remote log unit.
 * Created by mwei on 12/10/15.
 */
public class LogUnitClient extends AbstractClient {

    String rdmaHost;
    int rdmaPort;
    ConcurrentHashMap<Integer, CompletableFuture<Boolean>> writeFutureMap = new ConcurrentHashMap<>();
    private AtomicInteger writeRequestID = new AtomicInteger(0);

    public LogUnitClient(IClientRouter router, long epoch, UUID clusterID) {
        super(router, epoch, clusterID);

        RdmaServerResponseMsg response = CFUtils.getUninterruptibly(sendRequestWithFuture(getRdmaServerRequestMsg(), ClusterIdCheck.IGNORE, EpochCheck.IGNORE));
        this.rdmaHost = response.getHost();
        this.rdmaPort = (int) response.getPort();
        if (this.rdmaPort == -1) {
            System.out.println("Rdma not available, we fall back to ethernet");
            return;
        }
        System.out.println("Rdma server available: " + this.rdmaHost + ":" + this.rdmaPort);
        System.out.println("Init rdma client...");
        try {
            this.initRdmaClient();
        } catch (Exception e) {
            System.out.println("failed to init rdma client" + e.toString());
            e.printStackTrace();
        }
        
    }

    public String getHost() {
        return getRouter().getHost();
    }

    public Integer getPort() {
        return getRouter().getPort();
    }

    /**
     * Asynchronously write to the logging unit.
     *
     * @param address        the address to write to.
     * @param writeObject    the object, pre-serialization, to write.
     * @param backpointerMap the map of backpointers to write.
     * @return a completable future which returns true on success.
     */
    public CompletableFuture<Boolean> write(long address,
                                            Object writeObject,
                                            Map<UUID, Long> backpointerMap) {
        ByteBuf payload = Unpooled.buffer();
        Serializers.CORFU.serialize(writeObject, payload);
        LogData logData = new LogData(DataType.DATA, payload);
        logData.setBackpointerMap(backpointerMap);
        logData.setGlobalAddress(address);
        return sendRequestWithFuture(getWriteLogRequestMsg(logData), ClusterIdCheck.CHECK, EpochCheck.CHECK);
    }

    private DaRPCClientGroup<RdmaRpcRequest, RdmaRpcResponse> rdmaGroup = null;
    private DaRPCStream<RdmaRpcRequest, RdmaRpcResponse> rdmaStream = null;
    private DaRPCClientEndpoint<RdmaRpcRequest, RdmaRpcResponse> rdmaEndpoint = null;
    

    private void initRdmaClient() throws Exception {
		int size = 24;
		int loop = 100;
		int threadCount = 1;
		int batchSize = 16;
		int connections = 1;
		int clienttimeout = 3000;
		int maxinline = 0;
		int recvQueue = batchSize;
		int sendQueue = batchSize;

        RdmaRpcProtocol rpcProtocol = new RdmaRpcProtocol();
        this.rdmaGroup = DaRPCClientGroup.createClientGroup(rpcProtocol, 100, maxinline, recvQueue, sendQueue);
        InetSocketAddress address = new InetSocketAddress(this.rdmaHost, this.rdmaPort);
        this.rdmaEndpoint = this.rdmaGroup.createEndpoint();
        this.rdmaEndpoint.connect(address, 1000);
        this.rdmaStream = this.rdmaEndpoint.createStream();

        new Thread(() -> {
            rdmaPoll();
        }).start();
    }

    void rdmaPoll() {
        while(true && !Thread.interrupted()) {
            try {
                RdmaRpcResponse response = this.rdmaStream.take(1000).getReceiveMessage();
                int requestID = response.getRequestID();
                CompletableFuture<Boolean> cf;
                if ((cf = (CompletableFuture<Boolean>) writeFutureMap.remove(requestID)) != null) {
                    cf.complete(true);
                    // System.out.println("We got some RDMA response from server: " + response.toString());
                } else {
                    System.out.println("Attempted to complete request" + requestID + ", but request not outstanding!");
                }
            } catch (Exception e) {
                System.out.println("failed to poll rdma response" + e.toString());
                e.printStackTrace();
            }
        }

        try {
            this.rdmaEndpoint.close();
            this.rdmaGroup.close();
        } catch (Exception e) {
            System.out.println("failed to close rdma channel" + e.toString());
            e.printStackTrace();
            System.out.println("");
        }
    }

    /**
     * Asynchronously write to the logging unit.
     *
     * @param payload The log data to write to the logging unit.
     * @return a completable future which returns true on success.
     */
    public CompletableFuture<Boolean> write(ILogData payload) {
        // We check for rdma client
        try {
            if (this.rdmaPort != -1 && this.rdmaStream != null) {
                LogData logData = (LogData) payload;
                RdmaRpcRequest request = new RdmaRpcRequest();
                RdmaRpcResponse response = new RdmaRpcResponse();
                ByteBuf buf = Unpooled.buffer();
                boolean streamMode = true;
    
                int requestID = writeRequestID.getAndIncrement();
                request.setRequestID(requestID);
                request.setCmd(0);
                logData.doSerialize(buf);
                request.setLogDataBuffer(buf.nioBuffer());
    
                final CompletableFuture<Boolean> cf = new CompletableFuture<>();
                writeFutureMap.put(requestID, cf);
    
                this.rdmaStream.request(request, response, streamMode);
    
                return cf;
            }
        } catch (Exception e) {
            System.out.println("failed to write with rdma, fall back to slow path" + e.toString());
            e.printStackTrace();
        }
        
        return sendRequestWithFuture(getWriteLogRequestMsg((LogData) payload), ClusterIdCheck.CHECK, EpochCheck.CHECK);
    }

    /**
     * Sends a request to write a list of addresses.
     *
     * @param range entries to write to the log unit. Must have at least one entry.
     * @return a completable future which returns true on success.
     */
    public CompletableFuture<Boolean> writeRange(List<LogData> range) {
        if (range.isEmpty()) {
            throw new IllegalArgumentException("Can't write an empty range");
        }

        long base = range.get(0).getGlobalAddress();
        for (int x = 0; x < range.size(); x++) {
            LogData curr = range.get(x);
            if (!curr.getGlobalAddress().equals(base + x)) {
                throw new IllegalArgumentException("Entries not in sequential order!");
            } else if (curr.isEmpty()) {
                throw new IllegalArgumentException("Can't write empty entries!");
            }
        }

        return sendRequestWithFuture(getRangeWriteLogRequestMsg(range), ClusterIdCheck.CHECK, EpochCheck.CHECK);
    }

    /**
     * Asynchronously read from the logging unit.
     * Read result is cached at log unit server.
     *
     * @param address the address to read from.
     * @return a completableFuture which returns a ReadResponse on completion.
     */
    public CompletableFuture<ReadResponse> read(long address) {
        return read(Collections.singletonList(address), true);
    }

    /**
     * Asynchronously read from the logging unit.
     *
     * @param addresses the addresses to read from.
     * @param cacheable whether the read result should be cached on log unit server.
     * @return a completableFuture which returns a ReadResponse on completion.
     */
    public CompletableFuture<ReadResponse> read(List<Long> addresses, boolean cacheable) {
        return sendRequestWithFuture(getReadLogRequestMsg(addresses, cacheable), ClusterIdCheck.CHECK, EpochCheck.CHECK);
    }

    /**
     * Check if addresses are committed on log unit server, which returns a future
     * with uncommitted addresses (holes) on the server.
     *
     * @param addresses list of global addresses to inspect
     * @return a completableFuture which returns an InspectAddressesResponse
     */
    public CompletableFuture<InspectAddressesResponse> inspectAddresses(List<Long> addresses) {
        return sendRequestWithFuture(getInspectAddressesRequestMsg(addresses), ClusterIdCheck.CHECK, EpochCheck.CHECK);
    }

    /**
     * Get the global tail maximum address the log unit has written.
     *
     * @return a CompletableFuture which will complete with the globalTail once
     * received.
     */
    public CompletableFuture<TailsResponse> getLogTail() {
        return sendRequestWithFuture(getTailRequestMsg(Type.LOG_TAIL), ClusterIdCheck.CHECK, EpochCheck.CHECK);
    }

    /**
     * Get all stream tails (i.e., maximum address written to every stream) and global tail.
     *
     * @return A CompletableFuture which will complete with the stream tails once
     * received.
     */
    public CompletableFuture<TailsResponse> getAllTails() {
        return sendRequestWithFuture(getTailRequestMsg(Type.ALL_STREAMS_TAIL), ClusterIdCheck.CHECK, EpochCheck.CHECK);
    }

    /**
     * Get the committed tail of the log unit.
     *
     * @return a CompletableFuture which will complete with the committed tail once received.
     */
    public CompletableFuture<Long> getCommittedTail() {
        return sendRequestWithFuture(getCommittedTailRequestMsg(), ClusterIdCheck.CHECK, EpochCheck.CHECK);
    }

    /**
     * Update the committed tail of the log unit.
     *
     * @param committedTail new committed tail to update
     * @return an empty completableFuture
     */
    public CompletableFuture<Void> updateCommittedTail(long committedTail) {
        return sendRequestWithFuture(getUpdateCommittedTailRequestMsg(committedTail), ClusterIdCheck.CHECK, EpochCheck.CHECK);
    }

    /**
     * Get the address space for all streams in the log.
     *
     * @return A CompletableFuture which will complete with the address space map for all streams.
     */
    public CompletableFuture<StreamsAddressResponse> getLogAddressSpace() {
        return sendRequestWithFuture(getLogAddressSpaceRequestMsg(), ClusterIdCheck.CHECK, EpochCheck.CHECK);
    }

    /**
     * Get the starting address of a log unit.
     *
     * @return a CompletableFuture for the starting address
     */
    public CompletableFuture<Long> getTrimMark() {
        return sendRequestWithFuture(getTrimMarkRequestMsg(), ClusterIdCheck.CHECK, EpochCheck.CHECK);
    }

    /**
     * Request for known addresses in the specified range.
     *
     * @param startRange Start of range (inclusive).
     * @param endRange   End of range (inclusive).
     * @return Known addresses.
     */
    public CompletableFuture<KnownAddressResponse> requestKnownAddresses(long startRange, long endRange) {
        return sendRequestWithFuture(getKnownAddressRequestMsg(startRange, endRange), ClusterIdCheck.CHECK, EpochCheck.CHECK);
    }

    /**
     * Send a prefix trim request that will trim the log up to a certain address
     *
     * @param address an address to trim up to (i.e. [0, address))
     * @return an empty completableFuture
     */
    public CompletableFuture<Void> prefixTrim(Token address) {
        return sendRequestWithFuture(getTrimLogRequestMsg(address), ClusterIdCheck.CHECK, EpochCheck.CHECK);
    }

    /**
     * Send a compact request that will delete the trimmed parts of the log.
     */
    public CompletableFuture<Void> compact() {
        return sendRequestWithFuture(getCompactRequestMsg(), ClusterIdCheck.CHECK, EpochCheck.IGNORE);
    }

    /**
     * Send a flush cache request that will flush the logunit cache.
     */
    public CompletableFuture<Void> flushCache() {
        return sendRequestWithFuture(getFlushCacheRequestMsg(), ClusterIdCheck.CHECK, EpochCheck.IGNORE);
    }

    /**
     * Send a reset request.
     *
     * @param epoch epoch to check and set epochWaterMark.
     * @return a completable future which returns true on success.
     */
    public CompletableFuture<Boolean> resetLogUnit(long epoch) {
        return sendRequestWithFuture(getResetLogUnitRequestMsg(epoch), ClusterIdCheck.CHECK, EpochCheck.IGNORE);
    }
}
