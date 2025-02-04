/*
 * Copyright (c) 2013 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.model.vpool;

import java.net.URI;

import javax.xml.bind.annotation.XmlElement;

public class VirtualPoolProtectionMirrorParam {

    public final static int MAX_DISABLED = 0;

    private Integer maxMirrors;
    private URI vpool;
    private Integer haMaxMirrors;
    private URI haVpool;

    public VirtualPoolProtectionMirrorParam() {
    }

    public VirtualPoolProtectionMirrorParam(Integer maxMirrors, URI vpool) {
        this.maxMirrors = maxMirrors;
        this.vpool = vpool;
    }

    /**
     * The maximum number of continuous copies for a virtual pool.
     * 
     */
    @XmlElement(name = "max_native_continuous_copies", required = false)
    public Integer getMaxMirrors() {
        return maxMirrors;
    }

    public void setMaxMirrors(Integer maxMirrors) {
        this.maxMirrors = maxMirrors;
    }

    /**
     * The virtual pool for protection mirrors.
     * 
     */
    @XmlElement(name = "protection_mirror_vpool", required = false)
    public URI getVpool() {
        return vpool;
    }

    public void setVpool(URI vpool) {
        this.vpool = vpool;
    }

    @XmlElement(name = "ha_max_native_continuous_copies", required = false)
    public Integer getHaMaxMirrors() {
        return haMaxMirrors;
    }

    /**
     * The maximum number of continuous copies for a High Availability virtual pool.
     * 
     */
    public void setHaMaxMirrors(Integer haMaxMirrors) {
        this.haMaxMirrors = haMaxMirrors;
    }

    /**
     * The virtual pool for protection mirrors on the High Availability side.
     * 
     */
    @XmlElement(name = "ha_protection_mirror_vpool", required = false)
    public URI getHaVpool() {
        return haVpool;
    }

    public void setHaVpool(URI haVpool) {
        this.haVpool = haVpool;
    }

}
