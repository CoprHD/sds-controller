/*
 * Copyright (c) 2008-2013 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.db.server.upgrade.util.models.updated;

import com.emc.storageos.db.client.model.Cf;
import com.emc.storageos.db.client.model.DataObject;
import com.emc.storageos.db.client.model.Name;
import java.net.URI;

@Cf("Resource3")
public class Resource3 extends DataObject {
    private URI res4;
    private Long extraFlags;

    @Name("extraFlags")
    public Long getExtraFlags() {
        return extraFlags;
    }

    public void setExtraFlags(Long extraFlags) {
        this.extraFlags = extraFlags;
        setChanged("extraFlags");
    }

    @Name("res4")
    public URI getRes4() {
        return res4;
    }

    public void setRes4(URI res4) {
        this.res4 = res4;
        setChanged("res4");
    }
}
