/* This file is part of VoltDB.
 * Copyright (C) 2008-2017 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.iv2;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.voltcore.logging.VoltLogger;
import org.voltcore.messaging.HostMessenger;
import org.voltcore.messaging.TransactionInfoBaseMessage;
import org.voltcore.messaging.VoltMessage;
import org.voltcore.utils.CoreUtils;
import org.voltdb.ClientResponseImpl;
import org.voltdb.CommandLog;
import org.voltdb.CommandLog.DurabilityListener;
import org.voltdb.Consistency;
import org.voltdb.Consistency.ReadLevel;
import org.voltdb.RealVoltDB;
import org.voltdb.SnapshotCompletionInterest;
import org.voltdb.SnapshotCompletionMonitor;
import org.voltdb.SystemProcedureCatalog;
import org.voltdb.VoltDB;
import org.voltdb.VoltTable;
import org.voltdb.client.ClientResponse;
import org.voltdb.dtxn.TransactionState;
import org.voltdb.exceptions.SerializableException;
import org.voltdb.exceptions.TransactionRestartException;
import org.voltdb.iv2.SiteTasker.SiteTaskerRunnable;
import org.voltdb.messaging.BorrowTaskMessage;
import org.voltdb.messaging.CompleteTransactionMessage;
import org.voltdb.messaging.CompleteTransactionResponseMessage;
import org.voltdb.messaging.DummyTransactionResponseMessage;
import org.voltdb.messaging.DummyTransactionTaskMessage;
import org.voltdb.messaging.DumpMessage;
import org.voltdb.messaging.DumpPlanThenExitMessage;
import org.voltdb.messaging.FragmentResponseMessage;
import org.voltdb.messaging.FragmentTaskMessage;
import org.voltdb.messaging.InitiateResponseMessage;
import org.voltdb.messaging.Iv2InitiateTaskMessage;
import org.voltdb.messaging.Iv2LogFaultMessage;
import org.voltdb.messaging.MultiPartitionParticipantMessage;
import org.voltdb.messaging.RepairLogTruncationMessage;
import org.voltdb.utils.MiscUtils;
import org.voltdb.utils.VoltTrace;
import com.google_voltpatches.common.primitives.Ints;
import com.google_voltpatches.common.primitives.Longs;
import com.google_voltpatches.common.util.concurrent.ListenableFuture;
import com.google_voltpatches.common.util.concurrent.SettableFuture;

public class SpScheduler extends Scheduler implements SnapshotCompletionInterest
{
    static final VoltLogger tmLog = new VoltLogger("TM");

    static class DuplicateCounterKey implements Comparable<DuplicateCounterKey> {
        private final long m_txnId;
        private final long m_spHandle;

        DuplicateCounterKey(long txnId, long spHandle) {
            m_txnId = txnId;
            m_spHandle = spHandle;
        }

        @Override
        public boolean equals(Object o) {
            try {
                DuplicateCounterKey other = (DuplicateCounterKey) o;
                return (m_txnId == other.m_txnId && m_spHandle == other.m_spHandle);
            }
            catch (Exception e) {
                return false;
            }
        }

        // Only care about comparing TXN ID part for sorting in updateReplicas
        @Override
        public int compareTo(DuplicateCounterKey o) {
            if (m_txnId < o.m_txnId) {
                return -1;
            } else if (m_txnId > o.m_txnId) {
                return 1;
            } else {
                if (m_spHandle < o.m_spHandle) {
                    return -1;
                }
                else if (m_spHandle > o.m_spHandle) {
                    return 1;
                }
                else {
                    return 0;
                }
            }
        }

        @Override
        public int hashCode() {
            assert(false) : "Hashing this is unsafe as it can't promise no collisions.";
            throw new UnsupportedOperationException(
                    "Hashing this is unsafe as it can't promise no collisions.");
        }

        @Override
        public String toString() {
            return "[txn:" + TxnEgo.txnIdToString(m_txnId) + "(" + m_txnId + "), spHandle:" + TxnEgo.txnIdToString(m_spHandle) + "(" + m_spHandle + ")]";
        }
    };

    public interface DurableUniqueIdListener {
        /**
         * Notify listener of last durable Single-Part and Multi-Part uniqueIds
         */
        public void lastUniqueIdsMadeDurable(long spUniqueId, long mpUniqueId);
    }

    List<Long> m_replicaHSIds = new ArrayList<Long>();
    long m_sendToHSIds[] = new long[0];
    private final TransactionTaskQueue m_pendingTasks;
    private final Map<Long, TransactionState> m_outstandingTxns =
        new HashMap<Long, TransactionState>();
    private final Map<DuplicateCounterKey, DuplicateCounter> m_duplicateCounters =
        new TreeMap<DuplicateCounterKey, DuplicateCounter>();
    // MP fragment tasks or completion tasks pending durability
    private final Map<Long, Queue<TransactionTask>> m_mpsPendingDurability =
        new HashMap<Long, Queue<TransactionTask>>();
    private CommandLog m_cl;
    private final SnapshotCompletionMonitor m_snapMonitor;
    // used to decide if we should shortcut reads
    private Consistency.ReadLevel m_defaultConsistencyReadLevel;
    private BufferedReadLog m_bufferedReadLog = null;

    // Need to track when command log replay is complete (even if not performed) so that
    // we know when we can start writing viable replay sets to the fault log.
    boolean m_replayComplete = false;
    // The DurabilityListener is not thread-safe. Access it only on the Site thread.
    private final DurabilityListener m_durabilityListener;
    // Generator of pre-IV2ish timestamp based unique IDs
    private final UniqueIdGenerator m_uniqueIdGenerator;

    // the current not-needed-any-more point of the repair log.
    long m_repairLogTruncationHandle = Long.MIN_VALUE;
    // the truncation handle last sent to the replicas
    long m_lastSentTruncationHandle = Long.MIN_VALUE;
    // the max schedule transaction sphandle, multi-fragments mp txn counts one
    long m_maxScheduledTxnSpHandle = Long.MIN_VALUE;

    // the checkpoint transaction sphandle upon BalanceSPI is initiated
    long m_spiCheckPoint = Long.MIN_VALUE;

    //The RepairLog is the same instance as the one initialized in InitiatorMailbox.
    //Iv2IniatiateTaskMessage, FragmentTaskMessage and CompleteTransactionMessage
    //are to be added to the repair log when these messages get updated transaction ids.
    protected RepairLog m_repairLog;

    SpScheduler(int partitionId, SiteTaskerQueue taskQueue, SnapshotCompletionMonitor snapMonitor)
    {
        super(partitionId, taskQueue);
        m_pendingTasks = new TransactionTaskQueue(m_tasks);
        m_snapMonitor = snapMonitor;
        m_durabilityListener = new SpDurabilityListener(this, m_pendingTasks);
        m_uniqueIdGenerator = new UniqueIdGenerator(partitionId, 0);

        // try to get the global default setting for read consistency, but fall back to SAFE
        m_defaultConsistencyReadLevel = VoltDB.Configuration.getDefaultReadConsistencyLevel();
        if (m_defaultConsistencyReadLevel == ReadLevel.SAFE) {
            m_bufferedReadLog = new BufferedReadLog();
        }
        m_repairLogTruncationHandle = getCurrentTxnId();
        // initialized as current txn id in order to release the initial reads into the system
        m_maxScheduledTxnSpHandle = getCurrentTxnId();
    }

    @Override
    public void setLeaderState(boolean isLeader)
    {
        super.setLeaderState(isLeader);
        m_snapMonitor.addInterest(this);
    }

    @Override
    public void setMaxSeenTxnId(long maxSeenTxnId)
    {
        super.setMaxSeenTxnId(maxSeenTxnId);
        writeIv2ViableReplayEntry();
    }

    @Override
    public void setDurableUniqueIdListener(final DurableUniqueIdListener listener) {
        m_tasks.offer(new SiteTaskerRunnable() {
            @Override
            void run()
            {
                m_durabilityListener.setUniqueIdListener(listener);
            }
        });
    }

    @Override
    public void shutdown()
    {
        m_tasks.offer(m_nullTask);
    }

    // This is going to run in the BabySitter's thread.  This and deliver are synchronized by
    // virtue of both being called on InitiatorMailbox and not directly called.
    // (That is, InitiatorMailbox's API, used by BabySitter, is synchronized on the same
    // lock deliver() is synchronized on.)
    @Override
    public void updateReplicas(List<Long> replicas, Map<Integer, Long> partitionMasters)
    {
        if (tmLog.isDebugEnabled()) {
            tmLog.debug("[updateReplicas] replicas: " + Arrays.toString(replicas.toArray()) + " on " + CoreUtils.hsIdToString(m_mailbox.getHSId()));
            if (partitionMasters != null) {
                if (partitionMasters.keySet() != null) {
                    tmLog.debug("[updateReplicas] partition master keys: " + Arrays.toString(partitionMasters.keySet().toArray()));
                }
                if (partitionMasters.values() != null) {
                    tmLog.debug("[updateReplicas] partition master values: " + Arrays.toString(partitionMasters.values().toArray()));
                }
            }
        }

        // First - correct the official replica set.
        m_replicaHSIds = replicas;
        // Update the list of remote replicas that we'll need to send to
        List<Long> sendToHSIds = new ArrayList<Long>(m_replicaHSIds);
        sendToHSIds.remove(m_mailbox.getHSId());
        m_sendToHSIds = Longs.toArray(sendToHSIds);
        // Cleanup duplicate counters and collect DONE counters
        // in this list for further processing.
        List<DuplicateCounterKey> doneCounters = new LinkedList<DuplicateCounterKey>();
        for (Entry<DuplicateCounterKey, DuplicateCounter> entry : m_duplicateCounters.entrySet()) {
            DuplicateCounter counter = entry.getValue();
            int result = counter.updateReplicas(m_replicaHSIds);
            if (result == DuplicateCounter.DONE) {
                doneCounters.add(entry.getKey());
            }
        }

        // Maintain the CI invariant that responses arrive in txnid order.
        Collections.sort(doneCounters);
        for (DuplicateCounterKey key : doneCounters) {
            DuplicateCounter counter = m_duplicateCounters.remove(key);

            final TransactionState txn = m_outstandingTxns.get(key.m_txnId);
            if (txn == null || txn.isDone()) {
                m_outstandingTxns.remove(key.m_txnId);
                // for MP write txns, we should use it's first SpHandle in the TransactionState
                // for SP write txns, we can just use the SpHandle from the DuplicateCounterKey
                long m_safeSpHandle = txn == null ? key.m_spHandle: txn.m_spHandle;
                setRepairLogTruncationHandle(m_safeSpHandle);
            }

            VoltMessage resp = counter.getLastResponse();
            if (resp != null) {
                // MPI is tracking deps per partition HSID.  We need to make
                // sure we write ours into the message getting sent to the MPI
                if (resp instanceof FragmentResponseMessage) {
                    FragmentResponseMessage fresp = (FragmentResponseMessage)resp;
                    fresp.setExecutorSiteId(m_mailbox.getHSId());
                }
                m_mailbox.send(counter.m_destinationId, resp);
            }
            else {
                hostLog.warn("TXN " + counter.getTxnId() + " lost all replicas and " +
                        "had no responses.  This should be impossible?");
            }
        }
        SettableFuture<Boolean> written = writeIv2ViableReplayEntry();

        // Get the fault log status here to ensure the leader has written it to disk
        // before initiating transactions again.
        blockFaultLogWriteStatus(written);
    }

    /**
     * Poll the replay sequencer and process the messages until it returns null
     */
    private void deliverReadyTxns() {
        // First, pull all the sequenced messages, if any.
        VoltMessage m = m_replaySequencer.poll();
        while(m != null) {
            deliver(m);
            m = m_replaySequencer.poll();
        }
        // Then, try to pull all the drainable messages, if any.
        m = m_replaySequencer.drain();
        while (m != null) {
            if (m instanceof Iv2InitiateTaskMessage) {
                // Send IGNORED response for all SPs
                Iv2InitiateTaskMessage task = (Iv2InitiateTaskMessage) m;
                final InitiateResponseMessage response = new InitiateResponseMessage(task);
                response.setResults(new ClientResponseImpl(ClientResponse.UNEXPECTED_FAILURE,
                            new VoltTable[0],
                            ClientResponseImpl.IGNORED_TRANSACTION));
                m_mailbox.send(response.getInitiatorHSId(), response);
            }
            m = m_replaySequencer.drain();
        }
    }

    /**
     * Sequence the message for replay if it's for CL or DR.
     *
     * @param message
     * @return true if the message can be delivered directly to the scheduler,
     * false if the message is queued
     */
    @Override
    public boolean sequenceForReplay(VoltMessage message)
    {
        boolean canDeliver = false;
        long sequenceWithUniqueId = Long.MIN_VALUE;

        boolean commandLog = (message instanceof TransactionInfoBaseMessage &&
                (((TransactionInfoBaseMessage)message).isForReplay()));

        boolean sentinel = message instanceof MultiPartitionParticipantMessage;

        boolean replay = commandLog || sentinel;
        boolean sequenceForReplay = m_isLeader && replay;

        if (replay) {
            sequenceWithUniqueId = ((TransactionInfoBaseMessage)message).getUniqueId();
        }

        if (sequenceForReplay) {
            InitiateResponseMessage dupe = m_replaySequencer.dedupe(sequenceWithUniqueId,
                    (TransactionInfoBaseMessage) message);
            if (dupe != null) {
                // Duplicate initiate task message, send response
                m_mailbox.send(dupe.getInitiatorHSId(), dupe);
            }
            else if (!m_replaySequencer.offer(sequenceWithUniqueId, (TransactionInfoBaseMessage) message)) {
                canDeliver = true;
            }
            else {
                deliverReadyTxns();
            }

            // If it's a DR sentinel, send an acknowledgement
            if (sentinel && !commandLog) {
                MultiPartitionParticipantMessage mppm = (MultiPartitionParticipantMessage) message;
                final InitiateResponseMessage response = new InitiateResponseMessage(mppm);
                ClientResponseImpl clientResponse =
                        new ClientResponseImpl(ClientResponseImpl.UNEXPECTED_FAILURE,
                                new VoltTable[0], ClientResponseImpl.IGNORED_TRANSACTION);
                response.setResults(clientResponse);
                m_mailbox.send(response.getInitiatorHSId(), response);
            }
        }
        else {
            if (replay) {
                // Update last seen and last polled uniqueId for replicas
                m_replaySequencer.updateLastSeenUniqueId(sequenceWithUniqueId,
                        (TransactionInfoBaseMessage) message);
                m_replaySequencer.updateLastPolledUniqueId(sequenceWithUniqueId,
                        (TransactionInfoBaseMessage) message);
            }

            canDeliver = true;
        }

        return canDeliver;
    }

    // SpInitiators will see every message type.  The Responses currently come
    // from local work, but will come from replicas when replication is
    // implemented
    @Override
    public void deliver(VoltMessage message)
    {
        if (message instanceof Iv2InitiateTaskMessage) {
            handleIv2InitiateTaskMessage((Iv2InitiateTaskMessage)message);
        }
        else if (message instanceof InitiateResponseMessage) {
            handleInitiateResponseMessage((InitiateResponseMessage)message);
        }
        else if (message instanceof FragmentTaskMessage) {
            handleFragmentTaskMessage((FragmentTaskMessage)message);
        }
        else if (message instanceof FragmentResponseMessage) {
            handleFragmentResponseMessage((FragmentResponseMessage)message);
        }
        else if (message instanceof CompleteTransactionMessage) {
            handleCompleteTransactionMessage((CompleteTransactionMessage)message);
        }
        else if (message instanceof CompleteTransactionResponseMessage) {
            handleCompleteTransactionResponseMessage((CompleteTransactionResponseMessage) message);
        }
        else if (message instanceof BorrowTaskMessage) {
            handleBorrowTaskMessage((BorrowTaskMessage)message);
        }
        else if (message instanceof Iv2LogFaultMessage) {
            handleIv2LogFaultMessage((Iv2LogFaultMessage)message);
        }
        else if (message instanceof DumpMessage) {
            handleDumpMessage();
        } else if (message instanceof DumpPlanThenExitMessage) {
            handleDumpPlanMessage((DumpPlanThenExitMessage)message);
        }
        else if (message instanceof DummyTransactionTaskMessage) {
            handleDummyTransactionTaskMessage((DummyTransactionTaskMessage) message);
        }
        else if (message instanceof DummyTransactionResponseMessage) {
            handleDummyTransactionResponseMessage((DummyTransactionResponseMessage)message);
        }
        else {
            throw new RuntimeException("UNKNOWN MESSAGE TYPE, BOOM!");
        }
    }

    // SpScheduler expects to see InitiateTaskMessages corresponding to single-partition
    // procedures only.
    private void handleIv2InitiateTaskMessage(Iv2InitiateTaskMessage message)
    {
        if (!message.isSinglePartition()) {
            throw new RuntimeException("SpScheduler.handleIv2InitiateTaskMessage " +
                    "should never receive multi-partition initiations.");
        }

        final String procedureName = message.getStoredProcedureName();
        long newSpHandle;
        long uniqueId = Long.MIN_VALUE;
        Iv2InitiateTaskMessage msg = message;
        if (m_isLeader || message.isReadOnly()) {
            /*
             * A short circuit read is a read where the client interface is local to
             * this node. The CI will let a replica perform a read in this case and
             * it does looser tracking of client handles since it can't be
             * partitioned from the local replica.
             */

            //When this site is marked as non-leader, m_isLeader will be immediately set to false.
            //Before new leader is installed, read-only messages may come from local (safe or fast read) and other CIs (safe read)
            //
            boolean balanceSPI = (message.getStoredProcedureInvocation() != null &&
                    "@BalanceSPI".equals(message.getStoredProcedureName()));
            final ReadLevel level = VoltDB.Configuration.getDefaultReadConsistencyLevel();
            if (!m_isLeader && !balanceSPI && level == ReadLevel.FAST && message.isReadOnly() &&
                    CoreUtils.getHostIdFromHSId(msg.getInitiatorHSId()) !=
                    CoreUtils.getHostIdFromHSId(m_mailbox.getHSId())) {
                hostLog.error("site:" + CoreUtils.hsIdToString(m_mailbox.getHSId())+ " is leader:" + m_isLeader + "\n" + message);
                VoltDB.crashLocalVoltDB("Only allowed to do short circuit reads locally", true, null);
            }

            /*
             * If this is for CL replay or DR, update the unique ID generator
             */
            if (message.isForReplay()) {
                uniqueId = message.getUniqueId();
                try {
                    m_uniqueIdGenerator.updateMostRecentlyGeneratedUniqueId(uniqueId);
                }
                catch (Exception e) {
                    hostLog.fatal(e.getMessage());
                    hostLog.fatal("Invocation: " + message);
                    VoltDB.crashLocalVoltDB(e.getMessage(), true, e);
                }
            }

            /*
             * If this is CL replay use the txnid from the CL and also
             * update the txnid to match the one from the CL
             */
            if (message.isForReplay()) {
                TxnEgo ego = advanceTxnEgo();
                newSpHandle = ego.getTxnId();
                updateMaxScheduledTransactionSpHandle(newSpHandle);
            } else if (m_isLeader && !message.isReadOnly()) {
                TxnEgo ego = advanceTxnEgo();
                newSpHandle = ego.getTxnId();
                updateMaxScheduledTransactionSpHandle(newSpHandle);
                uniqueId = m_uniqueIdGenerator.getNextUniqueId();
            } else {
                /*
                 * The SPI read or the short circuit read case. Since we are read only,
                 * do not create new transaction IDs but reuse the last seen
                 * txnid. For a timestamp, might as well give a reasonable one
                 * for a read heavy workload so time isn't bursty.
                 */
                uniqueId = UniqueIdGenerator.makeIdFromComponents(
                        Math.max(System.currentTimeMillis(), m_uniqueIdGenerator.lastUsedTime),
                        0,
                        m_uniqueIdGenerator.partitionId);

                newSpHandle = getMaxScheduledTxnSpHandle();
            }

            // Need to set the SP handle on the received message
            // Need to copy this or the other local sites handling
            // the same initiate task message will overwrite each
            // other's memory -- the message isn't copied on delivery
            // to other local mailboxes.
            msg = new Iv2InitiateTaskMessage(
                    message.getInitiatorHSId(),
                    message.getCoordinatorHSId(),
                    getRepairLogTruncationHandleForReplicas(),
                    message.getTxnId(),
                    message.getUniqueId(),
                    message.isReadOnly(),
                    message.isSinglePartition(),
                    message.getStoredProcedureInvocation(),
                    message.getClientInterfaceHandle(),
                    message.getConnectionId(),
                    message.isForReplay());

            msg.setSpHandle(newSpHandle);
            logRepair(msg);
            // Also, if this is a vanilla single-part procedure, make the TXNID
            // be the SpHandle (for now)
            // Only system procedures are every-site, so we'll check through the SystemProcedureCatalog
            if (SystemProcedureCatalog.listing.get(procedureName) == null ||
                    !SystemProcedureCatalog.listing.get(procedureName).getEverysite())
            {
                msg.setTxnId(newSpHandle);
                msg.setUniqueId(uniqueId);
            }

            // The leader will be responsible to replicate messages to replicas.
            // Don't replicate reads, not matter FAST or SAFE.
            if (m_isLeader && (!msg.isReadOnly()) && (m_sendToHSIds.length > 0)) {
                for (long hsId : m_sendToHSIds) {
                    Iv2InitiateTaskMessage finalMsg = msg;
                    final VoltTrace.TraceEventBatch traceLog = VoltTrace.log(VoltTrace.Category.SPI);
                    if (traceLog != null) {
                        traceLog.add(() -> VoltTrace.beginAsync("replicateSP",
                                                                MiscUtils.hsIdPairTxnIdToString(m_mailbox.getHSId(), hsId, finalMsg.getSpHandle(), finalMsg.getClientInterfaceHandle()),
                                                                "txnId", TxnEgo.txnIdToString(finalMsg.getTxnId()),
                                                                "dest", CoreUtils.hsIdToString(hsId)));
                    }
                }
                Iv2InitiateTaskMessage replmsg =
                    new Iv2InitiateTaskMessage(m_mailbox.getHSId(),
                            m_mailbox.getHSId(),
                            getRepairLogTruncationHandleForReplicas(),
                            msg.getTxnId(),
                            msg.getUniqueId(),
                            msg.isReadOnly(),
                            msg.isSinglePartition(),
                            msg.getStoredProcedureInvocation(),
                            msg.getClientInterfaceHandle(),
                            msg.getConnectionId(),
                            msg.isForReplay(),
                            true);
                // Update the handle in the copy since the constructor doesn't set it
                replmsg.setSpHandle(newSpHandle);
                m_mailbox.send(m_sendToHSIds, replmsg);

                DuplicateCounter counter = new DuplicateCounter(
                        msg.getInitiatorHSId(),
                        msg.getTxnId(),
                        m_replicaHSIds,
                        msg);

                safeAddToDuplicateCounterMap(new DuplicateCounterKey(msg.getTxnId(), newSpHandle), counter);
            }
        }
        else {
            setMaxSeenTxnId(msg.getSpHandle());
            newSpHandle = msg.getSpHandle();
            logRepair(msg);
            // Don't update the uniqueID if this is a run-everywhere txn, because it has an MPI unique ID.
            if (UniqueIdGenerator.getPartitionIdFromUniqueId(msg.getUniqueId()) == m_partitionId) {
                m_uniqueIdGenerator.updateMostRecentlyGeneratedUniqueId(msg.getUniqueId());
            }
        }
        Iv2Trace.logIv2InitiateTaskMessage(message, m_mailbox.getHSId(), msg.getTxnId(), newSpHandle);
        doLocalInitiateOffer(msg);
    }

    /**
     * Do the work necessary to turn the Iv2InitiateTaskMessage into a
     * TransactionTask which can be queued to the TransactionTaskQueue.
     * This is reused by both the normal message handling path and the repair
     * path, and assumes that the caller has dealt with or ensured that the
     * necessary ID, SpHandles, and replication issues are resolved.
     */
    private void doLocalInitiateOffer(Iv2InitiateTaskMessage msg)
    {
        final String threadName = Thread.currentThread().getName(); // Thread name has to be materialized here
        final VoltTrace.TraceEventBatch traceLog = VoltTrace.log(VoltTrace.Category.SPI);
        if (traceLog != null) {
            traceLog.add(() -> VoltTrace.meta("process_name", "name", CoreUtils.getHostnameOrAddress()))
                    .add(() -> VoltTrace.meta("thread_name", "name", threadName))
                    .add(() -> VoltTrace.meta("thread_sort_index", "sort_index", Integer.toString(10000)))
                    .add(() -> VoltTrace.beginAsync("initsp",
                                                    MiscUtils.hsIdPairTxnIdToString(m_mailbox.getHSId(), m_mailbox.getHSId(), msg.getSpHandle(), msg.getClientInterfaceHandle()),
                                                    "ciHandle", msg.getClientInterfaceHandle(),
                                                    "txnId", TxnEgo.txnIdToString(msg.getTxnId()),
                                                    "partition", m_partitionId,
                                                    "read", msg.isReadOnly(),
                                                    "name", msg.getStoredProcedureName(),
                                                    "hsId", CoreUtils.hsIdToString(m_mailbox.getHSId())));
        }

        /**
         * A shortcut read is a read operation sent to any replica and completed with no
         * confirmation or communication with other replicas. In a partition scenario, it's
         * possible to read an unconfirmed transaction's writes that will be lost.
         */
        final boolean shortcutRead = msg.isReadOnly() && (m_defaultConsistencyReadLevel == ReadLevel.FAST);
        final String procedureName = msg.getStoredProcedureName();
        final SpProcedureTask task =
            new SpProcedureTask(m_mailbox, procedureName, m_pendingTasks, msg);
        if (!shortcutRead) {
            ListenableFuture<Object> durabilityBackpressureFuture =
                    m_cl.log(msg, msg.getSpHandle(), null, m_durabilityListener, task);

            if (traceLog != null && durabilityBackpressureFuture != null) {
                traceLog.add(() -> VoltTrace.beginAsync("durability",
                                                        MiscUtils.hsIdTxnIdToString(m_mailbox.getHSId(), msg.getSpHandle()),
                                                        "txnId", TxnEgo.txnIdToString(msg.getTxnId()),
                                                        "partition", Integer.toString(m_partitionId)));
            }

            //Durability future is always null for sync command logging
            //the transaction will be delivered again by the CL for execution once durable
            //Async command logging has to offer the task immediately with a Future for backpressure
            if (m_cl.canOfferTask()) {
                m_pendingTasks.offer(task.setDurabilityBackpressureFuture(durabilityBackpressureFuture));
            }
        } else {
            m_pendingTasks.offer(task);
        }
    }

    @Override
    public void handleMessageRepair(List<Long> needsRepair, VoltMessage message)
    {
        if (message instanceof Iv2InitiateTaskMessage) {
            handleIv2InitiateTaskMessageRepair(needsRepair, (Iv2InitiateTaskMessage)message);
        }
        else if (message instanceof FragmentTaskMessage) {
            handleFragmentTaskMessageRepair(needsRepair, (FragmentTaskMessage)message);
        }
        else if (message instanceof CompleteTransactionMessage) {
            // It should be safe to just send CompleteTransactionMessages to everyone.
            //if it gets here, the message is for the leader to repair from MpScheduler
            ((CompleteTransactionMessage) message).setToLeader(true);
            handleCompleteTransactionMessage((CompleteTransactionMessage)message);
        }
        else {
            throw new RuntimeException("SpScheduler.handleMessageRepair received unexpected message type: " +
                    message);
        }
    }

    private void handleIv2InitiateTaskMessageRepair(List<Long> needsRepair, Iv2InitiateTaskMessage message)
    {
        if (!message.isSinglePartition()) {
            throw new RuntimeException("SpScheduler.handleIv2InitiateTaskMessageRepair " +
                    "should never receive multi-partition initiations.");
        }

        // set up duplicate counter. expect exactly the responses corresponding
        // to needsRepair. These may, or may not, include the local site.

        // We currently send the final response into the ether, since we don't
        // have the original ClientInterface HSID stored.  It would be more
        // useful to have the original ClienInterface HSId somewhere handy.

        List<Long> expectedHSIds = new ArrayList<Long>(needsRepair);
        DuplicateCounter counter = new DuplicateCounter(
                HostMessenger.VALHALLA,
                message.getTxnId(),
                expectedHSIds,
                message);
        safeAddToDuplicateCounterMap(new DuplicateCounterKey(message.getTxnId(), message.getSpHandle()), counter);

        m_uniqueIdGenerator.updateMostRecentlyGeneratedUniqueId(message.getUniqueId());
        // is local repair necessary?
        if (needsRepair.contains(m_mailbox.getHSId())) {
            needsRepair.remove(m_mailbox.getHSId());
            // make a copy because handleIv2 non-repair case does?
            Iv2InitiateTaskMessage localWork =
                new Iv2InitiateTaskMessage(message.getInitiatorHSId(),
                    message.getCoordinatorHSId(), message);
            doLocalInitiateOffer(localWork);
        }

        // is remote repair necessary?
        if (!needsRepair.isEmpty()) {
            //to replica for repair
            Iv2InitiateTaskMessage replmsg =
                new Iv2InitiateTaskMessage(m_mailbox.getHSId(), m_mailbox.getHSId(), message, true);
            m_mailbox.send(com.google_voltpatches.common.primitives.Longs.toArray(needsRepair), replmsg);
        }
    }

    private void handleFragmentTaskMessageRepair(List<Long> needsRepair, FragmentTaskMessage message)
    {
        // set up duplicate counter. expect exactly the responses corresponding
        // to needsRepair. These may, or may not, include the local site.

        List<Long> expectedHSIds = new ArrayList<Long>(needsRepair);
        DuplicateCounter counter = new DuplicateCounter(
                message.getCoordinatorHSId(), // Assume that the MPI's HSID hasn't changed
                message.getTxnId(),
                expectedHSIds,
                message);
        safeAddToDuplicateCounterMap(new DuplicateCounterKey(message.getTxnId(), message.getSpHandle()), counter);

        // is local repair necessary?
        if (needsRepair.contains(m_mailbox.getHSId())) {
            // Sanity check that we really need repair.
            if (m_outstandingTxns.get(message.getTxnId()) != null) {
                hostLog.warn("SPI repair attempted to repair a fragment which it has already seen. " +
                        "This shouldn't be possible.");
                // Not sure what to do in this event.  Crash for now
                throw new RuntimeException("Attempted to repair with a fragment we've already seen.");
            }
            needsRepair.remove(m_mailbox.getHSId());
            // make a copy because handleIv2 non-repair case does?
            FragmentTaskMessage localWork =
                new FragmentTaskMessage(message.getInitiatorHSId(),
                    message.getCoordinatorHSId(), message);
            doLocalFragmentOffer(localWork);
        }

        // is remote repair necessary?
        if (!needsRepair.isEmpty()) {
            FragmentTaskMessage replmsg =
                new FragmentTaskMessage(m_mailbox.getHSId(), m_mailbox.getHSId(), message);
            replmsg.setToReplica(true);
            if (tmLog.isDebugEnabled()) {
                tmLog.debug("[SpScheduler.handleFragmentTaskMessageRepair] sending repairing msg to " + CoreUtils.hsIdCollectionToString(needsRepair) +
                        " from " + CoreUtils.hsIdToString(m_mailbox.getHSId()) + " " + replmsg);
            }
            m_mailbox.send(com.google_voltpatches.common.primitives.Longs.toArray(needsRepair), replmsg);
        }
    }

    // Pass a response through the duplicate counters.
    private void handleInitiateResponseMessage(InitiateResponseMessage message)
    {
        /**
         * A shortcut read is a read operation sent to any replica and completed with no
         * confirmation or communication with other replicas. In a partition scenario, it's
         * possible to read an unconfirmed transaction's writes that will be lost.
         */
        final long spHandle = message.getSpHandle();
        final DuplicateCounterKey dcKey = new DuplicateCounterKey(message.getTxnId(), spHandle);
        DuplicateCounter counter = m_duplicateCounters.get(dcKey);
        final VoltTrace.TraceEventBatch traceLog = VoltTrace.log(VoltTrace.Category.SPI);

        // All reads will have no duplicate counter.
        // Avoid all the lookup below.
        // Also, don't update the truncation handle, since it won't have meaning for anyone.
        if (message.isReadOnly()) {
            if (traceLog != null) {
                traceLog.add(() -> VoltTrace.endAsync("initsp", MiscUtils.hsIdPairTxnIdToString(m_mailbox.getHSId(), message.m_sourceHSId, message.getSpHandle(), message.getClientInterfaceHandle())));
            }

            if (m_defaultConsistencyReadLevel == ReadLevel.FAST || !m_isLeader) {
                // the initiatorHSId is the ClientInterface mailbox.
                m_mailbox.send(message.getInitiatorHSId(), message);
                return;
            }

            if (m_defaultConsistencyReadLevel == ReadLevel.SAFE) {
                // InvocationDispatcher routes SAFE reads to SPI only
                assert(m_isLeader);
                assert(m_bufferedReadLog != null);
                m_bufferedReadLog.offer(m_mailbox, message, m_repairLogTruncationHandle);
                return;
            }
        }

        if (counter != null) {
            String traceName = "initsp";
            if (message.m_sourceHSId != m_mailbox.getHSId()) {
                traceName = "replicatesp";
            }
            String finalTraceName = traceName;
            if (traceLog != null) {
                traceLog.add(() -> VoltTrace.endAsync(finalTraceName, MiscUtils.hsIdPairTxnIdToString(m_mailbox.getHSId(), message.m_sourceHSId, message.getSpHandle(), message.getClientInterfaceHandle()),
                                                      "hash", message.getClientResponseData().getHashes()[0]));
            }

            int result = counter.offer(message);
            if (result == DuplicateCounter.DONE) {
                m_duplicateCounters.remove(dcKey);
                setRepairLogTruncationHandle(spHandle);
                m_mailbox.send(counter.m_destinationId, counter.getLastResponse());
            }
            else if (result == DuplicateCounter.MISMATCH) {
                if (m_isLeader && m_sendToHSIds.length > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (long hsId : m_sendToHSIds) {
                        sb.append(CoreUtils.getHostIdFromHSId(hsId) + ":" + CoreUtils.getSiteIdFromHSId(hsId)).append(" ");
                    }
                    hostLog.info("Send dump plan message to other replicas: " + sb.toString());
                    m_mailbox.send(m_sendToHSIds, new DumpPlanThenExitMessage(counter.getStoredProcedureName()));
                }
                RealVoltDB.printDiagnosticInformation(VoltDB.instance().getCatalogContext(),
                        counter.getStoredProcedureName(), m_procSet);
                VoltDB.crashLocalVoltDB("HASH MISMATCH: replicas produced different results.", true, null);
            } else if (result == DuplicateCounter.ABORT) {
                if (m_isLeader && m_sendToHSIds.length > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (long hsId : m_sendToHSIds) {
                        sb.append(CoreUtils.getHostIdFromHSId(hsId) + ":" + CoreUtils.getSiteIdFromHSId(hsId)).append(" ");
                    }
                    hostLog.info("Send dump plan message to other replicas: " + sb.toString());
                    m_mailbox.send(m_sendToHSIds, new DumpPlanThenExitMessage(counter.getStoredProcedureName()));
                }
                RealVoltDB.printDiagnosticInformation(VoltDB.instance().getCatalogContext(),
                        counter.getStoredProcedureName(), m_procSet);
                VoltDB.crashLocalVoltDB("HASH MISMATCH: transaction succeeded on one replica but failed on another replica.", true, null);
            }
        }
        else {
            if (traceLog != null) {
                traceLog.add(() -> VoltTrace.endAsync("initsp", MiscUtils.hsIdPairTxnIdToString(m_mailbox.getHSId(), message.m_sourceHSId, message.getSpHandle(), message.getClientInterfaceHandle())));
            }
            // the initiatorHSId is the ClientInterface mailbox.
            // this will be on SPI without k-safety or replica only with k-safety
            assert(!message.isReadOnly());
            setRepairLogTruncationHandle(spHandle);
            m_mailbox.send(message.getInitiatorHSId(), message);
        }
    }

    // BorrowTaskMessages encapsulate a FragmentTaskMessage along with
    // input dependency tables. The MPI issues borrows to a local site
    // to perform replicated reads or aggregation fragment work.
    private void handleBorrowTaskMessage(BorrowTaskMessage message) {
        // borrows do not advance the sp handle. The handle would
        // move backwards anyway once the next message is received
        // from the SP leader.
        long newSpHandle = getMaxScheduledTxnSpHandle();
        Iv2Trace.logFragmentTaskMessage(message.getFragmentTaskMessage(),
                m_mailbox.getHSId(), newSpHandle, true);
        final VoltTrace.TraceEventBatch traceLog = VoltTrace.log(VoltTrace.Category.SPI);
        if (traceLog != null) {
            traceLog.add(() -> VoltTrace.beginAsync("recvfragment",
                                                    MiscUtils.hsIdPairTxnIdToString(m_mailbox.getHSId(), m_mailbox.getHSId(), newSpHandle, 0),
                                                    "txnId", TxnEgo.txnIdToString(message.getTxnId()),
                                                    "partition", m_partitionId,
                                                    "hsId", CoreUtils.hsIdToString(m_mailbox.getHSId())));
        }

        TransactionState txn = m_outstandingTxns.get(message.getTxnId());

        if (txn == null) {
            // If the borrow is the first fragment for a transaction, run it as
            // a single partition fragment; Must not  engage/pause this
            // site on a MP transaction before the SP instructs to do so.
            // Do not track the borrow task as outstanding - it completes
            // immediately and is not a valid transaction state for
            // full MP participation (it claims everything can run as SP).
            txn = new BorrowTransactionState(newSpHandle, message);
        }

        // BorrowTask is a read only task embedded in a MP transaction
        // and its response (FragmentResponseMessage) should not be buffered
        if (message.getFragmentTaskMessage().isSysProcTask()) {
            final SysprocFragmentTask task =
                new SysprocFragmentTask(m_mailbox, (ParticipantTransactionState)txn,
                                        m_pendingTasks, message.getFragmentTaskMessage(),
                                        message.getInputDepMap());
            task.setResponseNotBufferable();
            m_pendingTasks.offer(task);
        }
        else {
            final FragmentTask task =
                new FragmentTask(m_mailbox, (ParticipantTransactionState)txn,
                        m_pendingTasks, message.getFragmentTaskMessage(),
                        message.getInputDepMap());
            task.setResponseNotBufferable();
            m_pendingTasks.offer(task);
        }
    }

    // SpSchedulers will see FragmentTaskMessage for:
    // - The scatter fragment(s) of a multi-part transaction (normal or sysproc)
    // - Borrow tasks to do the local fragment work if this partition is the
    //   buddy of the MPI.  Borrow tasks may include input dependency tables for
    //   aggregation fragments, or not, if it's a replicated table read.
    // For multi-batch MP transactions, we'll need to look up the transaction state
    // that gets created when the first batch arrives.
    // During command log replay a new SP handle is going to be generated, but it really
    // doesn't matter, it isn't going to be used for anything.
    void handleFragmentTaskMessage(FragmentTaskMessage message)
    {
        FragmentTaskMessage msg = message;
        long newSpHandle;
        //The site has been marked as non-leader. The follow-up batches or fragments are processed here
        if (!message.toReplica() && (m_isLeader || (!m_isLeader && message.shouldHandleByOriginalLeader()))) {
            // Quick hack to make progress...we need to copy the FragmentTaskMessage
            // before we start mucking with its state (SPHANDLE).  We need to revisit
            // all the messaging mess at some point.
            msg = new FragmentTaskMessage(message.getInitiatorHSId(),
                    message.getCoordinatorHSId(), message);
            //Not going to use the timestamp from the new Ego because the multi-part timestamp is what should be used
            msg.setHandleByOriginalLeader(message.shouldHandleByOriginalLeader());
            if (!message.isReadOnly()) {
                TxnEgo ego = advanceTxnEgo();
                newSpHandle = ego.getTxnId();

                if (m_outstandingTxns.get(msg.getTxnId()) == null) {
                    updateMaxScheduledTransactionSpHandle(newSpHandle);
                }
            } else {
                newSpHandle = getMaxScheduledTxnSpHandle();
            }

            msg.setSpHandle(newSpHandle);
            logRepair(msg);
            if (msg.getInitiateTask() != null) {
                msg.getInitiateTask().setSpHandle(newSpHandle);//set the handle
                //Trigger reserialization so the new handle is used
                msg.setStateForDurability(msg.getInitiateTask(), msg.getInvolvedPartitions());
            }

            /*
             * If there a replicas to send it to, forward it!
             * Unless... it's read only AND not a sysproc. Read only sysprocs may expect to be sent
             * everywhere.
             * In that case don't propagate it to avoid a determinism check and extra messaging overhead
             */
            if (m_sendToHSIds.length > 0 && (!message.isReadOnly() || msg.isSysProcTask())) {
                for (long hsId : m_sendToHSIds) {
                    FragmentTaskMessage finalMsg = msg;
                    final VoltTrace.TraceEventBatch traceLog = VoltTrace.log(VoltTrace.Category.SPI);
                    if (traceLog != null) {
                        traceLog.add(() -> VoltTrace.beginAsync("replicatefragment",
                                                                MiscUtils.hsIdPairTxnIdToString(m_mailbox.getHSId(), hsId, finalMsg.getSpHandle(), finalMsg.getTxnId()),
                                                                "txnId", TxnEgo.txnIdToString(finalMsg.getTxnId()),
                                                                "dest", CoreUtils.hsIdToString(hsId)));
                    }
                }

                FragmentTaskMessage replmsg =
                    new FragmentTaskMessage(m_mailbox.getHSId(),
                            m_mailbox.getHSId(), msg);
                replmsg.setToReplica(true);
                m_mailbox.send(m_sendToHSIds,replmsg);
                if (tmLog.isDebugEnabled()) {
                    List<Long> hsids = new ArrayList<Long>(Arrays.hashCode(m_sendToHSIds));
                    tmLog.debug("[handleFragmentTaskMessage] " + CoreUtils.hsIdToString(m_mailbox.getHSId()) + " sends to " +
                            CoreUtils.hsIdCollectionToString(hsids) + "\n" + replmsg);
                }
                DuplicateCounter counter;
                /*
                 * Non-determinism should be impossible to happen with MP fragments.
                 * if you see "MP_DETERMINISM_ERROR" as procedure name in the crash logs
                 * something has horribly gone wrong.
                 */
                if (message.getFragmentTaskType() != FragmentTaskMessage.SYS_PROC_PER_SITE) {
                    counter = new DuplicateCounter(
                            msg.getCoordinatorHSId(),
                            msg.getTxnId(),
                            m_replicaHSIds,
                            message);
                }
                else {
                    counter = new SysProcDuplicateCounter(
                            msg.getCoordinatorHSId(),
                            msg.getTxnId(),
                            m_replicaHSIds,
                            message);
                }
                safeAddToDuplicateCounterMap(new DuplicateCounterKey(message.getTxnId(), newSpHandle), counter);
            }
        } else {
            logRepair(msg);
            newSpHandle = msg.getSpHandle();
            setMaxSeenTxnId(newSpHandle);
        }
        Iv2Trace.logFragmentTaskMessage(message, m_mailbox.getHSId(), newSpHandle, false);
        doLocalFragmentOffer(msg);
    }

    /**
     * Do the work necessary to turn the FragmentTaskMessage into a
     * TransactionTask which can be queued to the TransactionTaskQueue.
     * This is reused by both the normal message handling path and the repair
     * path, and assumes that the caller has dealt with or ensured that the
     * necessary ID, SpHandles, and replication issues are resolved.
     */
    private void doLocalFragmentOffer(FragmentTaskMessage msg)
    {
        final String threadName = Thread.currentThread().getName(); // Thread name has to be materialized here
        final VoltTrace.TraceEventBatch traceLog = VoltTrace.log(VoltTrace.Category.SPI);
        if (traceLog != null) {
            traceLog.add(() -> VoltTrace.meta("process_name", "name", CoreUtils.getHostnameOrAddress()))
                    .add(() -> VoltTrace.meta("thread_name", "name", threadName))
                    .add(() -> VoltTrace.meta("thread_sort_index", "sort_index", Integer.toString(10000)))
                    .add(() -> VoltTrace.beginAsync("recvfragment",
                                                    MiscUtils.hsIdPairTxnIdToString(m_mailbox.getHSId(), m_mailbox.getHSId(), msg.getSpHandle(), msg.getTxnId()),
                                                    "txnId", TxnEgo.txnIdToString(msg.getTxnId()),
                                                    "partition", m_partitionId,
                                                    "hsId", CoreUtils.hsIdToString(m_mailbox.getHSId()),
                                                    "final", msg.isFinalTask()));
        }

        TransactionState txn = m_outstandingTxns.get(msg.getTxnId());
        boolean logThis = false;
        // bit of a hack...we will probably not want to create and
        // offer FragmentTasks for txn ids that don't match if we have
        // something in progress already
        if (txn == null) {
            txn = new ParticipantTransactionState(msg.getSpHandle(), msg, msg.isReadOnly());
            m_outstandingTxns.put(msg.getTxnId(), txn);
            // Only want to send things to the command log if it satisfies this predicate
            // AND we've never seen anything for this transaction before.  We can't
            // actually log until we create a TransactionTask, though, so just keep track
            // of whether it needs to be done.

            // Like SP, we should log writes and safe reads.
            // Fast reads can be directly put on the task queue.
            boolean shortcutRead = msg.isReadOnly() && (m_defaultConsistencyReadLevel == ReadLevel.FAST);
            logThis = !shortcutRead;
        }

        // Check to see if this is the final task for this txn, and if so, if we can close it out early
        // Right now, this just means read-only.
        // NOTE: this overlaps slightly with CompleteTransactionMessage handling completion.  It's so tiny
        // that for now, meh, but if this scope grows then it should get refactored out
        if (msg.isFinalTask() && txn.isReadOnly()) {
            m_outstandingTxns.remove(msg.getTxnId());
        }

        TransactionTask task;
        if (msg.isSysProcTask()) {
            task =
                new SysprocFragmentTask(m_mailbox, (ParticipantTransactionState)txn,
                                        m_pendingTasks, msg, null);
        }
        else {
            task =
                new FragmentTask(m_mailbox, (ParticipantTransactionState)txn,
                                 m_pendingTasks, msg, null);
        }
        if (logThis) {
            ListenableFuture<Object> durabilityBackpressureFuture =
                    m_cl.log(msg.getInitiateTask(), msg.getSpHandle(), Ints.toArray(msg.getInvolvedPartitions()),
                             m_durabilityListener, task);

            if (traceLog != null && durabilityBackpressureFuture != null) {
                traceLog.add(() -> VoltTrace.beginAsync("durability",
                                                        MiscUtils.hsIdTxnIdToString(m_mailbox.getHSId(), msg.getSpHandle()),
                                                        "txnId", TxnEgo.txnIdToString(msg.getTxnId()),
                                                        "partition", Integer.toString(m_partitionId)));
            }

            //Durability future is always null for sync command logging
            //the transaction will be delivered again by the CL for execution once durable
            //Async command logging has to offer the task immediately with a Future for backpressure
            if (m_cl.canOfferTask()) {
                m_pendingTasks.offer(task.setDurabilityBackpressureFuture(durabilityBackpressureFuture));
            } else {
                /* Getting here means that the task is the first fragment of an MP txn and
                 * synchronous command logging is on, so create a backlog for future tasks of
                 * this MP arrived before it's marked durable.
                 *
                 * This is important for synchronous command logging and MP txn restart. Without
                 * this, a restarted MP txn may not be gated by logging of the first fragment.
                 */
                assert !m_mpsPendingDurability.containsKey(task.getTxnId());
                m_mpsPendingDurability.put(task.getTxnId(), new ArrayDeque<TransactionTask>());
            }
        } else {
            queueOrOfferMPTask(task);
        }
    }

    /**
     * Offer all fragment tasks and complete transaction tasks queued for durability for the given
     * MP transaction, and remove the entry from the pending map so that future ones won't be
     * queued.
     *
     * @param txnId    The MP transaction ID.
     */
    public void offerPendingMPTasks(long txnId)
    {
        Queue<TransactionTask> pendingTasks = m_mpsPendingDurability.get(txnId);
        if (pendingTasks != null) {
            for (TransactionTask task : pendingTasks) {
                if (task instanceof SpProcedureTask) {
                    final VoltTrace.TraceEventBatch traceLog = VoltTrace.log(VoltTrace.Category.SPI);
                    if (traceLog != null) {
                        traceLog.add(() -> VoltTrace.endAsync("durability",
                                                              MiscUtils.hsIdTxnIdToString(m_mailbox.getHSId(), task.getSpHandle())));
                    }
                } else if (task instanceof FragmentTask) {
                    final VoltTrace.TraceEventBatch traceLog = VoltTrace.log(VoltTrace.Category.SPI);
                    if (traceLog != null) {
                        traceLog.add(() -> VoltTrace.endAsync("durability",
                                                              MiscUtils.hsIdTxnIdToString(m_mailbox.getHSId(), ((FragmentTask) task).m_fragmentMsg.getSpHandle())));
                    }
                }

                m_pendingTasks.offer(task);
            }
            m_mpsPendingDurability.remove(txnId);
        }
    }

    /**
     * Check if the MP task has to be queued because the first fragment is still being logged
     * synchronously to the command log. If not, offer it to the transaction task queue.
     *
     * @param task    A fragment task or a complete transaction task
     */
    private void queueOrOfferMPTask(TransactionTask task)
    {
        // The pending map will only have an entry for the transaction if the first fragment is
        // still pending durability.
        Queue<TransactionTask> pendingTasks = m_mpsPendingDurability.get(task.getTxnId());
        if (pendingTasks != null) {
            pendingTasks.offer(task);
        } else {
            m_pendingTasks.offer(task);
        }
    }

    private boolean isFragmentMisrouted(FragmentResponseMessage message) {
        SerializableException ex = message.getException();
        if (ex != null && ex instanceof TransactionRestartException) {
            return (((TransactionRestartException)ex).isMisrouted());
        }
        return false;
    }

    // Eventually, the master for a partition set will need to be able to dedupe
    // FragmentResponses from its replicas.
    private void handleFragmentResponseMessage(FragmentResponseMessage message)
    {
        if (isFragmentMisrouted(message)){
            m_mailbox.send(message.getDestinationSiteId(), message);
            return;
        }
        final VoltTrace.TraceEventBatch traceLog = VoltTrace.log(VoltTrace.Category.SPI);

        // Send the message to the duplicate counter, if any
        DuplicateCounter counter =
            m_duplicateCounters.get(new DuplicateCounterKey(message.getTxnId(), message.getSpHandle()));
        final TransactionState txn = m_outstandingTxns.get(message.getTxnId());
        if (counter != null) {
            String traceName = "recvfragment";
            if (message.m_sourceHSId != m_mailbox.getHSId()) {
                traceName = "replicatefragment";
            }
            String finalTraceName = traceName;
            if (traceLog != null) {
                traceLog.add(() -> VoltTrace.endAsync(finalTraceName, MiscUtils.hsIdPairTxnIdToString(m_mailbox.getHSId(), message.m_sourceHSId, message.getSpHandle(), message.getTxnId()),
                                                      "status", message.getStatusCode()));
            }

            int result = counter.offer(message);
            if (result == DuplicateCounter.DONE) {
                if (txn != null && txn.isDone()) {
                    setRepairLogTruncationHandle(txn.m_spHandle, message.shouldHandleByOriginalLeader());
                }

                m_duplicateCounters.remove(new DuplicateCounterKey(message.getTxnId(), message.getSpHandle()));
                FragmentResponseMessage resp = (FragmentResponseMessage)counter.getLastResponse();
                if (tmLog.isDebugEnabled()) {
                    tmLog.debug("[handleFragmentResponseMessage]: remove DC:" + message);
                }
                // MPI is tracking deps per partition HSID.  We need to make
                // sure we write ours into the message getting sent to the MPI
                resp.setExecutorSiteId(m_mailbox.getHSId());
                m_mailbox.send(counter.m_destinationId, resp);
            }
            else if (result == DuplicateCounter.MISMATCH) {
                VoltDB.crashGlobalVoltDB("HASH MISMATCH running multi-part procedure.", true, null);
            } else if (result == DuplicateCounter.ABORT) {
                VoltDB.crashGlobalVoltDB("PARTIAL ROLLBACK/ABORT running multi-part procedure.", true, null);
            }
            // doing duplicate suppression: all done.
            return;
        }

        // No k-safety means no replica: read/write queries on master.
        // K-safety: read-only queries (on master) or write queries (on replica).
        if (m_defaultConsistencyReadLevel == ReadLevel.SAFE && m_isLeader && m_sendToHSIds.length > 0
                && message.getRespBufferable()
                && (txn == null || txn.isReadOnly()) ) {
            // on k-safety leader with safe reads configuration: one shot reads + normal multi-fragments MP reads
            // we will have to buffer these reads until previous writes acked in the cluster.
            long readTxnId = txn == null ? message.getSpHandle() : txn.m_spHandle;
            m_bufferedReadLog.offer(m_mailbox, message, readTxnId, m_repairLogTruncationHandle);
            return;
        }

        // for complete writes txn, we will advance the transaction point
        if (txn != null && !txn.isReadOnly() && txn.isDone()) {
            setRepairLogTruncationHandle(txn.m_spHandle);
        }

        if (traceLog != null) {
            traceLog.add(() -> VoltTrace.endAsync("recvfragment", MiscUtils.hsIdPairTxnIdToString(m_mailbox.getHSId(), message.m_sourceHSId, message.getSpHandle(), message.getTxnId()),
                                                  "status", message.getStatusCode()));
        }

        m_mailbox.send(message.getDestinationSiteId(), message);
    }

    private void handleCompleteTransactionMessage(CompleteTransactionMessage message)
    {
        CompleteTransactionMessage msg = message;
        TransactionState txn = m_outstandingTxns.get(msg.getTxnId());

        // 1) The site is not a leader any more, thanks to spi migration but the message is intended for leader.
        //    action: advance TxnEgo, send it to all original replicas (before spi migration)
        // 2) The site is the new leader but the message is intended for replica
        //    action: no TxnEgo advance
        if ((m_isLeader && message.isToLeader())|| message.isToLeader()) {
            msg = new CompleteTransactionMessage(m_mailbox.getHSId(), m_mailbox.getHSId(), message);
            // Set the spHandle so that on repair the new master will set the max seen spHandle
            // correctly
            advanceTxnEgo();
            msg.setSpHandle(getCurrentTxnId());
            logRepair(msg);
            msg.setToLeader(false);
            msg.setAckRequestedFromSender(true);
            if (m_sendToHSIds.length > 0 && !msg.isReadOnly()) {
                m_mailbox.send(m_sendToHSIds, msg);
            }
        } else {
            if(!m_isLeader) {
                setMaxSeenTxnId(msg.getSpHandle());
                if (txn != null) {
                    logRepair(msg);
                }
            }
        }
        // We can currently receive CompleteTransactionMessages for multipart procedures
        // which only use the buddy site (replicated table read).  Ignore them for
        // now, fix that later.
        if (txn != null)
        {
            CompleteTransactionMessage finalMsg = msg;
            final VoltTrace.TraceEventBatch traceLog = VoltTrace.log(VoltTrace.Category.SPI);
            if (traceLog != null) {
                traceLog.add(() -> VoltTrace.instant("recvCompleteTxn",
                                                     "txnId", TxnEgo.txnIdToString(finalMsg.getTxnId()),
                                                     "partition", Integer.toString(m_partitionId),
                                                     "hsId", CoreUtils.hsIdToString(m_mailbox.getHSId())));
            }

            final boolean isSysproc = ((FragmentTaskMessage) txn.getNotice()).isSysProcTask();
            if (m_sendToHSIds.length > 0 && !msg.isRestart() && (!msg.isReadOnly() || isSysproc) && message.isToLeader()) {

                DuplicateCounter counter;
                counter = new DuplicateCounter(msg.getCoordinatorHSId(),
                                               msg.getTxnId(),
                                               m_replicaHSIds,
                                               msg);
                safeAddToDuplicateCounterMap(new DuplicateCounterKey(msg.getTxnId(), msg.getSpHandle()), counter);
            }

            Iv2Trace.logCompleteTransactionMessage(msg, m_mailbox.getHSId());
            final CompleteTransactionTask task =
                new CompleteTransactionTask(m_mailbox, txn, m_pendingTasks, msg);
            queueOrOfferMPTask(task);
        } else {
            // Generate a dummy response message when this site has not seen previous FragmentTaskMessage,
            // the leader may have started to wait for replicas' response messages.
            // This can happen in the early phase of site rejoin before replica receiving the snapshot initiation,
            // it also means this CompleteTransactionMessage message will be dropped because it's after snapshot.
            final CompleteTransactionResponseMessage resp = new CompleteTransactionResponseMessage(msg);
            resp.m_sourceHSId = m_mailbox.getHSId();
            handleCompleteTransactionResponseMessage(resp);
        }
    }

    private void handleCompleteTransactionResponseMessage(CompleteTransactionResponseMessage msg)
    {
        final DuplicateCounterKey duplicateCounterKey = new DuplicateCounterKey(msg.getTxnId(), msg.getSpHandle());
        DuplicateCounter counter = m_duplicateCounters.get(duplicateCounterKey);
        boolean txnDone = true;

        if (msg.isRestart()) {
            // Don't mark txn done for restarts
            txnDone = false;
        }

        if (counter != null) {
            txnDone = counter.offer(msg) == DuplicateCounter.DONE;
        }

        if (txnDone) {
            assert !msg.isRestart();
            final TransactionState txn = m_outstandingTxns.remove(msg.getTxnId());
            m_duplicateCounters.remove(duplicateCounterKey);

            if (txn != null) {
                // Set the truncation handle here instead of when processing
                // FragmentResponseMessage to avoid letting replicas think a
                // fragment is done before the MP txn is fully committed.
                assert txn.isDone() : "Counter " + counter + ", leader " + m_isLeader + ", " + msg;
                setRepairLogTruncationHandle(txn.m_spHandle);
            }
        }

        // The CompleteTransactionResponseMessage ends at the SPI. It is not
        // sent to the MPI because it doesn't care about it.
        //
        // The SPI uses this response message to track if all replicas have
        // committed the transaction.
        if (!m_isLeader && msg.isAckRequestedFromSender()) {
            m_mailbox.send(msg.getSPIHSId(), msg);
        }
    }

    /**
     * Should only receive these messages at replicas, when told by the leader
     */
    private void handleIv2LogFaultMessage(Iv2LogFaultMessage message)
    {
        //call the internal log write with the provided SP handle and wait for the fault log IO to complete
        SettableFuture<Boolean> written = writeIv2ViableReplayEntryInternal(message.getSpHandle());

        // Get the Fault Log Status here to ensure the replica completes the log fault task is finished before
        // it starts processing transactions again
        blockFaultLogWriteStatus(written);

        setMaxSeenTxnId(message.getSpHandle());

        // Also initialize the unique ID generator and the last durable unique ID using
        // the value sent by the master
        m_uniqueIdGenerator.updateMostRecentlyGeneratedUniqueId(message.getSpUniqueId());
        m_cl.initializeLastDurableUniqueId(m_durabilityListener, m_uniqueIdGenerator.getLastUniqueId());
    }

    /**
     * Wait to get the status of a fault log write
     */
    private void blockFaultLogWriteStatus(SettableFuture<Boolean> written) {
        boolean logWritten = false;

        if (written != null) {
            try {
                logWritten = written.get();
            } catch (InterruptedException e) {
            } catch (ExecutionException e) {
                if (tmLog.isDebugEnabled()) {
                    tmLog.debug("Could not determine fault log state for partition: " + m_partitionId, e);
                }
            }
            if (!logWritten) {
                tmLog.warn("Attempted fault log not written for partition: " + m_partitionId);
            }
        }
    }

    private void handleDumpMessage()
    {
        String who = CoreUtils.hsIdToString(m_mailbox.getHSId());
        hostLog.warn("State dump for site: " + who);
        hostLog.warn(who + ": partition: " + m_partitionId + ", isLeader: " + m_isLeader);
        if (m_isLeader) {
            hostLog.warn(who + ": replicas: " + CoreUtils.hsIdCollectionToString(m_replicaHSIds));
            if (m_sendToHSIds.length > 0) {
                m_mailbox.send(m_sendToHSIds, new DumpMessage());
            }
        }
        hostLog.warn(who + ": most recent SP handle: " + TxnEgo.txnIdToString(getCurrentTxnId()));
        hostLog.warn(who + ": outstanding txns: " + m_outstandingTxns.keySet() + " " +
                TxnEgo.txnIdCollectionToString(m_outstandingTxns.keySet()));
        hostLog.warn(who + ": TransactionTaskQueue: " + m_pendingTasks.toString());
        if (m_duplicateCounters.size() > 0) {
            hostLog.warn(who + ": duplicate counters: ");
            for (Entry<DuplicateCounterKey, DuplicateCounter> e : m_duplicateCounters.entrySet()) {
                hostLog.warn("\t" + who + ": " + e.getKey().toString() + ": " + e.getValue().toString());
            }
        }
    }

    private void handleDummyTransactionTaskMessage(DummyTransactionTaskMessage message)
    {
        DummyTransactionTaskMessage msg = message;
        if (m_isLeader) {
            TxnEgo ego = advanceTxnEgo();
            long newSpHandle = ego.getTxnId();
            updateMaxScheduledTransactionSpHandle(newSpHandle);
            // this uniqueId is needed as the command log tracks it (uniqueId has to advance)
            long uniqueId = m_uniqueIdGenerator.getNextUniqueId();
            msg = new DummyTransactionTaskMessage(m_mailbox.getHSId(), newSpHandle, uniqueId);

            if (m_sendToHSIds.length > 0) {
                m_mailbox.send(m_sendToHSIds, msg);

                DuplicateCounter counter = new DuplicateCounter(
                        HostMessenger.VALHALLA,
                        msg.getTxnId(),
                        m_replicaHSIds,
                        msg);
                safeAddToDuplicateCounterMap(new DuplicateCounterKey(msg.getTxnId(), newSpHandle), counter);
            }
        } else {
            setMaxSeenTxnId(msg.getSpHandle());
        }
        Iv2Trace.logDummyTransactionTaskMessage(msg, m_mailbox.getHSId());

        DummyTransactionTask task = new DummyTransactionTask(m_mailbox,
                new SpTransactionState(msg), m_pendingTasks);
        // This read only DummyTransactionTask is to help flushing the task queue,
        // including tasks in command log queue as well.
        ListenableFuture<Object> durabilityBackpressureFuture =
                m_cl.log(null, msg.getSpHandle(), null,  m_durabilityListener, task);
        // Durability future is always null for sync command logging
        // the transaction will be delivered again by the CL for execution once durable
        // Async command logging has to offer the task immediately with a Future for backpressure
        if (m_cl.canOfferTask()) {
            m_pendingTasks.offer(task.setDurabilityBackpressureFuture(durabilityBackpressureFuture));
        }
    }

    private void handleDummyTransactionResponseMessage(DummyTransactionResponseMessage message) {
        final long spHandle = message.getSpHandle();
        final DuplicateCounterKey dcKey = new DuplicateCounterKey(message.getTxnId(), spHandle);
        DuplicateCounter counter = m_duplicateCounters.get(dcKey);
        if (counter == null) {
            // this will be on SPI without k-safety or replica only with k-safety
            setRepairLogTruncationHandle(spHandle);
            if (!m_isLeader) {
                m_mailbox.send(message.getSPIHSId(), message);
            }
            return;
        }

        int result = counter.offer(message);
        if (result == DuplicateCounter.DONE) {
            // DummyTransactionResponseMessage ends on SPI
            m_duplicateCounters.remove(dcKey);
            setRepairLogTruncationHandle(spHandle);
        }
    }

    public void handleDumpPlanMessage(DumpPlanThenExitMessage msg)
    {
        hostLog.error("This node is going to shutdown because a hash mismatch error is detected on " +
                       CoreUtils.getHostIdFromHSId(msg.m_sourceHSId) + ":" + CoreUtils.getSiteIdFromHSId(msg.m_sourceHSId));
        RealVoltDB.printDiagnosticInformation(VoltDB.instance().getCatalogContext(),
                msg.getProcName(), m_procSet);
        VoltDB.crashLocalVoltDB("HASH MISMATCH", true, null);
    }

    @Override
    public void setCommandLog(CommandLog cl) {
        m_cl = cl;
        m_durabilityListener.createFirstCompletionCheck(cl.isSynchronous(), cl.isEnabled());
        m_cl.registerDurabilityListener(m_durabilityListener);
    }

    @Override
    public void enableWritingIv2FaultLog()
    {
        m_replayComplete = true;
        writeIv2ViableReplayEntry();
    }

    /**
     * If appropriate, cause the initiator to write the viable replay set to the command log
     * Use when it's unclear whether the caller is the leader or a replica; the right thing will happen.
     *
     * This will return a future to block on for the write on the fault log. If the attempt to write
     * the replay entry was never followed through due to conditions, it will be null. If the attempt
     * to write the replay entry went through but could not be done internally, the future will be false.
     */
    SettableFuture<Boolean> writeIv2ViableReplayEntry()
    {
        SettableFuture<Boolean> written = null;
        if (m_replayComplete) {
            if (m_isLeader) {
                // write the viable set locally
                long faultSpHandle = advanceTxnEgo().getTxnId();
                written = writeIv2ViableReplayEntryInternal(faultSpHandle);
                // Generate Iv2LogFault message and send it to replicas
                Iv2LogFaultMessage faultMsg = new Iv2LogFaultMessage(faultSpHandle, m_uniqueIdGenerator.getLastUniqueId());
                m_mailbox.send(m_sendToHSIds,
                        faultMsg);
            }
        }
        return written;
    }

    /**
     * Write the viable replay set to the command log with the provided SP Handle.
     * Pass back the future that is set after the fault log is written to disk.
     */
    SettableFuture<Boolean> writeIv2ViableReplayEntryInternal(long spHandle)
    {
        SettableFuture<Boolean> written = null;
        if (m_replayComplete) {
            written = m_cl.logIv2Fault(m_mailbox.getHSId(),
                new HashSet<Long>(m_replicaHSIds), m_partitionId, spHandle);
        }
        return written;
    }

    @Override
    public CountDownLatch snapshotCompleted(SnapshotCompletionEvent event)
    {
        if (event.truncationSnapshot && event.didSucceed) {
            synchronized(m_lock) {
                writeIv2ViableReplayEntry();
            }
        }
        return new CountDownLatch(0);
    }

    public void processDurabilityChecks(final CommandLog.CompletionChecks currentChecks) {
        final SiteTaskerRunnable r = new SiteTasker.SiteTaskerRunnable() {
            @Override
            void run() {
                assert(currentChecks != null);
                synchronized (m_lock) {
                    currentChecks.processChecks();
                }
            }
        };
        if (InitiatorMailbox.SCHEDULE_IN_SITE_THREAD) {
            m_tasks.offer(r);
        } else {
            r.run();
        }
    }

    /**
     * Just using "put" on the dup counter map is unsafe.
     * It won't detect the case where keys collide from two different transactions.
     */
    void safeAddToDuplicateCounterMap(DuplicateCounterKey dpKey, DuplicateCounter counter) {
        DuplicateCounter existingDC = m_duplicateCounters.get(dpKey);
        if (existingDC == null) {
            m_duplicateCounters.put(dpKey, counter);
            return;
        }
        if (tmLog.isDebugEnabled()) {
            tmLog.debug(String.format("Duplicate counters:\nMessage 1: %s\nMessage 2: %s\n",
                    existingDC.m_openMessage, counter.m_openMessage));
        }
        if (!skipCollisionFromBalanceSPI(existingDC, counter)) {
            existingDC.logWithCollidingDuplicateCounters(counter);
            VoltDB.crashGlobalVoltDB("DUPLICATE COUNTER MISMATCH: two duplicate counter keys collided.", true, null);
        }
    }

    //Both former and current partition leader may send CompleteTransactionMessage
    //over, which may introduce duplicate counter collision.
    boolean skipCollisionFromBalanceSPI(DuplicateCounter counter1, DuplicateCounter counter2) {
        if (!(counter1.m_openMessage instanceof CompleteTransactionMessage) ||
                !(counter2.m_openMessage instanceof CompleteTransactionMessage)) {
            return false;
        }

        CompleteTransactionMessage msg1 = (CompleteTransactionMessage)(counter1.m_openMessage);
        CompleteTransactionMessage msg2 = (CompleteTransactionMessage)(counter2.m_openMessage);
        return (msg1.getCoordinatorHSId() != msg2.getCoordinatorHSId());
    }

    @Override
    public void dump()
    {
        m_replaySequencer.dump(m_mailbox.getHSId());
        tmLog.info(String.format("%s: %s", CoreUtils.hsIdToString(m_mailbox.getHSId()), m_pendingTasks));

        if (m_defaultConsistencyReadLevel == ReadLevel.SAFE) {
            tmLog.info("[dump] current truncation handle: " + TxnEgo.txnIdToString(m_repairLogTruncationHandle) + " "
                + (m_defaultConsistencyReadLevel == Consistency.ReadLevel.SAFE ? m_bufferedReadLog.toString() : ""));
        }
    }

    // This is for test only
    public void setConsistentReadLevelForTestOnly(ReadLevel readLevel) {
        m_defaultConsistencyReadLevel = readLevel;
        if (m_defaultConsistencyReadLevel == ReadLevel.SAFE) {
            m_bufferedReadLog = new BufferedReadLog();
        }
    }

    private void updateMaxScheduledTransactionSpHandle(long newSpHandle) {
        m_maxScheduledTxnSpHandle = Math.max(m_maxScheduledTxnSpHandle, newSpHandle);
    }

    long getMaxScheduledTxnSpHandle() {
        return m_maxScheduledTxnSpHandle;
    }

    private long getRepairLogTruncationHandleForReplicas()
    {
        m_lastSentTruncationHandle = m_repairLogTruncationHandle;
        return m_repairLogTruncationHandle;
    }

    private void setRepairLogTruncationHandle(long newHandle, boolean forceAdvance)
    {
        if (newHandle > m_repairLogTruncationHandle) {
            m_repairLogTruncationHandle = newHandle;

            // We have to advance the local truncation point on the replica. It's important for
            // node promotion when there are no missing repair log transactions on the replica.
            // Because we still want to release the reads if no following writes will come to this replica.
            if (! m_isLeader && !forceAdvance) {
                return;
            }
            if (m_defaultConsistencyReadLevel == ReadLevel.SAFE) {
                m_bufferedReadLog.releaseBufferedReads(m_mailbox, m_repairLogTruncationHandle);
            }
            scheduleRepairLogTruncateMsg();
        } else {
            // As far as I know, they are cases that will move truncation handle backwards.
            // These include node failures (promotion phase) and node rejoin (early rejoin phase).
            if (tmLog.isDebugEnabled()) {
                tmLog.debug("Updating truncation point from " + TxnEgo.txnIdToString(m_repairLogTruncationHandle) +
                        "to" + TxnEgo.txnIdToString(newHandle));
            }
        }
    }

    private void setRepairLogTruncationHandle(long newHandle)
    {
        setRepairLogTruncationHandle(newHandle, false);
    }

    /**
     * Schedules a task to be run on the site to send the latest truncation
     * handle to the replicas. This should be called whenever the local
     * truncation handle advances on the leader to guarantee that the replicas
     * will hear about the new handle in case there is no more transactions to
     * carry the information over.
     *
     * The truncation handle is not sent immediately when this method is called
     * to avoid sending a message for every committed transaction. In most cases
     * when there is sufficient load on the system, there will always be a new
     * transaction that this information can piggy-back on. In that case, by the
     * time this task runs on the site, the last sent truncation handle has
     * already advanced, so there is no need to send the message. This has the
     * benefit of sending more truncation messages when the throughput is low,
     * which makes the replicas see committed transactions faster.
     */
    private void scheduleRepairLogTruncateMsg()
    {
        if (m_sendToHSIds.length == 0) {
            return;
        }

        m_tasks.offer(new SiteTaskerRunnable() {
            @Override
            void run()
            {
                synchronized (m_lock) {
                    if (m_lastSentTruncationHandle < m_repairLogTruncationHandle) {
                        m_lastSentTruncationHandle = m_repairLogTruncationHandle;
                        final RepairLogTruncationMessage truncMsg = new RepairLogTruncationMessage(m_repairLogTruncationHandle);
                        // Also keep the local repair log's truncation point up-to-date
                        // so that it can trigger the callbacks.
                        m_mailbox.deliver(truncMsg);
                        m_mailbox.send(m_sendToHSIds, truncMsg);
                    }
                }
            }
        });
    }

    public TransactionState getTransaction(long txnId) {
        return m_outstandingTxns.get(txnId);
    }

    private void logRepair(VoltMessage message) {
        //null check for unit test
        if (m_repairLog != null) {
            m_repairLog.deliver(message);
        }
    }

    public void checkPointBalanceSPI() {
        m_spiCheckPoint = getMaxScheduledTxnSpHandle();
        tmLog.info("Balance spi checkpoint on " + CoreUtils.hsIdToString(m_mailbox.getHSId()) +
                    " sphandle: " + m_spiCheckPoint);
    }

    public boolean txnDoneBeforeCheckPoint() {
        if (m_spiCheckPoint < 0) {
            return false;
        }
        List<DuplicateCounterKey> keys = m_duplicateCounters.keySet().stream()
                .filter(k->k.m_spHandle < m_spiCheckPoint).collect(Collectors.toList());
        if (!keys.isEmpty()) {
            if (tmLog.isDebugEnabled()) {
                StringBuilder builder = new StringBuilder();
                for (DuplicateCounterKey dc : keys) {
                    builder.append(TxnEgo.txnIdToString(dc.m_txnId) + "(" + dc.m_spHandle + "),");
                    DuplicateCounter counter = m_duplicateCounters.get(dc);
                    builder.append(counter.m_openMessage + "\n");
                }
                tmLog.debug("Duplicate counters on " + CoreUtils.hsIdToString(m_mailbox.getHSId()) + " have keys smaller than the sphandle:" + m_spiCheckPoint + "\n" + builder.toString());
            }
            return false;
        }
        tmLog.info("Balance spi previous leader " + CoreUtils.hsIdToString(m_mailbox.getHSId()) +
                " has completed transactions before sphandle: " + m_spiCheckPoint);
        m_spiCheckPoint = Long.MIN_VALUE;
        return true;
    }
}
