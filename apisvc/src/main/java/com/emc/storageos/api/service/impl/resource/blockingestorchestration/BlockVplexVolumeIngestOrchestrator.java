/*
 * Copyright (c) 2008-2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.api.service.impl.resource.blockingestorchestration;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.api.service.impl.resource.TenantsService;
import com.emc.storageos.api.service.impl.resource.VPlexBlockServiceApiImpl;
import com.emc.storageos.api.service.impl.resource.utils.CapacityUtils;
import com.emc.storageos.api.service.impl.resource.utils.PropertySetterUtil;
import com.emc.storageos.api.service.impl.resource.utils.VolumeIngestionUtil;
import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.URIUtil;
import com.emc.storageos.db.client.constraint.AlternateIdConstraint;
import com.emc.storageos.db.client.constraint.URIQueryResultList;
import com.emc.storageos.db.client.model.BlockConsistencyGroup;
import com.emc.storageos.db.client.model.BlockObject;
import com.emc.storageos.db.client.model.DataObject;
import com.emc.storageos.db.client.model.DataObject.Flag;
import com.emc.storageos.db.client.model.ExportGroup;
import com.emc.storageos.db.client.model.Initiator;
import com.emc.storageos.db.client.model.NamedURI;
import com.emc.storageos.db.client.model.OpStatusMap;
import com.emc.storageos.db.client.model.Project;
import com.emc.storageos.db.client.model.StoragePool;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.StringMap;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.client.model.TenantOrg;
import com.emc.storageos.db.client.model.VirtualArray;
import com.emc.storageos.db.client.model.VirtualPool;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.db.client.model.VplexMirror;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedExportMask;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedVolume;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedVolume.SupportedVolumeInformation;
import com.emc.storageos.db.client.model.util.BlockConsistencyGroupUtils;
import com.emc.storageos.model.block.VolumeExportIngestParam;
import com.emc.storageos.svcs.errorhandling.resources.APIException;
import com.emc.storageos.vplex.api.VPlexApiException;
import com.emc.storageos.vplexcontroller.VPlexControllerUtils;

/**
 * Responsible for ingesting vplex local and distributed virtual volumes.
 */
public class BlockVplexVolumeIngestOrchestrator extends BlockVolumeIngestOrchestrator {
    private static final Logger _logger = LoggerFactory.getLogger(BlockVplexVolumeIngestOrchestrator.class);

    private static final long CACHE_TIMEOUT = 600000; // ten minutes
    private long cacheLastRefreshed = 0;

    // maps the cluster id (1 or 2) to its name (e.g., cluster-1 or cluster-2)
    private Map<String, String> clusterIdToNameMap = new HashMap<String, String>();

    // maps each virtual array's URI to the cluster id (1 or 2) it connects to
    private Map<String, String> varrayToClusterIdMap = new HashMap<String, String>();

    // maps storage system URIs to StorageSystem objects
    private Map<String, StorageSystem> systemMap = new HashMap<String, StorageSystem>();

    private IngestStrategyFactory ingestStrategyFactory;

    public void setIngestStrategyFactory(IngestStrategyFactory ingestStrategyFactory) {
        this.ingestStrategyFactory = ingestStrategyFactory;
    }

    private TenantsService _tenantsService;

    public void setTenantsService(TenantsService tenantsService) {
        _tenantsService = tenantsService;
    }

