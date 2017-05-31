/*
 * Copyright (c) 2012-2015 iWave Software LLC
 * All Rights Reserved
 */
package com.emc.sa.service.vmware.block;

import java.net.URI;
import java.util.List;
import java.util.Set;

import com.emc.sa.engine.bind.Bindable;
import com.emc.sa.engine.service.Service;
import com.emc.sa.machinetags.vmware.VMwareDatastoreTagger;
import com.emc.sa.service.vipr.block.BlockStorageUtils;
import com.emc.sa.service.vipr.block.ExportBlockVolumeHelper;
import com.emc.sa.service.vipr.block.ExportVMwareBlockVolumeHelper;
import com.emc.sa.service.vmware.VMwareHostService;
import com.emc.sa.service.vmware.block.tasks.MountDatastore;
import com.emc.storageos.model.block.BlockObjectRestRep;
import com.iwave.ext.vmware.VMwareUtils;
import com.vmware.vim25.mo.Datastore;

@Service("VMware-ExportVolume")
public class ExportVMwareVolumeService extends VMwareHostService {

    @Bindable
    protected ExportBlockVolumeHelper helper = new ExportVMwareBlockVolumeHelper();

    @Override
    public boolean checkClusterConnectivity() {
        return false;
    }

    @Override
    public void precheck() throws Exception {
        vmware.disconnect();
        helper.precheck();
    }

    @Override
    public void execute() throws Exception {
        helper.exportVolumes();
        setVmfsDatastoreTag(helper.getVolumeIds(), helper.getHostId());
        this.connectAndInitializeHost();
        vmware.refreshStorage(host, cluster);
        vmware.attachLuns(host, uris(helper.getVolumeIds()));
        host.getHostStorageSystem().rescanVmfs();
        mountDatastores(helper.getVolumeIds());
    }

    private void mountDatastores(List<String> volumeIds) {
        for (String volumeId : volumeIds) {
            BlockObjectRestRep volume = BlockStorageUtils.getVolume(uri(volumeId));
            Set<String> datastoreNames = VMwareDatastoreTagger.getDatastoreNames(volume);

            for (String datastoreName : datastoreNames) {
                Datastore datastore = vmware.getDatastore(datacenter.getLabel(), datastoreName);
                if (datastore != null && !VMwareUtils.isDatastoreMountedOnHost(datastore, host)) {
                    execute(new MountDatastore(host, datastore));
                }
            }
        }
    }

    private void setVmfsDatastoreTag(List<String> volumeIds, URI hostId) {
        for (String volumeId : volumeIds) {
            BlockObjectRestRep volume = BlockStorageUtils.getVolume(uri(volumeId));
            Set<String> datastoreNames = VMwareDatastoreTagger.getDatastoreNames(volume);

            for (String datastoreName : datastoreNames) {
                vmware.addVmfsDatastoreTag(volume, hostId, datastoreName);
            }
        }
    }
}
