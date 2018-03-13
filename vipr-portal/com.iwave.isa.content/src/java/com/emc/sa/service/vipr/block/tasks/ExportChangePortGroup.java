/*
 * Copyright (c) 2018 Dell-EMC
 * All Rights Reserved
 */
package com.emc.sa.service.vipr.block.tasks;

import java.net.URI;

import com.emc.sa.service.vipr.tasks.WaitForTask;
import com.emc.storageos.model.block.export.ChangePortGroupParam;
import com.emc.storageos.model.block.export.ExportGroupRestRep;
import com.emc.vipr.client.Task;

public class ExportChangePortGroup extends WaitForTask<ExportGroupRestRep> {
    private URI exportGroupId;
    private ChangePortGroupParam changePortGroupParam;    
    
    public ExportChangePortGroup(String exportGroupId, String newPortGroupId, Boolean suspendWait) {
        this(uri(exportGroupId), uri(newPortGroupId), suspendWait);
    }

    public ExportChangePortGroup(URI exportGroupId, URI newPortGroupId, Boolean suspendWait) {        
        this.exportGroupId = exportGroupId;
        this.changePortGroupParam = new ChangePortGroupParam();
        changePortGroupParam.setNewPortGroup(newPortGroupId);
        changePortGroupParam.setWaitBeforeRemovePaths(suspendWait);
             
        provideDetailArgs(exportGroupId, newPortGroupId, suspendWait);
    }

    @Override
    protected Task<ExportGroupRestRep> doExecute() throws Exception {  
        return getClient().blockExports().changePortGroup(exportGroupId, changePortGroupParam);                
    }
}