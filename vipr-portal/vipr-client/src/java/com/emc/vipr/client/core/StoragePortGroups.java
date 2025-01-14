/*
 * Copyright (c) 2018 Dell-EMC Corporation
 * All Rights Reserved
 */
package com.emc.vipr.client.core;

import java.net.URI;

import com.emc.storageos.model.portgroup.StoragePortGroupCreateParam;
import com.emc.storageos.model.portgroup.StoragePortGroupList;
import com.emc.storageos.model.portgroup.StoragePortGroupRestRep;
import com.emc.vipr.client.Task;
import com.emc.vipr.client.ViPRCoreClient;
import com.emc.vipr.client.core.impl.PathConstants;
import com.emc.vipr.client.impl.RestClient;

/**
 * Storage port groups resource.
 * <p>
 * Base URL: <tt>/vdc/storage-systems/{storageSystemUri}/storage-port-groups</tt>
 */
public class StoragePortGroups extends AbstractResources<StoragePortGroupRestRep> {
    public StoragePortGroups(ViPRCoreClient parent, RestClient client) {
        super(client, StoragePortGroupRestRep.class, PathConstants.STORAGE_PORT_GROUP_URL);
    }

    /**
     * Create a storage port group
     * <p>
     * API call: <tt>POST /vdc/storage-systems/{storageSystemUri}/storage-port-groups</tt>
     * </p>
     * 
     * @param storageUri
     *            the storage system URI
     * @param input
     *            the storage port group creation params
     * @return the new storage port group
     */
    public Task<StoragePortGroupRestRep> create(URI storageUri, StoragePortGroupCreateParam input) {
        return postTask(input, baseUrl, storageUri);
    }

    /**
     * Register a storage port group.
     * <p>
     * API Call: <tt>POST /vdc/storage-systems/{storageSystemId}/storage-port-groups/{portGroupUri}/register</tt>
     * 
     * @param portGroupUri
     *            the URI of the storage port group.
     * @param storageUri
     *            the URI of the storage system.
     * @return the updated storage port group.
     */
    public StoragePortGroupRestRep register(URI portGroupUri, URI storageUri) {
        String registerUrl = PathConstants.STORAGE_PORT_GROUP_URL + "/{portgroupUri}/register";
        return client.post(StoragePortGroupRestRep.class, registerUrl, storageUri, portGroupUri);
    }

    /**
     * Deregister a storage port group.
     * <p>
     * API Call: <tt>POST /vdc/storage-systems/{storageSystemId}/storage-port-groups/{portGroupUri}/deregister</tt>
     * 
     * @param portGroupUri
     *            the URI of the storage port group.
     * @param storageUri
     *            the URI of the storage system.
     * @return the updated storage port group.
     */
    public StoragePortGroupRestRep deregister(URI portGroupUri, URI storageUri) {
        String deregisterUrl = PathConstants.STORAGE_PORT_GROUP_URL + "/{portgroupUri}/deregister";
        return client.post(StoragePortGroupRestRep.class, deregisterUrl, storageUri, portGroupUri);
    }

    /**
     * Delete a storage port group.
     * <p>
     * API Call: <tt>POST /vdc/storage-systems/{storageSystemId}/storage-port-groups/{portGroupUri}/deactivate</tt>
     * 
     * @param portGroupUri
     *            the URI of the storage port group.
     * @param storageUri
     *            the URI of the storage system.
     * @return the updated storage port group.
     */
    public Task<StoragePortGroupRestRep> delete(URI portGroupUri, URI storageUri) {
        String deleteUrl = PathConstants.STORAGE_PORT_GROUP_URL + "/{portgroupUri}/deactivate";
        return postTask(deleteUrl, storageUri, portGroupUri);
    }

    /**
     * Get a storage port group.
     * <p>
     * API Call: <tt>Get /vdc/storage-systems/{storageSystemUri}/storage-port-groups/{portGroupUri}</tt>
     * 
     * @param portGroupUri
     *            the URI of the storage port group.
     * @param storageUri
     *            the URI of the storage system.
     * @return the storage port group.
     */
    public StoragePortGroupRestRep get(URI portGroupUri, URI storageUri) {
        String getUrl = PathConstants.STORAGE_PORT_GROUP_URL + "/{portgroupUri}";
        return client.get(StoragePortGroupRestRep.class, getUrl, storageUri, portGroupUri);
    }

    /**
     * Get all storage port group in the given storage system.
     * <p>
     * API Call: <tt>Get /vdc/storage-systems/{storageSystemId}/storage-port-groups/</tt>
     * 
     * @param storageUri
     *            the URI of the storage system.
     * @return the list of storage port groups.
     */
    public StoragePortGroupList getAll(URI storageUri) {
        return client.get(StoragePortGroupList.class, baseUrl, storageUri);
    }
}