    @Override
    public <T extends BlockObject> T ingestBlockObjects(List<URI> systemCache, List<URI> poolCache, StorageSystem system,
            UnManagedVolume unManagedVolume,
            VirtualPool vPool, VirtualArray virtualArray, Project project, TenantOrg tenant,
            List<UnManagedVolume> unManagedVolumesToBeDeleted,
            Map<String, BlockObject> createdObjectMap, Map<String, List<DataObject>> updatedObjectMap, boolean unManagedVolumeExported,
            Class<T> clazz,
            Map<String, StringBuffer> taskStatusMap) throws IngestionException {
        // For VPLEX volumes, verify that it is OK to ingest the unmanaged
        // volume into the requested virtual array.

        long timeRightNow = new Date().getTime();
        if (timeRightNow > (cacheLastRefreshed + CACHE_TIMEOUT)) {
            _logger.debug("clearing vplex ingestion api info cache");
            clusterIdToNameMap.clear();
            varrayToClusterIdMap.clear();
            cacheLastRefreshed = timeRightNow;
        }

        _logger.info("isValidVarrayForUnmanagedVolume");
        if (!VolumeIngestionUtil.isValidVarrayForUnmanagedVolume(unManagedVolume, virtualArray.getId(),
                clusterIdToNameMap, varrayToClusterIdMap, _dbClient)) {
            _logger.warn("UnManaged Volume {} cannot be ingested into the requested varray. Skipping Ingestion.",
                    unManagedVolume.getLabel());
            _logger.info("isValidVarrayForUnmanagedVolume done about to throw varrayIsInvalidForVplexVolume");
            throw IngestionException.exceptions.varrayIsInvalidForVplexVolume(virtualArray.getLabel(), unManagedVolume.getLabel());
        }
        _logger.info("isValidVarrayForUnmanagedVolume done");
        
        
        
        
        URI vplexUri = unManagedVolume.getStorageSystemUri();
        Iterator<UnManagedVolume> allUnmanagedVolumes = null;
        _logger.info("about to start dingle timer");
        long dingleTimer = new Date().getTime();
        Map<String,URI> deviceToUnManagedVolumeMap = new HashMap<String,URI>();
        List<URI> storageSystem = new ArrayList<URI>();
        storageSystem.add(vplexUri);
        try {
            // TODO how long will this nonsense take? no way to query StringSetMap values with dbClient
            _logger.info("about to query for ids");
            List<URI> ids = _dbClient.queryByType(UnManagedVolume.class, true);
            List<String> fields = new ArrayList<String>();
            fields.add("storageDevice");
            fields.add("volumeInformation");
            allUnmanagedVolumes = _dbClient.queryIterativeObjectFields(UnManagedVolume.class, fields, ids);
        } catch (Throwable t) {
            // TODO: remove this, but having trouble with dbclient not logging in testing
            _logger.error("Throwable caught!!!!!!!!!!!!!! ");
            _logger.error("Throwable caught: " + t.toString());
        }
        if (null != allUnmanagedVolumes) {
            while (allUnmanagedVolumes.hasNext()) {
                UnManagedVolume vol = allUnmanagedVolumes.next();
                if (vol.getStorageSystemUri().equals(vplexUri)) {
                    String supportingDeviceName = 
                            PropertySetterUtil.extractValueFromStringSet(
                                    SupportedVolumeInformation.VPLEX_SUPPORTING_DEVICE_NAME.toString(), 
                                    vol.getVolumeInformation());
                    if (null != supportingDeviceName) {
                        deviceToUnManagedVolumeMap.put(supportingDeviceName, vol.getId());
                    }
                }
            }
        } else {
            // :(
            throw IngestionException.exceptions.generalException("could not load deviceToUnManagedVolumeMap");
        }
        _logger.info("creating deviceToUnManagedVolumeMap took {} ms", new Date().getTime() - dingleTimer);
        
        // we create a subset of all these collections that are just for the current vplex virtual volume being ingested
        Map<String, UnManagedVolume> vplexBackendProcessedUnManagedVolumeMap = new HashMap<String, UnManagedVolume>();
        Map<String, BlockObject> vplexBackendCreatedObjectMap = new HashMap<String, BlockObject>();
        Map<String, List<DataObject>> vplexBackendUpdatedObjectMap = new HashMap<String, List<DataObject>>();
        List<String> associatedVolumeGuids = new ArrayList<String>();
        List<UnManagedVolume> mirrors = new ArrayList<UnManagedVolume>();
        try {
            List<URI> associatedVolumeUris = getAssociatedVolumes(unManagedVolume);
            
            if (null != associatedVolumeUris && !associatedVolumeUris.isEmpty()) {

                Project vplexProject = VPlexBlockServiceApiImpl.getVplexProject(system, _dbClient, _tenantsService);
                validateAssociatedVolumes(vPool, vplexProject, tenant, associatedVolumeUris);

                List<UnManagedVolume> associatedVolumes = _dbClient.queryObject(UnManagedVolume.class, associatedVolumeUris);
                for (UnManagedVolume vol : associatedVolumes) {
                    associatedVolumeGuids.add(vol.getNativeGuid().replace(VolumeIngestionUtil.UNMANAGEDVOLUME,
                            VolumeIngestionUtil.VOLUME));
                }
                
                // check for snaps and clones
                List<UnManagedVolume> snapshots = checkForSnapshots(associatedVolumes);
                // map of front-end clone virtual volume to backend volume clone
                Map<UnManagedVolume, UnManagedVolume> cloneMap = checkForVvolClones(associatedVolumes, _dbClient, 
                        unManagedVolume.getStorageSystemUri(), deviceToUnManagedVolumeMap);
                // TODO: add a "checkForSimpleClones" method - this above will check for ones with vvols only
                
                // check for mirrors
                boolean isSyncActive = false;
                Iterator<UnManagedVolume> it = associatedVolumes.iterator();
                while (it.hasNext() && !isSyncActive) {
                    if (VolumeIngestionUtil.isSyncActive(it.next())){
                        isSyncActive = true;
                    }
                }
                if (isSyncActive) {
                    Map<UnManagedVolume, UnManagedVolume> mirrorsMap = 
                            checkForMirrors(unManagedVolume, associatedVolumes);
                    if (null != mirrorsMap && !mirrorsMap.isEmpty()) {
                        for (Entry<UnManagedVolume, UnManagedVolume> entry : mirrorsMap.entrySet()) {
                            UnManagedVolume mirrorVolume = entry.getValue();
                            associatedVolumes.remove(mirrorVolume);
                            mirrors.add(mirrorVolume);
                        }
                    }
                }
                
                List<UnManagedVolume> allVolumes = new ArrayList<UnManagedVolume>();
                allVolumes.addAll(associatedVolumes);
                allVolumes.addAll(snapshots);
                allVolumes.addAll(cloneMap.keySet());
                allVolumes.addAll(cloneMap.values());

                ingestBackendVolumes(systemCache, poolCache, vPool,
                        virtualArray, vplexProject, tenant,
                        unManagedVolumesToBeDeleted, taskStatusMap,
                        allVolumes, vplexBackendProcessedUnManagedVolumeMap,
                        vplexBackendCreatedObjectMap, vplexBackendUpdatedObjectMap);

                List<BlockObject> ingestedObjects = new ArrayList<BlockObject>();

                ingestBackendExportMasks(system, vPool, virtualArray, vplexProject,
                        tenant, unManagedVolumesToBeDeleted, vplexBackendUpdatedObjectMap,
                        taskStatusMap, associatedVolumes,
                        vplexBackendProcessedUnManagedVolumeMap, vplexBackendCreatedObjectMap,
                        ingestedObjects);
                
                for (BlockObject o : ingestedObjects) {
                    _logger.info("ingested object: " + o.getNativeGuid());
                }
                
                for (BlockObject o : vplexBackendCreatedObjectMap.values()) {
                    _logger.info("vplex created object map: " + o.getNativeGuid());
                }
                
                for (Entry e : vplexBackendUpdatedObjectMap.entrySet()) {
                    _logger.info("updated object map: " + e.getKey() + " : " + e.getValue());
                }
                
                for (UnManagedVolume umv : vplexBackendProcessedUnManagedVolumeMap.values()) {
                    _logger.info("processed unmanaged volume: " + umv.getNativeGuid());
                }
                
                for (UnManagedVolume umv : unManagedVolumesToBeDeleted) {
                    _logger.info("unmanaged volume to be deleted: " + umv.getNativeGuid());
                }
                
                _dbClient.createObject(ingestedObjects);
                _dbClient.createObject(vplexBackendCreatedObjectMap.values());
                for (List<DataObject> dos : vplexBackendUpdatedObjectMap.values()) {
                    _logger.info("persisting " + dos);
                    _dbClient.persistObject(dos);
                }
                _dbClient.persistObject(vplexBackendProcessedUnManagedVolumeMap.values());
            }
        } catch (Exception ex) {
            
            // TODO: error handlin'
            _logger.error("error!!!", ex);
            return null;
        }

        _logger.info("About to ingest the actual VPLEX virtual volume...");
        T virtualVolume = super.ingestBlockObjects(systemCache, poolCache, system, unManagedVolume, vPool, virtualArray, project, tenant,
                unManagedVolumesToBeDeleted, createdObjectMap, updatedObjectMap, unManagedVolumeExported, clazz, taskStatusMap);
        
        if (null != virtualVolume && virtualVolume instanceof Volume) {
            Collection<BlockObject> associatedVols = vplexBackendCreatedObjectMap.values();
            StringSet vols = new StringSet();
            for (BlockObject vol : associatedVols) {
                if (associatedVolumeGuids.contains(vol.getNativeGuid())) {
                    vols.add(vol.getId().toString());
                }
            }
            ((Volume) virtualVolume).setAssociatedVolumes(vols);
            // TODO: set associated vol on clone
        }
        
        if (null != mirrors && !mirrors.isEmpty()) {
            _logger.info("creating VplexMirror object");
            for (UnManagedVolume umv : mirrors) {
                // find mirror and create a VplexMirror object
                BlockObject mirror = createdObjectMap.get(umv.getNativeGuid()
                    .replace(VolumeIngestionUtil.UNMANAGEDVOLUME,
                        VolumeIngestionUtil.VOLUME));
                if (null != mirror) {
                    if (mirror instanceof Volume) {
                        Volume mirrorVolume = (Volume) mirror;
                        VplexMirror vplexMirror = new VplexMirror();
                        StringSet associatedVolumes = new StringSet();
                        associatedVolumes.add(mirrorVolume.getId().toString());
                        vplexMirror.setAssociatedVolumes(associatedVolumes);
                        String deviceName = PropertySetterUtil.extractValueFromStringSet(
                                SupportedVolumeInformation.VPLEX_SUPPORTING_DEVICE_NAME.toString(),
                                    unManagedVolume.getVolumeInformation());
                        vplexMirror.setDeviceLabel(deviceName);
                        vplexMirror.setCapacity(mirrorVolume.getCapacity());
                        vplexMirror.setLabel(mirrorVolume.getLabel());
                        vplexMirror.setNativeId(umv.getNativeGuid());
                        vplexMirror.setProject(mirrorVolume.getProject());
                        vplexMirror.setProvisionedCapacity(mirrorVolume.getProvisionedCapacity());
                        // TODO figure out source
                        // vplexMirror.setSource(virtualVolume.getId());
                        vplexMirror.setStorageController(mirrorVolume.getStorageController());
                        vplexMirror.setTenant(mirrorVolume.getTenant());
                        vplexMirror.setThinPreAllocationSize(mirrorVolume.getThinVolumePreAllocationSize());
                        vplexMirror.setThinlyProvisioned(vplexMirror.getThinlyProvisioned());
                        vplexMirror.setVirtualArray(vplexMirror.getVirtualArray());
                        vplexMirror.setVirtualPool(vplexMirror.getVirtualPool());
                        
                        _dbClient.createObject(vplexMirror);
                    }
                }
            }
        }
        
        return virtualVolume;
    }

