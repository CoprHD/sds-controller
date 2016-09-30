/*
 * Copyright (c) 2016 EMC Corporation
 * All Rights Reserved
 *
 * This software contains the intellectual property of EMC Corporation
 * or is licensed to EMC Corporation from third parties.  Use of this
 * software and the intellectual property contained therein is expressly
 * limited to the terms and conditions of the License Agreement under which
 * it is provided by or on behalf of EMC.
 */

package com.emc.storageos.driver.ibmsvcdriver.helpers;

import com.emc.storageos.driver.ibmsvcdriver.api.*;
import com.emc.storageos.driver.ibmsvcdriver.connection.ConnectionManager;
import com.emc.storageos.driver.ibmsvcdriver.connection.SSHConnection;
import com.emc.storageos.driver.ibmsvcdriver.exceptions.IBMSVCDriverException;
import com.emc.storageos.driver.ibmsvcdriver.impl.IBMSVCDriverTask;
import com.emc.storageos.driver.ibmsvcdriver.impl.IBMSVCStorageDriver;
import com.emc.storageos.driver.ibmsvcdriver.utils.IBMSVCConstants;
import com.emc.storageos.driver.ibmsvcdriver.utils.IBMSVCDriverUtils;
import com.emc.storageos.driver.ibmsvcdriver.utils.IBMSVCHelper;
import com.emc.storageos.storagedriver.DriverTask;
import com.emc.storageos.storagedriver.model.Initiator;
import com.emc.storageos.storagedriver.model.StorageObject;
import com.emc.storageos.storagedriver.model.StoragePort;
import com.emc.storageos.storagedriver.model.StorageVolume;
import com.emc.storageos.storagedriver.storagecapabilities.StorageCapabilities;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.mutable.MutableBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class IBMSVCProvisioning {


    private static final Logger _log = LoggerFactory.getLogger(IBMSVCStorageDriver.class);

    /*
     * Connection Manager for managing connection pool
     */
    private ConnectionManager connectionManager = null;

    /**
     * Constructor
     */
    public IBMSVCProvisioning() {
        this.connectionManager = ConnectionManager.getInstance();
    }

    /**
     * Create driver task for task type
     *
     * @param taskType
     */
    public DriverTask createDriverTask(String taskType) {
        String taskID = String.format("%s+%s+%s", IBMSVCConstants.DRIVER_NAME, taskType, UUID.randomUUID());
        DriverTask task = new IBMSVCDriverTask(taskID);
        return task;
    }

    /**
     * Create storage volumes with a given set of capabilities. Before
     * completion of the request, set all required data for provisioned volumes
     * in "volumes" parameter.
     *
     * @param volumes
     *            Input/output argument for volumes.
     * @param capabilities
     *            Input argument for capabilities. Defines storage capabilities
     *            of volumes to create.
     * @return task
     */
    public DriverTask createVolumes(List<StorageVolume> volumes, StorageCapabilities capabilities) {

        DriverTask task = createDriverTask(IBMSVCConstants.TASK_TYPE_CREATE_STORAGE_VOLUMES);

        for (StorageVolume storageVolume : volumes) {

            _log.info("createVolumes() for storage system {} - start", storageVolume.getStorageSystemId());

            SSHConnection connection = null;

            try {
                connection = connectionManager.getClientBySystemId(storageVolume.getStorageSystemId());

                //Identify the

                IBMSVCCreateVolumeResult result = IBMSVCCLI.createStorageVolumes(connection, storageVolume, false,
                        false);

                if (result.isSuccess()) {
                    _log.info(String.format("Processing create storage volume %s (%s) size %s.\n", result.getName(),
                            result.getId(), result.getProvisionedCapacity()));

                    IBMSVCGetVolumeResult resultGetVolume = IBMSVCCLI.queryStorageVolume(connection, result.getId());

                    if (resultGetVolume.isSuccess()) {

                        _log.info(String.format("Processing storage volume %s.\n",
                                resultGetVolume.getProperty("VolumeId")));

                        storageVolume.setWwn(resultGetVolume.getProperty("VolumeWWN"));
                        storageVolume.setDeviceLabel(resultGetVolume.getProperty("VolumeName"));
                        storageVolume.setDisplayName(resultGetVolume.getProperty("VolumeName"));
                        Long capacity = IBMSVCDriverUtils
                                .convertGBtoBytes(resultGetVolume.getProperty("VolumeCapacity"));
                        storageVolume.setProvisionedCapacity(capacity);
                        storageVolume.setAllocatedCapacity(capacity);

                        _log.info(String.format("Processed storage volume %s \n",
                                resultGetVolume.getProperty("VolumeId")));

                    } else {
                        _log.warn(String.format("Processing storage volume failed %s\n",
                                resultGetVolume.getErrorString()), resultGetVolume.isSuccess());
                    }
                    storageVolume.setNativeId(result.getId());
                    storageVolume.setAccessStatus(StorageObject.AccessStatus.READ_WRITE);
                    result.setName(resultGetVolume.getProperty("VolumeName"));

                    task.setMessage(String.format("Created storage volume %s (%s) size %s\n", result.getName(),
                            result.getId(), result.getRequestedCapacity()));
                    task.setStatus(DriverTask.TaskStatus.READY);

                    _log.info(String.format("Created storage volume %s (%s) size %s.\n", result.getName(),
                            result.getId(), result.getRequestedCapacity()));

                } else {
                    _log.error(String.format("Creating storage volume for the storage system failed %s\n",
                            result.getErrorString()), result.isSuccess());
                    task.setMessage(String.format("Creating storage volume for the storage system %s failed : ",
                            storageVolume.getStorageSystemId()) + result.getErrorString());
                    task.setStatus(DriverTask.TaskStatus.FAILED);
                }
            } catch (Exception e) {
                _log.error("Unable to create the storage volume {} on the storage system {}",
                        storageVolume.getDeviceLabel(), storageVolume.getStorageSystemId());
                task.setMessage(String.format("Unable to create the storage volume %s on the storage system %s",
                        storageVolume.getDeviceLabel(), storageVolume.getStorageSystemId()) + e.getMessage());
                task.setStatus(DriverTask.TaskStatus.FAILED);
                e.printStackTrace();
            } finally {
                if(connection != null){
                    connection.disconnect();
                }
            }
            _log.info("createVolumes() for storage system {} - end", storageVolume.getStorageSystemId());
        }

        return task;
    }

    /**
     * Expand volume. Before completion of the request, set all required data
     * for expanded volume in "volume" parameter.
     *
     * @param storageVolume
     *            Volume to expand. Type: Input/Output argument.
     * @param newCapacity
     *            Requested capacity. Type: input argument.
     * @return task
     */
    public DriverTask expandVolume(StorageVolume storageVolume, long newCapacity) {

        DriverTask task = createDriverTask(IBMSVCConstants.TASK_TYPE_EXPAND_STORAGE_VOLUMES);

        _log.info("expandVolume() for storage system {} - start", storageVolume.getStorageSystemId());

        SSHConnection connection = null;

        try {
            connection = connectionManager.getClientBySystemId(storageVolume.getStorageSystemId());

            String newVolumeCapacity = String.valueOf(newCapacity);

            if (newCapacity > storageVolume.getProvisionedCapacity()) {

                IBMSVCExpandVolumeResult result = IBMSVCCLI.expandStorageVolumes(connection,
                        storageVolume.getNativeId(), newVolumeCapacity);

                if (result.isSuccess()) {
                    _log.info(String.format("Expanded storage volume Id (%s) size %s.\n", result.getId(),
                            result.getRequestedNewSize()));
                    task.setMessage(String.format("Expanded storage volume Id (%s) size %s.", result.getId(),
                            result.getRequestedNewSize()));
                    task.setStatus(DriverTask.TaskStatus.READY);
                } else {
                    _log.error(String.format("Expanding storage volume Id (%s) failed %s\n",
                            storageVolume.getNativeId(), result.getErrorString()), result.isSuccess());
                    task.setMessage(
                            String.format("Expanding storage volume Id (%s) failed : ", storageVolume.getNativeId())
                                    + result.getErrorString());
                    task.setStatus(DriverTask.TaskStatus.FAILED);
                }

            } else {
                _log.info(String.format("Expansion size is less than the existing volume size %s for the Volume %s.\n",
                        newVolumeCapacity, storageVolume.getNativeId()));
                task.setMessage(
                        String.format("Expansion size is less than the existing volume size %s for the Volume %s",
                                newVolumeCapacity, storageVolume.getNativeId()));
                task.setStatus(DriverTask.TaskStatus.FAILED);
            }
        } catch (Exception e) {
            _log.error("Unable to expand the storage volume {} on the storage system {}",
                    storageVolume.getDeviceLabel(), storageVolume.getStorageSystemId());
            task.setMessage(String.format("Unable to expand the storage volume %s on the storage system %s",
                    storageVolume.getDeviceLabel(), storageVolume.getStorageSystemId()) + e.getMessage());
            task.setStatus(DriverTask.TaskStatus.FAILED);
            e.printStackTrace();
        } finally {
            if(connection != null){
                connection.disconnect();
            }
        }

        _log.info("expandVolume() for storage system {} - end", storageVolume.getStorageSystemId());

        return task;
    }


    /**
     * Delete volumes.
     *
     * @param storageVolume
     *            Volumes to delete.
     * @return task
     */

    public DriverTask deleteVolume(StorageVolume storageVolume) {

        DriverTask task = createDriverTask(IBMSVCConstants.TASK_TYPE_DELETE_STORAGE_VOLUMES);

        //for (StorageVolume storageVolume : volumes) {

        _log.info("deleteVolumes() for storage system {} - start", storageVolume.getStorageSystemId());

        SSHConnection connection = null;

        try {
            connection = connectionManager.getClientBySystemId(storageVolume.getStorageSystemId());

            IBMSVCDeleteVolumeResult result = IBMSVCCLI.deleteStorageVolumes(connection,
                    storageVolume.getNativeId());

            if (result.isSuccess()) {
                _log.info(String.format("Deleted storage volume Id %s.\n", result.getId()));
                task.setMessage(String.format("Deleted storage volume Id %s.", result.getId()));
                task.setStatus(DriverTask.TaskStatus.READY);

            } else {
                _log.error(String.format("Deleting storage volume failed %s\n", result.getErrorString()),
                        result.isSuccess());
                task.setMessage(String.format("Deleting storage volume Id %s failed : ", result.getId())
                        + result.getErrorString());
                task.setStatus(DriverTask.TaskStatus.FAILED);
            }
        } catch (Exception e) {
            _log.error("Unable to delete the storage volume {} on the storage system {}",
                    storageVolume.getDeviceLabel(), storageVolume.getStorageSystemId());
            task.setMessage(String.format("Unable to delete the storage volume %s on the storage system %s",
                    storageVolume.getDeviceLabel(), storageVolume.getStorageSystemId()) + e.getMessage());
            task.setStatus(DriverTask.TaskStatus.FAILED);
            e.printStackTrace();
        } finally {
            if(connection != null){
                connection.disconnect();
            }
        }

        _log.info("deleteVolumes() for storage system {} - end", storageVolume.getStorageSystemId());
        //}
        return task;
    }

    /**
     * Export volumes to initiators through a given set of ports. If ports are
     * not provided, use port requirements from ExportPathsServiceOption storage
     * capability
     *
     * @param initiators
     *            Type: Input.
     * @param volumes
     *            Type: Input.
     * @param volumeToHLUMap
     *            map of volume nativeID to requested HLU. HLU value of -1 means
     *            that HLU is not defined and will be assigned by array. Type:
     *            Input/Output.
     * @param recommendedPorts
     *            list of storage ports recommended for the export. Optional.
     *            Type: Input.
     * @param availablePorts
     *            list of ports available for the export. Type: Input.
     * @param storageCapabilities
     *            storage capabilities. Type: Input.
     * @param usedRecommendedPorts
     *            true if driver used recommended and only recommended ports for
     *            the export, false otherwise. Type: Output.
     * @param selectedPorts
     *            ports selected for the export (if recommended ports have not
     *            been used). Type: Output.
     * @return task
     */
    public DriverTask exportVolumesToInitiators(List<Initiator> initiators, List<StorageVolume> volumes,
                                                Map<String, String> volumeToHLUMap, List<StoragePort> recommendedPorts, List<StoragePort> availablePorts,
                                                StorageCapabilities storageCapabilities, MutableBoolean usedRecommendedPorts,
                                                List<StoragePort> selectedPorts) {

        DriverTask task = createDriverTask(IBMSVCConstants.TASK_TYPE_EXPORT_STORAGE_VOLUMES);

        for (StorageVolume storageVolume : volumes) {

            _log.info("exportVolumesToInitiators() for storage system {} - start", storageVolume.getStorageSystemId());

            SSHConnection connection = null;

            try {
                connection = connectionManager.getClientBySystemId(storageVolume.getStorageSystemId());

                Set<String> uniqueHostSet = createHostsFromInitiators(connection, storageVolume.getStorageSystemId(), initiators);

                if (!uniqueHostSet.isEmpty()) {
                    // create an iterator

                    // check values
                    for (Object anUniqueHostSet : uniqueHostSet) {

                        String hostName = anUniqueHostSet.toString();

                        addVdiskAccess(connection, hostName, storageVolume.getNativeId());

                        _log.info("Exporting the volume Id {} to the host {}", storageVolume.getNativeId(), hostName);

                        IBMSVCExportVolumeResult result = IBMSVCCLI.exportStorageVolumes(connection,
                                storageVolume.getNativeId(), storageVolume.getDeviceLabel(), hostName);

                        if (result.isSuccess()) {
                            _log.info(String.format("Exported the storage volume %s to the host %s.\n",
                                    storageVolume.getDeviceLabel(), result.getHostName()));
                            task.setMessage(String.format("Exported the storage volume %s to the host %s.",
                                    storageVolume.getDeviceLabel(), result.getHostName()));
                            task.setStatus(DriverTask.TaskStatus.READY);
                        } else {
                            _log.error(String.format("Export storage volume %s to the host %s failed %s\n",
                                            storageVolume.getDeviceLabel(), result.getHostName(), result.getErrorString()),
                                    result.isSuccess());
                            task.setMessage(String.format("Export storage volume %s to the host %s failed : ",
                                    storageVolume.getDeviceLabel(), result.getHostName()) + result.getErrorString());
                            task.setStatus(DriverTask.TaskStatus.FAILED);
                        }
                    }

                } else {

                    // Create host on the array if it does not exist

                    _log.info("None of the initiator port hosts are registered with the storage system {}.",
                            storageVolume.getStorageSystemId());
                    task.setMessage(String.format(
                            "None of the initiator port hosts are registered with the storage system %s.",
                            storageVolume.getStorageSystemId()));
                    task.setStatus(DriverTask.TaskStatus.FAILED);
                }

            } catch (Exception e) {
                _log.error("Unable to export the storage volume {} on the storage system {}",
                        storageVolume.getDeviceLabel(), storageVolume.getStorageSystemId());
                task.setMessage(String.format("Unable to export the storage volume %s on the storage system %s",
                        storageVolume.getDeviceLabel(), storageVolume.getStorageSystemId()) + e.getMessage());
                task.setStatus(DriverTask.TaskStatus.FAILED);
                e.printStackTrace();
            } finally {
                if(connection != null){
                    connection.disconnect();
                }
            }

            _log.info("exportVolumesToInitiators() for storage system {} - end", storageVolume.getStorageSystemId());
        }
        return task;
    }

    /**
     * Add VDisk Access to volumes based on Host IO Groups
     * Get list of IO Groups for host
     * Add IOGrp Access to VDisk
     * @param connection - SSHConnection
     * @param hostName - hostName on array
     * @param volumeID - vDisk ID or Name
     * @throws IBMSVCDriverException
     */
    private void addVdiskAccess(SSHConnection connection, String hostName, String volumeID) throws IBMSVCDriverException{
        IBMSVCQueryHostIOGrpResult histIoGrps = IBMSVCCLI.queryHostIOGrps(connection, hostName);
        List<String> ioGrps = histIoGrps.getIOGroupIDList();
        if(ioGrps.isEmpty()){
            _log.warn("Host {} has no IO Grps ", hostName);
            return;
        }

        String iogrps = String.join(":",ioGrps);

        IBMSVCAddVdiskAccessResult addVdiskAccessResult = IBMSVCCLI.addVdiskAccess(connection, volumeID, iogrps);

        if(!addVdiskAccessResult.isSuccess()){
            //throw new IBMSVCDriverException("Failed to add vDisk Access - " + addVdiskAccessResult.getErrorString());
            // TODO: Check what to do when vDisk access fails. This fails if IO_GRP has nodeCount 0.
            // Host may be associated with that IO Group
            _log.warn("Failed to add vDisk Access - " + addVdiskAccessResult.getErrorString());
        }

    }

    /**
     * Check and create hosts on array using initiator information.
     * 1. Get list of hosts and initiators from the array
     * 2. Compare it against list of input initiators and get unique host names
     * 3. Get list of initiators not registered on the array
     * 4. For the list of initiators not available on the array:
     *         - Check if host is registered. If host is registered, add initiator to host (TODO)
     *         - If host is not registered, add host to array with initiators. Then restart checks

     * @param connection
     * @param storageSystemID - Storage System ID
     * @param initiators - List of initiators from the UI
     * @return - List of unique host names on IBM SVC Array
     * @throws IBMSVCDriverException
     */
    private Set<String> createHostsFromInitiators(SSHConnection connection, String storageSystemID, List<Initiator> initiators) throws IBMSVCDriverException{

        // Get Host Initiator List from IBM Array
        List<Initiator> ibmHostInitiatorList = getIBMSVCHostInitiatorList(connection,
                storageSystemID);

        Set<String> uniqueHostSet = new HashSet<>();
        ArrayList<Initiator> unassignedInitiators = new ArrayList<>();

        // Get list of hosts on array with input initiators
        for (Initiator initiator : initiators) {
            String initiatorPort = initiator.getPort();
            String hostName = initiator.getHostName();

            Boolean initiatorMatched = false;
            for (Initiator hostInitiator : ibmHostInitiatorList) {
                if (initiatorPort.equals(hostInitiator.getPort())) {
                    //uniqueHostSet.add(hostName);
                    uniqueHostSet.add(hostInitiator.getHostName());
                    initiatorMatched = true;
                }
            }

            // If initiator is in input and not on array
            if(!initiatorMatched){
                unassignedInitiators.add(initiator);
            }

        }

        // Get Host to Initiator Map from input
        Map<String, List<String>> hostInitiatorMap = IBMSVCHelper.getHostInitiatorMap(initiators);

        // Check unassigned initiators to see if they need to be added or host need to be created
        for(Initiator unAssignedInitiator : unassignedInitiators){
            String hostName = unAssignedInitiator.getHostName();
            String unAssignedInitiatorPort = unAssignedInitiator.getPort();
            if(Character.isDigit(hostName.charAt(0))){
                hostName = "_" + hostName;
            }

            // TODO: Assuming input hostname is equal to hostname defined on the array.
            // Handle case were this is different
            if(uniqueHostSet.contains(hostName)){
                _log.warn(String.format("Initiator Port %s not registered with host on array - %s %n",
                        unAssignedInitiatorPort, hostName));
                // TODO: Host exists but initiator not assigned. Add initiator to Host here
            }else{
                // Host does not exist on array. Add host.
                List<String> hostInitiatorList = hostInitiatorMap.get(hostName);

                IBMSVCIOgrp ioGrp = getLeastUsedIOGrp(connection, storageSystemID);

                IBMSVCCreateHostResult hostCreateResult = IBMSVCCLI.createHost(connection, hostName, StringUtils.join(hostInitiatorList, ":"), ioGrp.getIogrpName());
                if(hostCreateResult.isSuccess()){
                    // Restart checks
                    return createHostsFromInitiators(connection, storageSystemID, initiators);
                }
                else{
                    throw new IBMSVCDriverException(String.format("Host not found on array and could not create - %s \n",
                            hostCreateResult.getName()));

                }
            }

        }

        return uniqueHostSet;
    }

    /**
     * Get Least Used IOGrp
     * 1. Get list of all IOGrps
     * 2. Filter out IO Groups with node count > 0
     * 3. Identify IO Group with least host count and vdisk count
     * @param connection - SSHConnection to the array
     * @param storageSystemId - Storage System ID
     * @return - IBMSVCIOgrp
     * @throws IBMSVCDriverException
     */
    private IBMSVCIOgrp getLeastUsedIOGrp(SSHConnection connection, String storageSystemId) throws IBMSVCDriverException{

        _log.info("identifyLeastUsedIOGrp() for storage system {} - start", storageSystemId);

        IBMSVCQueryIOGrpResult ioGrps = IBMSVCCLI.queryAllIOGrps(connection);

        IBMSVCIOgrp leastUsedIOgrp = null;

        for(IBMSVCIOgrp iogrp : ioGrps.getIogrpList()){
            int nodeCount = iogrp.getNodeCount();
            int hostCount = iogrp.getHostCount();
            int vdiskCount = iogrp.getVdiskCount();

            if(nodeCount > 0){

                if(leastUsedIOgrp == null){
                    leastUsedIOgrp = iogrp;
                }

                if(leastUsedIOgrp.getHostCount() > hostCount){
                    leastUsedIOgrp = iogrp;
                }else if(leastUsedIOgrp.getHostCount() == hostCount){
                    if(leastUsedIOgrp.getVdiskCount() > vdiskCount){
                        leastUsedIOgrp = iogrp;
                    }
                }
            }

        }

        if(leastUsedIOgrp == null){
            _log.info("identifyLeastUsedIOGrp() for storage system {} -No IO Group Identified - end", storageSystemId);
        }else{
            _log.info("identifyLeastUsedIOGrp() for storage system {} - {} - end", storageSystemId, leastUsedIOgrp.getIogrpName());
        }


        return leastUsedIOgrp;
    }

    private List<Initiator> getIBMSVCHostInitiatorList(SSHConnection connection, String storageSystemId) {

        _log.info("getIBMSVCHostInitiatorList() for storage system {} - start", storageSystemId);

        List<Initiator> hostInitiatorList = new ArrayList<>();

        IBMSVCQueryAllHostResult resultAllHost = IBMSVCCLI.queryAllHosts(connection);

        if (resultAllHost.isSuccess()) {
            _log.info(String.format("Queried all host information.\n"));

            for (IBMSVCHost host : resultAllHost.getHostList()) {

                IBMSVCQueryHostInitiatorResult resultHostInitiator = IBMSVCCLI.queryHostInitiator(connection,
                        host.getHostId());

                if (resultHostInitiator.isSuccess()) {

                    _log.info(
                            String.format("Queried host initiator for host Id %s.\n", resultHostInitiator.getHostId()));

                    for (Initiator initiator : resultHostInitiator.getHostInitiatorList()) {
                        hostInitiatorList.add(initiator);
                    }
                } else {
                    _log.error(String.format("Querying host initiator for host failed %s\n",
                            resultHostInitiator.getErrorString()), resultHostInitiator.isSuccess());
                }
            }
        } else {
            _log.error(String.format("Querying all host failed %s\n", resultAllHost.getErrorString()),
                    resultAllHost.isSuccess());
        }
        _log.info("getIBMSVCHostInitiatorList() for storage system {} - end", storageSystemId);

        return hostInitiatorList;
    }

    /**
     * Unexport volumes from initiators
     *
     * @param initiators
     *            Type: Input.
     * @param volumes
     *            Type: Input.
     * @return task
     */
    public DriverTask unexportVolumesFromInitiators(List<Initiator> initiators, List<StorageVolume> volumes) {

        DriverTask task = createDriverTask(IBMSVCConstants.TASK_TYPE_UNEXPORT_STORAGE_VOLUMES);

        for (StorageVolume storageVolume : volumes) {

            _log.info("unexportVolumesFromInitiators() for storage system {} - start",
                    storageVolume.getStorageSystemId());

            SSHConnection connection = null;

            try {
                connection = connectionManager.getClientBySystemId(storageVolume.getStorageSystemId());

                List<Initiator> ibmHostInitiatorList = getIBMSVCHostInitiatorList(connection,
                        storageVolume.getStorageSystemId());

                Set<String> uniqueHostSet = new HashSet<>();

                for (Initiator initiator : initiators) {

                    for (Initiator hostInitiator : ibmHostInitiatorList) {

                        if (initiator.getPort().equals(hostInitiator.getPort())) {
                            uniqueHostSet.add(hostInitiator.getHostName());
                        }
                    }
                }

                if (uniqueHostSet.size() > 0) {

                    // create an iterator

                    // check values
                    for (Object anUniqueHostSet : uniqueHostSet) {

                        String hostName = anUniqueHostSet.toString();

                        _log.info("UnExporting the volume Id {} to the host {}", storageVolume.getNativeId(), hostName);

                        IBMSVCUnExportVolumeResult result = IBMSVCCLI.unexportStorageVolumes(connection,
                                storageVolume.getNativeId(), storageVolume.getDeviceLabel(), hostName);

                        if (result.isSuccess()) {
                            _log.info(String.format("UnExported the storage volume %s to the host %s.\n",
                                    storageVolume.getDeviceLabel(), result.getHostName()));
                            task.setMessage(String.format("UnExported the storage volume %s to the host %s.",
                                    storageVolume.getDeviceLabel(), result.getHostName()));
                            task.setStatus(DriverTask.TaskStatus.READY);

                        } else {
                            _log.error(String.format("UnExport the storage volume %s to the host %s failed : %s\n",
                                            storageVolume.getDeviceLabel(), result.getHostName(), result.getErrorString()),
                                    result.isSuccess());
                            task.setMessage(String.format("UnExport the storage volume to the host %s failed : %s.",
                                    storageVolume.getDeviceLabel(), result.getHostName()) + result.getErrorString());
                            task.setStatus(DriverTask.TaskStatus.FAILED);
                        }
                    }

                } else {
                    _log.info("None of the initiator port hosts are not registered with the storage system {}.",
                            storageVolume.getStorageSystemId());
                    task.setMessage(String.format(
                            "None of the initiator port hosts are not registered with the storage system %s.",
                            storageVolume.getStorageSystemId()));
                    task.setStatus(DriverTask.TaskStatus.FAILED);
                }

            } catch (Exception e) {
                _log.error("Unable to unexport the storage volume {} on the storage system {}\n",
                        storageVolume.getDeviceLabel(), storageVolume.getStorageSystemId());
                task.setMessage(String.format("Unable to unexport the storage volume %s on the storage system %s",
                        storageVolume.getDeviceLabel(), storageVolume.getStorageSystemId()) + e.getMessage());
                task.setStatus(DriverTask.TaskStatus.FAILED);
                e.printStackTrace();
            } finally {
                if(connection != null){
                    connection.disconnect();
                }
            }

            _log.info("unexportVolumesFromInitiators() for storage system {} - end\n",
                    storageVolume.getStorageSystemId());
        }
        return task;
    }


}
