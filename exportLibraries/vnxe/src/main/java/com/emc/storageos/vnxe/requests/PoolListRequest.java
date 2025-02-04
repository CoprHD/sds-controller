/*
 * Copyright (c) 2014 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.vnxe.requests;

import java.util.List;

import com.emc.storageos.vnxe.models.VNXePool;

public class PoolListRequest extends KHRequests<VNXePool> {
    private static final String URL = "/api/types/pool/instances";
    private static final String FIELDS = "raidType,tiers,sizeTotal,sizeSubscribed,sizeFree,name,isEmpty,poolFastVP,isFASTCacheEnabled,health";
    private static final String FIELDS_NOFASTVP = "raidType,tiers,sizeTotal,sizeSubscribed,sizeFree,name,isEmpty,health";
    
    public PoolListRequest(KHClient client) {
        super(client);
        _url = URL ;
        if (client.isFastVPEnabled()) {
            _fields = FIELDS;
        } else {
            _fields = FIELDS_NOFASTVP;
        }
    }

    public List<VNXePool> get() {
        return getDataForObjects(VNXePool.class);

    }

}