    private List<UnManagedVolume> checkForSnapshots(List<UnManagedVolume> sourceVolumes) {
        List<UnManagedVolume> snapshotsFound = new ArrayList<UnManagedVolume>();
        
        for (UnManagedVolume sourceVolume : sourceVolumes) {
            snapshotsFound.addAll(VolumeIngestionUtil.getUnManagedSnaphots(sourceVolume, _dbClient));
        }
        
        _logger.info("found these associated snapshots: " + snapshotsFound);
        return snapshotsFound;
    }

    private Map<UnManagedVolume, UnManagedVolume> checkForVvolClones(
            List<UnManagedVolume> sourceVolumes, DbClient dbClient, 
            URI vplexUri, Map<String,URI> deviceToUnManagedVolumeMap) {

        Map<UnManagedVolume, UnManagedVolume> virtualVolumeToBackendCloneMap = 
                new HashMap<UnManagedVolume, UnManagedVolume>();
        
        List<UnManagedVolume> backendClonesFound = new ArrayList<UnManagedVolume>();
        for (UnManagedVolume sourceVolume : sourceVolumes) {
            backendClonesFound.addAll(VolumeIngestionUtil.getUnManagedClones(sourceVolume, _dbClient));
        }
        
        if (backendClonesFound.isEmpty()) {
            _logger.info("no clones found for source volumes: " + sourceVolumes);
        } else {
            for (UnManagedVolume backendClone : backendClonesFound) {
                String volumeNativeId = PropertySetterUtil.extractValueFromStringSet(
                        SupportedVolumeInformation.NATIVE_ID.toString(),
                        backendClone.getVolumeInformation());
                
                StorageSystem backendSystem = 
                        dbClient.queryObject(StorageSystem.class, backendClone.getStorageSystemUri());
                
                String deviceName = VPlexControllerUtils.getDeviceForStorageVolume(
                        volumeNativeId, backendClone.getWwn(), backendSystem.getSerialNumber(), vplexUri, dbClient);
                
                if (null != deviceName) {
                    _logger.info("found device name {} for native id {}", deviceName, volumeNativeId);
                    URI umvUri = deviceToUnManagedVolumeMap.get(deviceName);
                    if (null != umvUri) {
                        UnManagedVolume virtualVolumeClone = dbClient.queryObject(UnManagedVolume.class, umvUri);
                        if (null != virtualVolumeClone) {
                            _logger.info("adding mapping for vvol clone {} to backend clone {}", virtualVolumeClone, backendClone);
                            virtualVolumeToBackendCloneMap.put(virtualVolumeClone, backendClone);
                        }
                    }
                } else {
                    _logger.info("could not determine supporting device name for native id " + volumeNativeId);
                }
            }
        }
        
        return virtualVolumeToBackendCloneMap;
    }

