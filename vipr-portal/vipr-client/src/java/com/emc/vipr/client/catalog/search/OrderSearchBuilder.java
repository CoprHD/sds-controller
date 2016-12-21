/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.vipr.client.catalog.search;

import static com.emc.vipr.client.catalog.impl.SearchConstants.END_TIME_PARAM;
import static com.emc.vipr.client.catalog.impl.SearchConstants.ORDER_COUNT_ONLY;
import static com.emc.vipr.client.catalog.impl.SearchConstants.ORDER_STATUS_PARAM;
import static com.emc.vipr.client.catalog.impl.SearchConstants.START_TIME_PARAM;
import static com.emc.vipr.client.catalog.impl.SearchConstants.TENANT_ID_PARAM;
import static com.emc.vipr.client.catalog.impl.SearchConstants.ORDER_MAX_COUNT;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import com.emc.vipr.client.core.AbstractResources;
import com.emc.vipr.client.core.search.SearchBuilder;
import com.emc.vipr.model.catalog.OrderRestRep;

public class OrderSearchBuilder extends SearchBuilder<OrderRestRep> {

    public OrderSearchBuilder(AbstractResources<OrderRestRep> resources) {
        super(resources);
    }

    public SearchBuilder<OrderRestRep> byStatus(String status) {
        return byStatus(status, null);
    }

    public SearchBuilder<OrderRestRep> byStatus(String status, URI tenantId) {
        Map<String, Object> parameters = new HashMap<String, Object>();
        if (tenantId != null) {
            parameters.put(TENANT_ID_PARAM, tenantId);
        }
        parameters.put(ORDER_STATUS_PARAM, status);
        return byAll(parameters);
    }

    public SearchBuilder<OrderRestRep> byTimeRange(String start, String end) {
        return byTimeRange(start, end, null, -1);
    }

    public SearchBuilder<OrderRestRep> byTimeRange(String start, String end, URI tenantId, long maxCount) {
        Map<String, Object> parameters = new HashMap<String, Object>();
        if (tenantId != null) {
            parameters.put(TENANT_ID_PARAM, tenantId);
        }
        if (start != null) {
            parameters.put(START_TIME_PARAM, start);
        }
        if (end != null) {
            parameters.put(END_TIME_PARAM, end);
        }

        parameters.put(ORDER_MAX_COUNT, maxCount);
        return byAll(parameters);
    }

}
