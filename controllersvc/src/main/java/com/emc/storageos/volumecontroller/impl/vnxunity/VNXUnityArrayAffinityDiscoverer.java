/*
 * Copyright (c) 2016 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.volumecontroller.impl.vnxunity;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.constraint.ContainmentConstraint;
import com.emc.storageos.db.client.constraint.URIQueryResultList;
import com.emc.storageos.db.client.model.ExportGroup;
import com.emc.storageos.db.client.model.Host;
import com.emc.storageos.db.client.model.Initiator;
import com.emc.storageos.db.client.model.StoragePool;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.util.NullColumnValueGetter;
import com.emc.storageos.plugins.AccessProfile;
import com.emc.storageos.plugins.common.Constants;
import com.emc.storageos.plugins.common.PartitionManager;
import com.emc.storageos.util.NetworkUtil;
import com.emc.storageos.vnxe.VNXeApiClient;
import com.emc.storageos.vnxe.VNXeApiClientFactory;
import com.emc.storageos.vnxe.models.BlockHostAccess;
import com.emc.storageos.vnxe.models.VNXeBase;
import com.emc.storageos.vnxe.models.VNXeHost;
import com.emc.storageos.vnxe.models.VNXeHostInitiator;
import com.emc.storageos.vnxe.models.VNXeLun;
import com.emc.storageos.volumecontroller.impl.NativeGUIDGenerator;
import com.emc.storageos.volumecontroller.impl.plugins.discovery.smis.processor.export.ArrayAffinityDiscoveryUtils;
import com.emc.storageos.volumecontroller.impl.utils.DiscoveryUtils;

/**
 * Discover array affinity
 *
 */
public class VNXUnityArrayAffinityDiscoverer {

    private static final Logger logger = LoggerFactory.getLogger(VNXUnityArrayAffinityDiscoverer.class);
    private static final String HOST = "Host";
    private static final int BATCH_SIZE = 100;

    private VNXeApiClientFactory vnxeApiClientFactory;

    public void setVnxeApiClientFactory(VNXeApiClientFactory vnxeApiClientFactory) {
        this.vnxeApiClientFactory = vnxeApiClientFactory;
    }

    /**
     * Discover array affinity
     * @param accessProfile AccesssProfile
     * @param dbClient DbClient
     * @param partitionManager PartitionManager
     * @throws Exception
     */
    public void discoverArrayAffinity(AccessProfile accessProfile, DbClient dbClient,
            PartitionManager partitionManager) throws Exception {
        logger.info("Started array affinity discovery for system {}", accessProfile.getSystemId());
        VNXeApiClient apiClient = vnxeApiClientFactory.getUnityClient(accessProfile.getIpAddress(),
                accessProfile.getPortNumber(), accessProfile.getUserName(),
                accessProfile.getPassword());

        StorageSystem system = dbClient.queryObject(StorageSystem.class, accessProfile.getSystemId());

        String hostIdStr = accessProfile.getProps().get(Constants.HOST);
        if (hostIdStr != null) {
            // array affinity for a host
            logger.info("Processing host {}", hostIdStr);
        } else {
            // array affinity for all hosts
            processAllHosts(system, apiClient, dbClient, partitionManager);
        }
    }