    private Map<UnManagedVolume, UnManagedVolume> checkForMirrors(UnManagedVolume unManagedVolume, 
            List<UnManagedVolume> associatedVolumes) {
        _logger.info("checking for mirrors on volume " + unManagedVolume.getNativeGuid());
        Map<UnManagedVolume, UnManagedVolume> mirrorMap = new HashMap<UnManagedVolume, UnManagedVolume>();
        
        Map<String, String> associatedVolumesInfo = new HashMap<String, String>();
        for (UnManagedVolume vol : associatedVolumes) {
            associatedVolumesInfo.put(vol.getNativeGuid(), vol.getWwn());
        }
        
        String deviceName = PropertySetterUtil.extractValueFromStringSet(
                SupportedVolumeInformation.VPLEX_SUPPORTING_DEVICE_NAME.toString(),
                    unManagedVolume.getVolumeInformation());
        
        String locality = PropertySetterUtil.extractValueFromStringSet(
                SupportedVolumeInformation.VPLEX_LOCALITY.toString(),
                    unManagedVolume.getVolumeInformation());

        // map of the components of a top level device: device slot-# to device wwn
        // the assumption is that the source device will be in slot-0 and any mirrors above that
        Map<String, String> topLevelDeviceMap = VPlexControllerUtils.getTopLevelDeviceMap(deviceName, locality, 
                unManagedVolume.getStorageSystemUri(), _dbClient);
        
        if (null != topLevelDeviceMap && !topLevelDeviceMap.isEmpty()) {
            Map<String, UnManagedVolume> wwnToVolMap = new HashMap<String, UnManagedVolume>();
            for (UnManagedVolume vol : associatedVolumes) {
                wwnToVolMap.put(vol.getWwn(), vol);
            }
            
            UnManagedVolume slot0Vol= null;
            for (Entry<String, String> entry : topLevelDeviceMap.entrySet()) {
                if (null == slot0Vol) {
                    slot0Vol = wwnToVolMap.get(BlockObject.normalizeWWN(entry.getValue()));
                } else {
                    mirrorMap.put(slot0Vol, 
                        wwnToVolMap.get(BlockObject.normalizeWWN(entry.getValue())));
                }
            }
        }
        
        _logger.info("generated mirror map: " + mirrorMap);
        return mirrorMap;
    }
    
