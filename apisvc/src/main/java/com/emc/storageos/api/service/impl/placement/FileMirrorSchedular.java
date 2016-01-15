/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.api.service.impl.placement;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Iterator;

import com.emc.storageos.api.service.authorization.PermissionsHelper;
import com.emc.storageos.db.client.DbClient;

import com.emc.storageos.db.client.model.Project;

import com.emc.storageos.db.client.model.VirtualArray;
import com.emc.storageos.db.client.model.VirtualPool;
import com.emc.storageos.volumecontroller.AttributeMatcher;
import com.emc.storageos.db.client.model.VirtualPool.FileReplicationType;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.db.client.model.VpoolRemoteCopyProtectionSettings;
import com.emc.storageos.fileorchestrationcontroller.FileDescriptor;



import com.emc.storageos.volumecontroller.impl.utils.VirtualPoolCapabilityValuesWrapper;
import com.emc.storageos.api.service.impl.placement.FileMirrorRecommendation.Target;
import com.emc.storageos.api.service.impl.placement.FileStorageScheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

public class FileMirrorSchedular implements Scheduler {
    public final Logger _log = LoggerFactory
            .getLogger(FileMirrorSchedular.class);

    private DbClient _dbClient;
    private StorageScheduler _storageScheduler;
    private FileStorageScheduler _fileScheduler;

    public void setStorageScheduler(final StorageScheduler storageScheduler) {
        _storageScheduler = storageScheduler;
    }

    public void setDbClient(final DbClient dbClient) {
        _dbClient = dbClient;
    }

    public void setFileScheduler(FileStorageScheduler fileScheduler) {
        _fileScheduler = fileScheduler;
    }

    @Autowired
    protected PermissionsHelper _permissionsHelper = null;

    /**
     * get list mirror recommendation for mirror file shares
     */
    @Override
    public List getRecommendationsForResources(VirtualArray varray,
            Project project, VirtualPool vpool,
            VirtualPoolCapabilityValuesWrapper capabilities) {
        List<FileRecommendation> recommendations = null;
        if( vpool.getFileReplicationType().equals(VirtualPool.FileReplicationType.REMOTE.name())){
            
        } else {
            recommendations = getLocalMirrorRecommendationsForResources(varray, project, vpool, capabilities);
        }
        return recommendations;
    }
    
    public List getLocalMirrorRecommendationsForResources(VirtualArray vArray,
            Project project, VirtualPool vPool,
            VirtualPoolCapabilityValuesWrapper capabilities) {
        
        List<FileMirrorRecommendation> fileMirrorRecommendations = new ArrayList<FileMirrorRecommendation>();
        List<FileRecommendation> targetFileRecommendations = null;
        FileMirrorRecommendation fileMirrorRecommendation = null;
        
        List<FileRecommendation> fileRecommendations = 
                _fileScheduler.getRecommendationsForResources(vArray, project, vPool, capabilities);
        
        for(FileRecommendation fileRecommendation: fileRecommendations) {
        
            
            fileMirrorRecommendation = new FileMirrorRecommendation(fileRecommendation);
            _fileScheduler.placeFileShare(vArray, vPool, capabilities, project, null);
            
            //attribute map
            Map<String, Object> attributeMap = new HashMap<String, Object>();
            Set<String> storageSystemSet = new HashSet<String>();
            storageSystemSet.add(fileMirrorRecommendation.getSourceStorageSystem().toString());
            attributeMap.put(AttributeMatcher.Attributes.storage_system.name(), storageSystemSet);

            Set<String> virtualArraySet = new HashSet<String>();
            virtualArraySet.add(vArray.getId().toString());
            attributeMap.put(AttributeMatcher.Attributes.varrays.name(), virtualArraySet);
            
            //get target recommendations
            targetFileRecommendations = _fileScheduler.placeFileShare(vArray, vPool, capabilities, project, attributeMap);
            
            prepareTargetMirrorRecommendation(vPool.getFileReplicationCopyMode(), 
                    vArray, targetFileRecommendations.get(0), fileMirrorRecommendation);
            
            fileMirrorRecommendations.add(fileMirrorRecommendation);
        }
        
        
        
        return fileMirrorRecommendations;
    }
    
    void prepareTargetMirrorRecommendation(final String fsCopyMode, final VirtualArray targetVarray, 
                                                        final FileRecommendation targetFileRecommendation, 
                                                        FileMirrorRecommendation fileMirrorRecommendation) {
        
        //set target recommendations
        Target target = new Target();
        
        target.setTargetPool(targetFileRecommendation.getSourceStoragePool());
        target.setTargetStorageDevice(targetFileRecommendation.getSourceStorageSystem());
        target.setTargetStoragePortUris(targetFileRecommendation.getStoragePorts());
        target.setTargetvNASURI(targetFileRecommendation.getvNAS());
        
        fileMirrorRecommendation.getVirtualArrayTargetMap().put(targetVarray.getId(), target);
        
        //file replication copy mode
        fileMirrorRecommendation.setCopyMode(fsCopyMode);
    }
    
    
    

    /**
     * Gets and verifies that the target varrays passed in the request are accessible to the tenant.
     *
     * @param project
     *            A reference to the project.
     * @param vpool
     *            class of service, contains target varrays
     * @return A reference to the varrays
     * @throws java.net.URISyntaxException
     * @throws com.emc.storageos.db.exceptions.DatabaseException
     */
    static public List<VirtualArray> getTargetVirtualArraysForVirtualPool(final Project project,
                                                                          final VirtualPool vpool, final DbClient dbClient,
                                                                          final PermissionsHelper permissionHelper) {
        List<VirtualArray> targetVirtualArrays = new ArrayList<VirtualArray>();
        if (VirtualPool.getFileRemoteProtectionSettings(vpool, dbClient) != null) {
            for (URI targetVirtualArray : VirtualPool.getFileRemoteProtectionSettings(vpool, dbClient)
                    .keySet()) {
                VirtualArray nh = dbClient.queryObject(VirtualArray.class, targetVirtualArray);
                targetVirtualArrays.add(nh);
                permissionHelper.checkTenantHasAccessToVirtualArray(
                        project.getTenantOrg().getURI(), nh);
            }
        }
        return targetVirtualArrays;
    }


}