/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.volumecontroller.impl.block.taskcompleter;

import java.net.URI;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.model.BlockMirror;
import com.emc.storageos.db.client.model.Operation;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.exceptions.DeviceControllerException;
import com.emc.storageos.services.OperationTypeEnum;
import com.emc.storageos.svcs.errorhandling.model.ServiceCoded;
import com.google.common.base.Joiner;

public class BlockMirrorCreateCompleter extends BlockMirrorTaskCompleter {
    private static final Logger _log = LoggerFactory.getLogger(BlockMirrorCreateCompleter.class);
    public static final String MIRROR_CREATED_MSG = "Mirror %s created for volume %s";
    public static final String MIRROR_CREATE_FAILED_MSG = "Failed to create mirror %s for volume %s";

    public BlockMirrorCreateCompleter(URI mirror, String opId) {
        super(BlockMirror.class, mirror, opId);
    }

    public BlockMirrorCreateCompleter(List<URI> mirrorList, String opId) {
        super(BlockMirror.class, mirrorList, opId);
    }

    @Override
    protected void complete(DbClient dbClient, Operation.Status status, ServiceCoded coded) throws DeviceControllerException {
        try {
            super.complete(dbClient, status, coded);
            List<BlockMirror> mirrorList = dbClient.queryObject(BlockMirror.class, getIds());
            for (BlockMirror mirror : mirrorList) {
                Volume volume = dbClient.queryObject(Volume.class, mirror.getSource());
                switch (status) {
                    case error:
                        mirror.setInactive(true);
                        dbClient.persistObject(mirror);
                        removeMirrorFromVolume(mirror.getId(), volume, dbClient);
                        dbClient.error(BlockMirror.class, mirror.getId(), getOpId(), coded);
                        dbClient.error(Volume.class, volume.getId(), getOpId(), coded);
                        break;
                    default:
                        dbClient.ready(BlockMirror.class, mirror.getId(), getOpId());
                        dbClient.ready(Volume.class, volume.getId(), getOpId());
                }
                recordBlockMirrorOperation(dbClient, OperationTypeEnum.CREATE_VOLUME_MIRROR,
                        status, eventMessage(status, volume, mirror), mirror, volume);
            }
        } catch (Exception e) {
            _log.error("Failed updating status. BlockMirrorCreate {}, for task " + getOpId(), Joiner.on("\t").join(getIds()), e);
        }
    }

    private String eventMessage(Operation.Status status, Volume volume, BlockMirror mirror) {
        return (Operation.Status.ready == status) ?
                String.format(MIRROR_CREATED_MSG, mirror.getLabel(), volume.getLabel()) :
                String.format(MIRROR_CREATE_FAILED_MSG, mirror.getLabel(), volume.getLabel());
    }

    private void removeMirrorFromVolume(URI mirrorId, Volume volume, DbClient dbClient) {
        StringSet mirrors = volume.getMirrors();
        if (mirrors == null) {
            _log.warn("Removing mirror {} from volume {} which had no mirrors", mirrorId, volume);
            return;
        }
        mirrors.remove(mirrorId.toString());
        volume.setMirrors(mirrors);
        // Persist changes
        dbClient.persistObject(volume);
    }

}