    private List<URI> getAssociatedVolumes(UnManagedVolume unManagedVolume) {
        List<URI> associatedVolumes = new ArrayList<URI>();
        String deviceName = PropertySetterUtil.extractValueFromStringSet(
                SupportedVolumeInformation.VPLEX_SUPPORTING_DEVICE_NAME.toString(),
                    unManagedVolume.getVolumeInformation());
        
        String locality = PropertySetterUtil.extractValueFromStringSet(
                SupportedVolumeInformation.VPLEX_LOCALITY.toString(),
                    unManagedVolume.getVolumeInformation());
        
        Set<String> backendVolumeWwns = null;
        try {
            backendVolumeWwns = 
                    VPlexControllerUtils.getStorageVolumeInfoForDevice(
                            deviceName, locality, 
                            unManagedVolume.getStorageSystemUri(), _dbClient);
            
        } catch (VPlexApiException ex) {
            _logger.error("could not determine backend storage volumes for {}: ", unManagedVolume.getLabel(), ex);
        }
        
        if (null != backendVolumeWwns) {
            for (String backendWwn : backendVolumeWwns) {
                _logger.info("attempting to find unmanaged backend volume by wwn {}", 
                        backendWwn);
                
                URIQueryResultList results = new URIQueryResultList();
                _dbClient.queryByConstraint(AlternateIdConstraint.
                        Factory.getUnmanagedVolumeWwnConstraint(
                                BlockObject.normalizeWWN(backendWwn)), results);
                if (results.iterator() != null) {
                    for (URI uri : results) {
                        associatedVolumes.add(uri);
                    }
                }
            }
        }
        
        _logger.info("for VPLEX UnManagedVolume {} found these associated volumes: " + associatedVolumes, unManagedVolume.getId());
        return associatedVolumes;
    }

    private void validateAssociatedVolumes(VirtualPool vPool, Project project,
            TenantOrg tenant, List<URI> associatedVolumes) throws Exception {
        _logger.info("validating the backend volumes: " + associatedVolumes);
        
        // TODO more validation
        // check if selected vpool can contain all the backend volumes
        // check quotas
        
        // check for Quotas
        long unManagedVolumesCapacity = VolumeIngestionUtil.getTotalUnManagedVolumeCapacity(_dbClient, associatedVolumes);
        _logger.info("UnManagedVolume provisioning quota validation successful");
        CapacityUtils.validateQuotasForProvisioning(_dbClient, vPool, project, tenant, unManagedVolumesCapacity, "volume");
        VolumeIngestionUtil.checkIngestionRequestValidForUnManagedVolumes(associatedVolumes, vPool, _dbClient);
    }

