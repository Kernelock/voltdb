/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB L.L.C.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.voltdb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Hashtable;

import org.junit.Test;
import org.voltdb.VoltDB.Configuration;
import org.voltdb.fault.FaultDistributor;
import org.voltdb.messaging.Messenger;
import org.voltdb.messaging.impl.HostMessenger;
import org.voltdb.network.VoltNetwork;

public class TestEELibraryLoader {

    private class Interface implements VoltDBInterface {

        @Override
        public String getBuildString() {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public ArrayList<ClientInterface> getClientInterfaces() {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public Configuration getConfig() {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public CatalogContext getCatalogContext() {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public HostMessenger getHostMessenger() {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public Hashtable<Integer, ExecutionSite> getLocalSites() {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public Messenger getMessenger() {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public VoltNetwork getNetwork() {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public StatsAgent getStatsAgent() {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public FaultDistributor getFaultDistributor()
        {
            return null;
        }

        @Override
        public String getVersionString() {
            return "foobar";
        }

        @Override
        public void initialize(Configuration config) {
            // TODO Auto-generated method stub

        }

        @Override
        public boolean isRunning() {
            // TODO Auto-generated method stub
            return false;
        }

        @Override
        public void readBuildInfo() {
            // TODO Auto-generated method stub

        }

        @Override
        public void run() {
            // TODO Auto-generated method stub

        }

        @Override
        public void shutdown(Thread mainSiteThread)
                throws InterruptedException {
            // TODO Auto-generated method stub

        }

        @Override
        public void startSampler() {
            // TODO Auto-generated method stub

        }

        @Override
        public boolean ignoreCrash() {
            m_crash = true;
            return true;
        }

        private boolean m_crash = false;

        @Override
        public void catalogUpdate(String diffCommands, String newCatalogURL, int expectedCatalogVersion) {
            // TODO Auto-generated method stub

        }

        @Override
        public Object[] getInstanceId() {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public BackendTarget getBackendTargetType() {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public void clusterUpdate(String diffCommands)
        {
            // TODO Auto-generated method stub

        }
    }

    @Test
    public void testLoader() {
        final VoltDB.Configuration configuration = new VoltDB.Configuration();
        configuration.m_noLoadLibVOLTDB = true;
        Interface intf = new Interface();

        VoltDB.replaceVoltDBInstanceForTest(intf);

        assertFalse(EELibraryLoader.loadExecutionEngineLibrary(false));
        assertFalse(intf.m_crash);
        assertFalse(EELibraryLoader.loadExecutionEngineLibrary(true));
        assertTrue(intf.m_crash);
        VoltDB.initialize(configuration);
        intf.m_crash = false;
        assertFalse(EELibraryLoader.loadExecutionEngineLibrary(true));
        assertFalse(intf.m_crash);
    }
}
