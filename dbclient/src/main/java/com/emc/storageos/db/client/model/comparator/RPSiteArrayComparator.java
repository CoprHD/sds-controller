/*
 * Copyright (c) 2013 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.db.client.model.comparator;

import java.net.URI;
import java.util.Comparator;

import com.emc.storageos.db.client.model.RPSiteArray;

/**
 * Comparator class for mainly used for determining equality between <code>RPSiteArray</code> objects. The URI will be used for
 * equality comparison.
 * <p>
 * Please note that this class is being constructed and used because the impact of overriding .hashCode and .equals in
 * <code>RPSiteArray</code> and <code>DataObject</code> was not clearly understood.
 */
public class RPSiteArrayComparator implements Comparator<RPSiteArray> {

    @Override
    public int compare(RPSiteArray o1, RPSiteArray o2) {
        URI id1 = o1.getId();
        URI id2 = o2.getId();
        if (id1 == null) {
            return id2 == null ? 0 : 1;
        }
        if (id2 == null) {
            return -1;
        }
        return id1.compareTo(id2);
    }

}