    /**
     * Process all hosts in ViPR DB
     * @param system
     * @param apiClient
     * @param dbClient
     * @param partitionManager
     */
    private void processAllHosts(StorageSystem system, VNXeApiClient apiClient, DbClient dbClient, PartitionManager partitionManager) {
        Map<URI, List<String>> hostToVolumesMap = new HashMap<URI, List<String>>();
        Map<String, URI> volumeToPoolMap = new HashMap<String, URI>();
        Map<String, URI> hostIdToHostURIMap = new HashMap<String, URI>();
        Map<String, Set<URI>> volumeToHostsMap = new HashMap<String, Set<URI>>();

        Set<String> serialIds = new HashSet<String>();
        serialIds.add(system.getSerialNumber());

        try {
            processAllLuns(system, apiClient, dbClient, hostToVolumesMap, volumeToHostsMap, volumeToPoolMap, hostIdToHostURIMap);

            List<Host> hostsToUpdate = new ArrayList<Host>();
            List<URI> hostURIs = dbClient.queryByType(Host.class, true);
            Iterator<Host> hosts = dbClient.queryIterativeObjectFields(Host.class, ArrayAffinityDiscoveryUtils.HOST_PROPERTIES, hostURIs);
            while (hosts.hasNext()) {
                Host host = hosts.next();
                if (host != null && !host.getInactive()) {
                    logger.info("Processing host {}", host.getLabel());
                    Map<String, String> preferredPoolMap = new HashMap<String, String>();

                    // check volumes
                    List<String> volumes = hostToVolumesMap.get(host.getId());
                    if (volumes != null && !volumes.isEmpty()) {
                        for (String volume : volumes) {
                            URI pool = volumeToPoolMap.get(volume);

                            if (pool != null) {
                                String exportType = volumeToHostsMap.get(volume).size() > 1 ? ExportGroup.ExportGroupType.Cluster.name()
                                        : ExportGroup.ExportGroupType.Host.name();
                                ArrayAffinityDiscoveryUtils.addPoolToPreferredPoolMap(preferredPoolMap, pool.toString(), exportType);
                            }
                        }
                    }

                    if (ArrayAffinityDiscoveryUtils.updatePreferredPools(host, serialIds, dbClient, preferredPoolMap)) {
                        hostsToUpdate.add(host);
                    }
                }

                // if hostsToUpdate size reaches BATCH_SIZE, persist to db
                if (hostsToUpdate.size() >= BATCH_SIZE) {
                    partitionManager.updateInBatches(hostsToUpdate, BATCH_SIZE, dbClient, HOST);
                    hostsToUpdate.clear();
                }
            }

            if (!hostsToUpdate.isEmpty()) {
                partitionManager.updateInBatches(hostsToUpdate, BATCH_SIZE, dbClient, HOST);
            }
        } catch (Exception e) {
            logger.warn("Exception on processAllHosts", e);
        }
    }

    /**
     * Discover array affinity via LUNs
     *
     * @param system
     * @param apiClient
     * @param dbClient
     * @param hostToVolumesMap
     * @param volumeToHostsMap
     * @param volumeToPoolMap
     * @param hostIdToHostURIMap
     * @throws Exception
     */
    private void processAllLuns(StorageSystem system, VNXeApiClient apiClient, DbClient dbClient,
            Map<URI, List<String>> hostToVolumesMap, Map<String, Set<URI>> volumeToHostsMap, Map<String, URI> volumeToPoolMap,
            Map<String, URI> hostIdToHostURIMap) throws Exception {
        List<VNXeLun> luns = apiClient.getAllLuns();
        if (luns != null && !luns.isEmpty()) {
            Map<String, StoragePool> pools = getStoragePoolMap(system, dbClient);
            for (VNXeLun lun : luns) {
                String nativeGuid = NativeGUIDGenerator.generateNativeGuidForVolumeOrBlockSnapShot(
                        system.getNativeGuid(), lun.getId());
                if (DiscoveryUtils.checkStorageVolumeExistsInDB(dbClient, nativeGuid) != null) {
                    logger.info("Skipping volume {} as it is already managed by ViPR", nativeGuid);
                }

                StoragePool pool = getStoragePoolOfUnManagedObject(lun.getPool().getId(), system, pools);
                if (pool != null) {
                    // the Lun belong to a ViPR host
                    Set<URI> hostURIs = getHostURIs(lun, apiClient, dbClient, hostIdToHostURIMap);
                    volumeToHostsMap.put(lun.getId(), hostURIs);

                    for (URI hostURI : hostURIs) {
                        List<String> volumes = hostToVolumesMap.get(hostURI);
                        if (volumes == null) {
                            volumes = new ArrayList<String>();
                            hostToVolumesMap.put(hostURI, volumes);
                        }
                        volumes.add(lun.getId());
                    }

                    volumeToPoolMap.put(lun.getId(), pool.getId());
                } else {
                    logger.error("Skipping volume {} as its storage pool doesn't exist in ViPR",
                            lun.getId());
                }
            }
        } else {
            logger.info("No luns found on the system: {}", system.getId());
        }
    }

