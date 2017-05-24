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

package org.voltdb;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import com.google_voltpatches.common.collect.ImmutableMap;
import java.util.Random;
import org.voltcore.logging.Level;
import org.voltcore.logging.VoltLogger;
import org.voltdb.AuthSystem.AuthUser;
import org.voltdb.catalog.Procedure;
import org.voltdb.catalog.Table;
import org.voltdb.client.BatchTimeoutOverrideType;
import org.voltdb.client.ClientResponse;
import org.voltdb.client.ProcedureCallback;
import org.voltdb.utils.MiscUtils;

/**
 * This class packs the parameters and dispatches the transactions.
 * Make sure responses over network thread does not touch this class.
 */
public class InternalConnectionHandler {
    final static String DEFAULT_INTERNAL_ADAPTER_NAME = "+!_InternalAdapter_!+";

    public final static long SUPPRESS_INTERVAL = 60;
    private static final VoltLogger m_logger = new VoltLogger("InternalConnectionHandler");

    // Atomically allows the catalog reference to change between access
    private final AtomicLong m_failedCount = new AtomicLong();
    private final AtomicLong m_submitSuccessCount = new AtomicLong();
    private volatile Map<Integer, InternalClientResponseAdapter> m_adapters = ImmutableMap.of();
    private final Random m_random = new Random();

    // Synchronized in case multiple partitions are added concurrently.
    public synchronized void addAdapter(int pid, InternalClientResponseAdapter adapter)
    {
        final ImmutableMap.Builder<Integer, InternalClientResponseAdapter> builder = ImmutableMap.builder();
        builder.putAll(m_adapters);
        builder.put(pid, adapter);
        m_adapters = builder.build();
    }

    public boolean hasAdapter(int pid)
    {
        return m_adapters.containsKey(pid);
    }

    /**
     * Returns true if a table with the given name exists in the server catalog.
     */
    public boolean hasTable(String name) {
        Table table = getCatalogContext().tables.get(name);
        return (table!=null);
    }

    public class NullCallback implements ProcedureCallback {
        @Override
        public void clientCallback(ClientResponse response) throws Exception {
        }
    }

    private CatalogContext getCatalogContext() {
        return VoltDB.instance().getCatalogContext();
    }

    public boolean callProcedure(
            AuthUser user,
            boolean isAdmin,
            int timeout,
            ProcedureCallback cb,
            String procName,
            Object...args)
    {

        Procedure catProc = InvocationDispatcher.getProcedureFromName(procName, getCatalogContext());
        if (catProc == null) {
            String fmt = "Cannot invoke procedure %s. Procedure not found.";
            m_logger.rateLimitedLog(SUPPRESS_INTERVAL, Level.ERROR, null, fmt, procName);
            m_failedCount.incrementAndGet();
            return false;
        }

        StoredProcedureInvocation task = new StoredProcedureInvocation();
        task.setProcName(procName);
        task.setParams(args);

        try {
            task = MiscUtils.roundTripForCL(task);
        } catch (Exception e) {
            String fmt = "Cannot invoke procedure %s. failed to create task.";
            m_logger.rateLimitedLog(SUPPRESS_INTERVAL, Level.ERROR, null, fmt, procName);
            m_failedCount.incrementAndGet();
            return false;
        }

        if (timeout != BatchTimeoutOverrideType.NO_TIMEOUT) {
            task.setBatchTimeout(timeout);
        }

        int partition = -1;
        try {
            partition = InvocationDispatcher.getPartitionForProcedure(catProc, task);
        } catch (Exception e) {
            String fmt = "Can not invoke procedure %s. Partition not found.";
            m_logger.rateLimitedLog(SUPPRESS_INTERVAL, Level.ERROR, e, fmt, procName);
            m_failedCount.incrementAndGet();
            return false;
        }

        final InternalClientResponseAdapter adapter = m_adapters.get(partition);
        InternalAdapterTaskAttributes kattrs = new InternalAdapterTaskAttributes(
                DEFAULT_INTERNAL_ADAPTER_NAME, isAdmin, adapter.connectionId());

        if (!adapter.createTransaction(kattrs, procName, catProc, cb, null, task, user, partition, null)) {
            m_failedCount.incrementAndGet();
            return false;
        }
        m_submitSuccessCount.incrementAndGet();
        return true;
    }

    // Use backPressureTimeout value <= 0  for no back pressure timeout
    public boolean callProcedure(InternalConnectionContext caller,
                                 Function<Integer, Boolean> backPressurePredicate,
                                 InternalConnectionStatsCollector statsCollector,
                                 ProcedureCallback procCallback, String proc, Object... fieldList) {
        Procedure catProc = InvocationDispatcher.getProcedureFromName(proc, getCatalogContext());
        if (catProc == null || (m_adapters.size() == 0)) {
            String fmt = "Cannot invoke procedure %s from streaming interface %s. Procedure not found.";
            m_logger.rateLimitedLog(SUPPRESS_INTERVAL, Level.ERROR, null, fmt, proc, caller);
            m_failedCount.incrementAndGet();
            return false;
        }

        StoredProcedureInvocation task = new StoredProcedureInvocation();

        task.setProcName(proc);
        task.setParams(fieldList);
        try {
            task = MiscUtils.roundTripForCL(task);
        } catch (Exception e) {
            String fmt = "Cannot invoke procedure %s from streaming interface %s. failed to create task.";
            m_logger.rateLimitedLog(SUPPRESS_INTERVAL, Level.ERROR, null, fmt, proc, caller);
            m_failedCount.incrementAndGet();
            return false;
        }
        int partition = m_random.nextInt(m_adapters.size()-1);
        final InternalClientResponseAdapter adapter = m_adapters.get(partition);
        if (adapter == null) {
            String fmt = "Cannot invoke procedure %s from streaming interface %s. failed to get adapter. " + m_adapters.size();
            m_logger.rateLimitedLog(SUPPRESS_INTERVAL, Level.ERROR, null, fmt, proc, caller);
            return false;
        }
        InternalAdapterTaskAttributes kattrs = new InternalAdapterTaskAttributes(caller,  adapter.connectionId());

        final AuthUser user = getCatalogContext().authSystem.getImporterUser();

        if (!adapter.createTransaction(kattrs, proc, catProc, procCallback, statsCollector, task, user, partition, backPressurePredicate)) {
            m_failedCount.incrementAndGet();
            return false;
        }
        m_submitSuccessCount.incrementAndGet();
        return true;
    }
}