    private void ingestBackendVolumes(List<URI> systemCache,
            List<URI> poolCache, VirtualPool vPool, VirtualArray virtualArray,
            Project vplexProject, TenantOrg tenant,
            List<UnManagedVolume> unManagedVolumesToBeDeleted,
            Map<String, StringBuffer> taskStatusMap,
            List<UnManagedVolume> associatedVolumes,
            Map<String, UnManagedVolume> processedUnManagedVolumeMap,
            Map<String, BlockObject> vplexCreatedObjectMap,
            Map<String, List<DataObject>> vplexUpdatedObjectMap) {

        _logger.info("ingesting these backend volumes: " + associatedVolumes);
        for (UnManagedVolume associatedVolume : associatedVolumes) {
            _logger.info("Ingestion started for exported vplex backend unmanagedvolume {}", associatedVolume.getNativeGuid());

            try {
                URI storageSystemUri = associatedVolume.getStorageSystemUri();
                StorageSystem associatedSystem =  systemMap.get(storageSystemUri.toString());
                if (null == associatedSystem) {
                    associatedSystem = _dbClient.queryObject(StorageSystem.class, storageSystemUri);
                    systemMap.put(storageSystemUri.toString(), associatedSystem);
                }
                
                IngestStrategy ingestStrategy =  ingestStrategyFactory.buildIngestStrategy(associatedVolume);
                
                @SuppressWarnings("unchecked")
                BlockObject blockObject = ingestStrategy.ingestBlockObjects(systemCache, poolCache, 
                        associatedSystem, associatedVolume, vPool, virtualArray, 
                        vplexProject, tenant, unManagedVolumesToBeDeleted, vplexCreatedObjectMap, 
                        vplexUpdatedObjectMap, true, VolumeIngestionUtil.getBlockObjectClass(associatedVolume), taskStatusMap);
                
                _logger.info("Ingestion ended for exported unmanagedvolume {}", associatedVolume.getNativeGuid());
                if (null == blockObject)  {
                    
                    // TODO: handle this by not ingesting any backend vols; leave a nice message in success response
                    throw IngestionException.exceptions.generalVolumeException(
                            associatedVolume.getLabel(), "check the logs for more details");
                }
                
                vplexCreatedObjectMap.put(blockObject.getNativeGuid(), blockObject);
                processedUnManagedVolumeMap.put(associatedVolume.getNativeGuid(), associatedVolume);
            } catch ( APIException ex ) {
                _logger.warn(ex.getLocalizedMessage(), ex);
                // TODO: throw exception? sort out error handling
            } catch ( Exception ex ) {
                _logger.warn(ex.getLocalizedMessage(), ex);
                throw ex;
                // TODO: throw exception? sort out error handling
            }
        }
    }

    private void ingestBackendExportMasks(StorageSystem system,
            VirtualPool vPool, VirtualArray virtualArray, Project vplexProject,
            TenantOrg tenant,
            List<UnManagedVolume> unManagedVolumesToBeDeleted,
            Map<String, List<DataObject>> updatedObjectMap,
            Map<String, StringBuffer> taskStatusMap,
            List<UnManagedVolume> associatedVolumes,
            Map<String, UnManagedVolume> processedUnManagedVolumeMap,
            Map<String, BlockObject> vplexCreatedObjectMap,
            List<BlockObject> ingestedObjects) {
        
        for (String unManagedVolumeGUID: processedUnManagedVolumeMap.keySet()) {
            String objectGUID = unManagedVolumeGUID.replace(VolumeIngestionUtil.UNMANAGEDVOLUME, VolumeIngestionUtil.VOLUME);
            BlockObject processedBlockObject = vplexCreatedObjectMap.get(objectGUID);
            UnManagedVolume processedUnManagedVolume = processedUnManagedVolumeMap.get(unManagedVolumeGUID);
            _logger.info("ingesting export mask(s) for unmanaged volume " + processedUnManagedVolume);

            try {
                if(processedBlockObject == null) {
                    _logger.warn("The ingested block object is null. Skipping ingestion of export masks for unmanaged volume {}", unManagedVolumeGUID);
                    throw IngestionException.exceptions.generalVolumeException(
                            processedUnManagedVolume.getLabel(), "check the logs for more details");
                }
                
                if (processedUnManagedVolume.getUnmanagedExportMasks().isEmpty()) {
                    _logger.info("ingested block object {} has no unmanaged export masks", processedUnManagedVolume.getLabel());
                    continue;
                }
                
                // TODO happy path would just be one, but may need to account for more than one UEM
                String uemUri = processedUnManagedVolume.getUnmanagedExportMasks().iterator().next();
                UnManagedExportMask uem = _dbClient.queryObject(UnManagedExportMask.class, URI.create(uemUri));
                
                URI storageSystemUri = processedUnManagedVolume.getStorageSystemUri();
                StorageSystem associatedSystem =  systemMap.get(storageSystemUri.toString());

                IngestExportStrategy ingestStrategy =  ingestStrategyFactory.buildIngestExportStrategy(processedUnManagedVolume);
                
                List<URI> initUris = new ArrayList<URI>();
                for (String uri : uem.getKnownInitiatorUris()) {
                    initUris.add(URI.create(uri));
                }
                List<Initiator> initiators = _dbClient.queryObject(Initiator.class, initUris);
                
                ExportGroup exportGroup = this.createExportGroup(system, associatedSystem, initiators, 
                        virtualArray.getId(), vplexProject.getId(), tenant.getId(), 4, uem); 
                boolean exportGroupCreated = exportGroup != null;
                
                VolumeExportIngestParam exportIngestParam = new VolumeExportIngestParam();
                exportIngestParam.setProject(vplexProject.getId());
                exportIngestParam.setVarray(virtualArray.getId());
                exportIngestParam.setVpool(vPool.getId());
                
                List<URI> associatedVolumeUris = new ArrayList<URI>();
                for (UnManagedVolume umv : associatedVolumes) {
                    associatedVolumeUris.add(umv.getId());
                }
                exportIngestParam.setUnManagedVolumes(associatedVolumeUris);

                BlockObject blockObject = ingestStrategy.ingestExportMasks(processedUnManagedVolume, exportIngestParam, exportGroup, 
                        processedBlockObject, unManagedVolumesToBeDeleted, associatedSystem, exportGroupCreated, initiators);
                if (null == blockObject)  {
                    _logger.error("blockObject was null");
                    throw IngestionException.exceptions.generalVolumeException(
                            processedUnManagedVolume.getLabel(), "check the logs for more details");
                }
                ingestedObjects.add(blockObject);
                
                if (blockObject.checkInternalFlags(Flag.NO_PUBLIC_ACCESS)) {
                    StringBuffer taskStatus = taskStatusMap
                            .get(processedUnManagedVolume.getNativeGuid());
                    String taskMessage = "";
                    if (taskStatus == null) {
                        // No task status found. Put in a default message.
                        taskMessage = String
                                .format("Not all the parent/replicas of unManagedVolume %s have been ingested",
                                        processedUnManagedVolume.getLabel());
                    } else {
                        taskMessage = taskStatus.toString();
                    }
                    _logger.error(taskMessage);
                }

                // Update the related objects if any after successful export
                // mask ingestion
                List<DataObject> updatedObjects = updatedObjectMap.get(unManagedVolumeGUID);
                if (updatedObjects != null && !updatedObjects.isEmpty()) {
                    _dbClient.updateAndReindexObject(updatedObjects);
                }
            } catch ( APIException ex ) {
                _logger.warn(ex.getLocalizedMessage(), ex);
                // TODO: throw exception? sort out error handling
            } catch ( Exception ex ) {
                _logger.warn(ex.getLocalizedMessage(), ex);
                throw ex;
                // TODO: throw exception? sort out error handling
            }
        }
    }