    /**
     * Find host URIs that a LUN is exported to
     *
     * @param lun
     * @param apiClient
     * @param dbClient
     * @param hostIdToHostURIMap
     * @return set of host URIs
     */
    private Set<URI> getHostURIs(VNXeLun lun, VNXeApiClient apiClient, DbClient dbClient, Map<String, URI> hostIdToHostURIMap) {
        Set<URI> hostURIs = new HashSet<URI>();
        List<BlockHostAccess> accesses = lun.getHostAccess();
        if (accesses != null && !accesses.isEmpty()) {
            for (BlockHostAccess access : accesses) {
                if (access != null) {
                    VNXeBase hostId = access.getHost();
                    if (hostId != null) {
                        hostURIs.add(getHostURI(apiClient, hostId.getId(), dbClient, hostIdToHostURIMap));
                    }
                }
            }
        }

        return hostURIs;
    }

    /**
     * Find host URI from host Id on array
     *
     * @param apiClient
     * @param hostId
     * @param dbClient
     * @param hostIdToHostURIMap
     * @return host URI or NULL_URI
     */
    private URI getHostURI(VNXeApiClient apiClient, String hostId, DbClient dbClient, Map<String, URI> hostIdToHostURIMap) {
        if (hostIdToHostURIMap.containsKey(hostId)) {
            return hostIdToHostURIMap.get(hostId);
        }

        VNXeHost vnxeHost = apiClient.getHostById(hostId);
        URI hostURI = findHostURI(vnxeHost.getFcHostInitiators(), apiClient, dbClient);
        if (hostURI == null) {
            hostURI = findHostURI(vnxeHost.getIscsiHostInitiators(), apiClient, dbClient);
        }

        if (hostURI == null) {
            hostURI = NullColumnValueGetter.getNullURI();
        }

        hostIdToHostURIMap.put(hostId, hostURI);
        return hostURI;
    }

    /**
     * Find host URI from host initiators on array
     *
     * @param initiators
     * @param apiClient
     * @param dbClient
     * @return host URI or null
     */
    private URI findHostURI(List<VNXeBase> initiators, VNXeApiClient apiClient, DbClient dbClient) {
        if (initiators != null && !initiators.isEmpty()) {
            for (VNXeBase init : initiators) {
                VNXeHostInitiator vnxeInitiator = apiClient.getHostInitiator(init.getId());
                String portwwn = vnxeInitiator.getPortWWN();
                if (portwwn == null || portwwn.isEmpty()) {
                    continue;
                }

                Initiator initiator = NetworkUtil.getInitiator(portwwn, dbClient);
                if (initiator != null && !initiator.getInactive()) {
                    URI hostURI = initiator.getHost();
                    if (!NullColumnValueGetter.isNullURI(hostURI)) {
                        return hostURI;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Construct pool's native GUID to storage pool object map
     *
     * @param storageSystem
     * @param dbClient
     * @return map of pool's native GUID to storage pool object
     */
    private Map<String, StoragePool> getStoragePoolMap(StorageSystem storageSystem, DbClient dbClient) {
        URIQueryResultList storagePoolURIs = new URIQueryResultList();
        dbClient.queryByConstraint(ContainmentConstraint.Factory
                .getStorageDeviceStoragePoolConstraint(storageSystem.getId()),
                storagePoolURIs);
        HashMap<String, StoragePool> pools = new HashMap<String, StoragePool>();
        Iterator<URI> poolsItr = storagePoolURIs.iterator();
        while (poolsItr.hasNext()) {
            URI storagePoolURI = poolsItr.next();
            StoragePool storagePool = dbClient.queryObject(StoragePool.class, storagePoolURI);
            pools.put(storagePool.getNativeGuid(), storagePool);
        }

        return pools;
    }

    /**
     * Return the pool of the UnManaged volume.
     *
     * @param storageResource
     * @param system
     * @param dbClient
     * @return storage pool
     * @throws IOException
     */
    private StoragePool getStoragePoolOfUnManagedObject(String poolNativeId,
            StorageSystem system, Map<String, StoragePool> pools) throws IOException {
        String poolNativeGuid = NativeGUIDGenerator.generateNativeGuid(system, poolNativeId, NativeGUIDGenerator.POOL);
        if (pools.containsKey(poolNativeGuid)) {
            return pools.get(poolNativeGuid);
        }
        return null;
    }
}
