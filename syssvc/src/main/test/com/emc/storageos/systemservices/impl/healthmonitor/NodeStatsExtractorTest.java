/*
 * Copyright (c) 2013 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.systemservices.impl.healthmonitor;

import com.emc.vipr.model.sys.healthmonitor.DiskStats;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.util.List;

public class NodeStatsExtractorTest extends NodeStatsExtractor {

    private static final String INVALID_PID = "0";
    private static volatile String validPID = null;

    @BeforeClass
    public static void getValidPID() throws Exception {
        File procDir = new File(PROC_DIR);
        File[] procFiles = procDir.listFiles();
        String sname = null;
        for (File f : procFiles) {
            validPID = f.getName().trim();
            if (validPID.equalsIgnoreCase(SELF_DIR)) {
                continue;
            }
            sname = ProcStats.getServiceName(validPID);
            if (sname != null && !sname.isEmpty() && !"monitor".equals(sname)) {
                break;
            }
        }
        Assert.assertNotNull(validPID);
    }

    @Test
    public void testDiskStatsWithInterval() {
        List<DiskStats> diskStatsList = getDiskStats(2);
        Assert.assertTrue(diskStatsList != null && !diskStatsList.isEmpty());
    }

    @Test
    public void testDiskStatsWithoutInterval() {
        List<DiskStats> diskStatsList = getDiskStats(0);
        Assert.assertTrue(diskStatsList != null && !diskStatsList.isEmpty());
    }

    @Test
    public void testNegDeltaMS() {
        double delta = getCPUTimeDeltaMS(null, null);
        Assert.assertFalse(delta > 0);
    }

    @Test
    public void testPerSec() {
        double persec = getRate(23000, 2);
        Assert.assertTrue(persec > 0);
    }

    @Test
    public void testNegPerSec() {
        double persec = getRate(23, 0);
        Assert.assertFalse(persec > 0);
    }
}