    // THE FOLLOWING TWO METHODS ARE BASICALLY COPIED FROM VplexBackendManager 
    // with some tweakage, probably should be consolidated somehow


    /**
     * Create an ExportGroup.
     * @param vplex -- VPLEX StorageSystem
     * @param array -- Array StorageSystem
     * @param initiators -- Collection<Initiator> representing VPLEX back-end ports.
     * @param virtualArrayURI
     * @param projectURI
     * @param tenantURI
     * @param numPaths Value of maxPaths to be put in ExportGroup
     * @param exportMask IFF non-null, will add the exportMask to the Export Group.
     * @return newly created ExportGroup persisted in DB.
     */
    ExportGroup createExportGroup(StorageSystem vplex,
            StorageSystem array, Collection<Initiator> initiators, 
            URI virtualArrayURI,
            URI projectURI, URI tenantURI, int numPaths, 
            UnManagedExportMask exportMask) {
        String groupName = getExportGroupName(vplex, array) 
                + "_" + UUID.randomUUID().toString().substring(28);
        if (exportMask != null) {
            String arrayName = array.getSystemType().replace("block", "") 
                    + array.getSerialNumber().substring(array.getSerialNumber().length()-4);
            groupName = exportMask.getMaskName() + "_" + arrayName;
        }
        
        // No existing group has the mask, let's create one.
        ExportGroup exportGroup = new ExportGroup();
        exportGroup.setId(URIUtil.createId(ExportGroup.class));
        exportGroup.setLabel(groupName);
        exportGroup.setProject(new NamedURI(projectURI, exportGroup.getLabel()));
        exportGroup.setVirtualArray(vplex.getVirtualArray());
        exportGroup.setTenant(new NamedURI(tenantURI, exportGroup.getLabel()));
        exportGroup.setGeneratedName(groupName);
        exportGroup.setVolumes(new StringMap());
        exportGroup.setOpStatus(new OpStatusMap());
        exportGroup.setVirtualArray(virtualArrayURI);
        exportGroup.setNumPaths(numPaths);

        // Add the initiators into the ExportGroup.
        for (Initiator initiator : initiators) {
            exportGroup.addInitiator(initiator);
        }

//        // If we have an Export Mask, add it into the Export Group.
//        if (exportMask != null) {
//            exportGroup.addExportMask(exportMask.getId());
//        }
        
        // Persist the ExportGroup
        _dbClient.createObject(exportGroup);  // TODO probably want to be able to roll back
        _logger.info(String.format("Returning new ExportGroup %s", exportGroup.getLabel()));
        return exportGroup;
        
    }
    
