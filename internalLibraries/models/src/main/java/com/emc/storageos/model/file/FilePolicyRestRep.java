/*
 * Copyright (c) 2016 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.model.file;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;

import com.emc.storageos.model.schedulepolicy.SchedulePolicyRestRep;

/**
 * Schedule Policy and returned as a response to a REST request.
 * 
 * @author prasaa9
 * 
 */

@XmlRootElement(name = "file_snapshot_schedule_policy")
@XmlType(name = "file_snapshot_schedule_policy")
public class FilePolicyRestRep extends SchedulePolicyRestRep {

    // Type of the policy
    private String snapshotPattern;

    @XmlElement(name = "snapshot_pattern")
    public String getSnapshotPattern() {
        return snapshotPattern;
    }

    public void setSnapshotPattern(String snapshotPattern) {
        this.snapshotPattern = snapshotPattern;
    }

}