    /**
     * Returns the ExportGroup name to be used between a particular VPlex and underlying Storage Array.
     * It is based on the serial numbers of the Vplex and Array. Therefore the same ExportGroup name
     * will always be used, and it always starts with "VPlex".
     * @param vplex
     * @param array
     * @return
     */
    private String getExportGroupName(StorageSystem vplex, StorageSystem array) {
        // Unfortunately, using the full VPlex serial number with the Array serial number
        // proves to be quite lengthy! We can run into issues on SMIS where
        // max length (represented as STOR_DEV_GROUP_MAX_LEN) is 64 characters.
        // Not to mention, other steps append to this name too.
        // So lets chop everything but the last 4 digits from both serial numbers.
        // This should be unique enough.
        int endIndex = vplex.getSerialNumber().length();
        int beginIndex = endIndex - 4;
        String modfiedVPlexSerialNumber = vplex.getSerialNumber().substring(beginIndex, endIndex);
        
        endIndex = array.getSerialNumber().length();
        beginIndex = endIndex - 4;
        String modfiedArraySerialNumber = array.getSerialNumber().substring(beginIndex, endIndex);
        
        return String.format("VPlex_%s_%s", modfiedVPlexSerialNumber, modfiedArraySerialNumber);
    }

    @Override
    protected void updateBlockObjectNativeIds(BlockObject blockObject, UnManagedVolume unManagedVolume) {
        String label = unManagedVolume.getLabel();
        blockObject.setDeviceLabel(label);
        blockObject.setLabel(label);
        blockObject.setNativeId(blockObject.getNativeGuid());
    }

    @Override
    protected URI getConsistencyGroupUri(UnManagedVolume unManagedVolume, VirtualPool vPool, URI project, URI tenant,
            URI virtualArray, DbClient dbClient) {
        return VolumeIngestionUtil.getVplexConsistencyGroup(unManagedVolume, vPool, project, tenant, virtualArray, dbClient);
    }

    @Override
    protected void updateCGPropertiesInVolume(URI consistencyGroupUri, Volume volume, StorageSystem system,
            UnManagedVolume unManagedVolume) {
        if (consistencyGroupUri != null) {

            String cgName = PropertySetterUtil.extractValueFromStringSet(
                    SupportedVolumeInformation.VPLEX_CONSISTENCY_GROUP_NAME.toString(), unManagedVolume.getVolumeInformation());

            BlockConsistencyGroup cg = _dbClient.queryObject(BlockConsistencyGroup.class, consistencyGroupUri);

            StringSet unmanagedVolumeClusters = unManagedVolume.getVolumeInformation().get(
                    SupportedVolumeInformation.VPLEX_CLUSTER_IDS.toString());
            // Add a ViPR CG mapping for each of the VPlex clusters the VPlex CG
            // belongs to.
            if (unmanagedVolumeClusters != null && !unmanagedVolumeClusters.isEmpty()) {
                Iterator<String> unmanagedVolumeClustersItr = unmanagedVolumeClusters.iterator();
                while (unmanagedVolumeClustersItr.hasNext()) {
                    cg.addSystemConsistencyGroup(system.getId().toString(),
                            BlockConsistencyGroupUtils.buildClusterCgName(unmanagedVolumeClustersItr.next(), cgName));
                }

                _dbClient.updateAndReindexObject(cg);
            }

            volume.setConsistencyGroup(consistencyGroupUri);
        }
    }

    @Override
    protected void setProtocol(StoragePool pool, Volume volume, VirtualPool vPool) {
        if (null == volume.getProtocol()) {
            volume.setProtocol(new StringSet());
        }
        volume.getProtocol().addAll(vPool.getProtocols());
    }

    @Override
    protected StoragePool validateAndReturnStoragePoolInVAarray(UnManagedVolume unManagedVolume, VirtualArray virtualArray) {
        return null;
    }

    @Override
    protected void checkSystemResourceLimitsExceeded(StorageSystem system, UnManagedVolume unManagedVolume, List<URI> systemCache) {
        // always return true,as for vplex volumes this limit doesn't have any effect
        return;
    }

    @Override
    protected void checkPoolResourceLimitsExceeded(StorageSystem system, StoragePool pool, UnManagedVolume unManagedVolume,
            List<URI> poolCache) {
        // always return true, as pool will be null
        return;
    }

    @Override
    protected void checkUnManagedVolumeAddedToCG(UnManagedVolume unManagedVolume, VirtualArray virtualArray, TenantOrg tenant,
            Project project, VirtualPool vPool) {
        if (VolumeIngestionUtil.checkUnManagedResourceAddedToConsistencyGroup(unManagedVolume)) {
            URI consistencyGroupUri = VolumeIngestionUtil.getVplexConsistencyGroup(unManagedVolume, vPool, project.getId(),
                    tenant.getId(), virtualArray.getId(), _dbClient);
            if (null == consistencyGroupUri) {
                _logger.warn("A Consistency Group for the VPLEX volume could not be determined. Skipping Ingestion.");
                throw IngestionException.exceptions.unmanagedVolumeVplexConsistencyGroupCouldNotBeIdentified(unManagedVolume.getLabel());
            }

        }
    }

    @Override
    protected void validateAutoTierPolicy(String autoTierPolicyId, UnManagedVolume unManagedVolume, VirtualPool vPool) {
        super.validateAutoTierPolicy(autoTierPolicyId, unManagedVolume, vPool);
    }
    
}
