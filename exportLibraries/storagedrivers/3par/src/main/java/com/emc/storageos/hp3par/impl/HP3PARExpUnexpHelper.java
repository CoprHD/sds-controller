/*
 * Copyright 2016 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.hp3par.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.mutable.MutableBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.hp3par.command.FcPath;
import com.emc.storageos.hp3par.command.HostCommandResult;
import com.emc.storageos.hp3par.command.HostMember;
import com.emc.storageos.hp3par.command.HostSetDetailsCommandResult;
import com.emc.storageos.hp3par.command.ISCSIPath;
import com.emc.storageos.hp3par.command.Position;
import com.emc.storageos.hp3par.command.VirtualLun;
import com.emc.storageos.hp3par.command.VirtualLunsList;
import com.emc.storageos.hp3par.command.VlunResult;
import com.emc.storageos.hp3par.utils.CompleteError;
import com.emc.storageos.hp3par.utils.HP3PARConstants;
import com.emc.storageos.hp3par.utils.HP3PARUtil;
import com.emc.storageos.hp3par.utils.SanUtils;
import com.emc.storageos.storagedriver.DriverTask;
import com.emc.storageos.storagedriver.LockManager;
import com.emc.storageos.storagedriver.Registry;
import com.emc.storageos.storagedriver.model.Initiator;
import com.emc.storageos.storagedriver.model.StoragePort;
import com.emc.storageos.storagedriver.model.StorageVolume;
import com.emc.storageos.storagedriver.model.Initiator.HostOsType;
import com.emc.storageos.storagedriver.model.Initiator.Type;
import com.emc.storageos.storagedriver.model.StoragePool.Protocols;
import com.emc.storageos.storagedriver.storagecapabilities.StorageCapabilities;

/**
 * 
 * Implements export/unexport operations
 *
 */
public class HP3PARExpUnexpHelper {

    private static final Logger _log = LoggerFactory.getLogger(HP3PARExpUnexpHelper.class);
    private HP3PARUtil hp3parUtil;
    
    
    /*********USE CASES**********
    
    EXCLUSIVE EXPORT: Will include port number of host
    
    1 Export volume to existing host  
    2 Export volume to non-existing host  
    3 Add initiator to existing host 
    4 Remove initiator from host 
    5 Unexport volume 
    
    A 1-5 can be done with single/multiple volumes,initiators as applicable
    B Does not depend on host name
    C Adding an initiator in matched-set will not do anything further. 
      All volumes have to be exported to new initiator explicitly. 
      In host-sees 3PAR will automatically export the volumes to newly added initiator.
    -------------------------------------------
    SHARED EXPORT: Will not include port number, exported to all ports, the cluster can see
    
    1 Export volume to existing cluster
    2 Export volume to non-existing cluster 
    3 Add initiator to existing host in cluster 
    4 Remove initiator from host in cluster
    5 Unexport volume from cluster
    6 Export a private volume to a host in a cluster 
    7 Unexport a private volume from a host in a cluster
    8 Add a host to cluster 
    9 Remove a host from a cluster
    10 Add a host having private export 
    11 Remove a host having private export
    12 Move a host from one cluster to another
    
    A 1-12 can be done with single/multiple volumes,initiators,hosts as applicable
    B Cluster name in ViPR and 3PAR has to be identical with case
    C Adding a new host to host-set will automatically export all volumes to the new host(initial export must have been host-set)
   */

  /*
   * All volumes in the list will be exported to all initiators using recommended ports. If a volume can not be exported to 'n' 
   * initiators the same will be tried with available ports  
   */

    public DriverTask exportVolumesToInitiators(List<Initiator> initiators, List<StorageVolume> volumes,
            Map<String, String> volumeToHLUMap, List<StoragePort> recommendedPorts, List<StoragePort> availablePorts,
            StorageCapabilities capabilities, MutableBoolean usedRecommendedPorts, List<StoragePort> selectedPorts,
            DriverTask task, Registry driverRegistry, LockManager driverLockManager) {
        
        _log.info("3PARDriver:exportVolumesToInitiators enter");

        String host = null;
        host = doHostProcessing(initiators, volumes, driverRegistry, driverLockManager);
        if (host == null ) {
            task.setMessage("exportVolumesToInitiators error: Processing hosts, Unable to export");
            task.setStatus(DriverTask.TaskStatus.FAILED);
            return task;
        }

        /*
         Export will be done keeping volumes as the starting point
         */
        Integer totalExport = recommendedPorts.size();
        for (StorageVolume vol : volumes) {
            Integer currExport = 0;
            Integer hlu = Integer.parseInt(volumeToHLUMap.get(vol.getNativeId()));

            try {
                // volume could belong to different storage system; get specific api client;
                HP3PARApi hp3parApi = hp3parUtil.getHP3PARDeviceFromNativeId(vol.getStorageSystemId(), driverRegistry);

                /*
                 export for INDIVIDUAL HOST=exclusive 
                 Some code is repeated with cluster for simplicity
                 */
                if (!host.startsWith("set:")) {
                    // try with recommended ports
                    for (StoragePort port : recommendedPorts) {
                        // volume and port belong to same storage system
                        String message = String.format(
                                "3PARDriver:exportVolumesToInitiators using recommendedPorts for "
                                        + "storage system %s, volume %s host %s hlu %s port %s",
                                        port.getStorageSystemId(), vol.getNativeId(), host, hlu.toString(), port.getNativeId());
                        _log.info(message);

                        VlunResult vlunRes = hp3parApi.createVlun(vol.getNativeId(), hlu, host, port.getNativeId());
                        if (vlunRes != null && vlunRes.getStatus()) {
                            currExport++;
                            usedRecommendedPorts.setValue(true);
                            // update hlu obtained as lun from 3apr & add the selected port if required
                            volumeToHLUMap.put(vol.getNativeId(), vlunRes.getAssignedLun());
                            if (!selectedPorts.contains(port)) {
                                selectedPorts.add(port);
                            }
                        } else {
                            task.setStatus(DriverTask.TaskStatus.PARTIALLY_FAILED);
                            _log.warn("3PARDriver: Could not export " + message);
                        }
                    } // for recommended ports

                    // now try with available ports
                    for (StoragePort port : availablePorts) {
                        if (currExport == totalExport) {
                            task.setStatus(DriverTask.TaskStatus.READY);
                            break;
                        }
                        
                        // Make sure this port is not used for earlier export
                        if (selectedPorts.contains(port)) {
                            continue;
                        }
                        
                        // verify volume and port belong to same storage
                        if (!vol.getStorageSystemId().equalsIgnoreCase(port.getStorageSystemId())) {
                            continue;
                        }

                        String message = String.format(
                                "3PARDriver:exportVolumesToInitiators using availablePorts for "
                                        + "storage system %s, volume %s host %s hlu %s port %s",
                                        port.getStorageSystemId(), vol.getNativeId(), host, hlu.toString(), port.getNativeId());
                        _log.info(message);

                        VlunResult vlunRes = hp3parApi.createVlun(vol.getNativeId(), hlu, host, port.getNativeId());
                        if (vlunRes != null && vlunRes.getStatus()) {
                            currExport++;
                            usedRecommendedPorts.setValue(false);
                            // update hlu obtained as lun from 3apr & add the selected port if required
                            volumeToHLUMap.put(vol.getNativeId(), vlunRes.getAssignedLun());
                            if (!selectedPorts.contains(port)) {
                                selectedPorts.add(port);
                            }
                        } else {
                            task.setStatus(DriverTask.TaskStatus.PARTIALLY_FAILED);
                            _log.warn("3PARDriver: Could not export " + message);
                        }
                    } // for available ports
                } else {
                    /*
                      export for CLUSTER=shared 
                      Some code is repeated with cluster for simplicity
                      
                      Cluster export will be done as host-set in 3APR for entire cluster in one go.
                      Hence requests coming for rest of the individual host exports should gracefully exit
                     */

                    String lockName = volumes.get(0).getStorageSystemId() + vol + host;
                    if (driverLockManager.acquireLock(lockName, 8, TimeUnit.MINUTES)) {
                        /*
                          If this is the first request key gets created with export operation. 
                          other requests will gracefully exit. key will be removed in unexport.
                         */

                        String message = String.format(
                                "3PARDriver:exportVolumesToInitiators "
                                        + "storage system %s, volume %s Cluster %s hlu %s ",
                                        vol.getStorageSystemId(), vol.getNativeId(), host, hlu.toString());
                        _log.info(message);

                        String exportPath = vol.getStorageSystemId() + vol.getNativeId() + host;
                        Map<String, List<String>> attributes = new HashMap<>();
                        List<String> expValue = new ArrayList<>();
                        List<String> lunValue = new ArrayList<>();
                        boolean doExport = true;

                        attributes = driverRegistry.getDriverAttributesForKey(HP3PARConstants.DRIVER_NAME,
                                exportPath);

                        if (attributes != null) {
                            expValue = attributes.get("EXPORT_PATH");
                            if (expValue != null && expValue.get(0).compareTo(exportPath) == 0) {
                                doExport = false;
                                // Already exported, make hlu, port details; gracefully exit
                                lunValue = attributes.get(vol.getNativeId());
                                volumeToHLUMap.put(vol.getNativeId(), lunValue.get(0));

                                String hstArray = get3parHostname(initiators, vol.getStorageSystemId(), driverRegistry);
                                HostMember hostRes = hp3parApi.getHostDetails(hstArray);

                                // get storage array ports for this host ports
                                List<StoragePort> clusterStoragePorts = new ArrayList<>();
                                getClusterStoragePorts(hostRes, availablePorts, vol.getStorageSystemId(),
                                        clusterStoragePorts);

                                for (StoragePort sp : clusterStoragePorts) {
                                    // assign all these ports as selected ports
                                    if (!selectedPorts.contains(sp)) {
                                        selectedPorts.add(sp);
                                    }
                                }

                                // go thru all slectedports. 
                                // if anyone is not part of the recommendedPorts set usedRecommendedPorts to false
                                usedRecommendedPorts.setValue(true);

                                for (StoragePort sp : selectedPorts) {
                                    if (!recommendedPorts.contains(sp)) {
                                        usedRecommendedPorts.setValue(false);
                                        break;
                                    }
                                }

                                task.setStatus(DriverTask.TaskStatus.READY);
                                _log.info("3PARDriver: Already exported, exiting" + message);
                            }
                        }

                        if (doExport) {
                            /*
                             for cluster use host set method, We cannot specify port; 
                             determine the individual host ports used
                             */
                            VlunResult vlunRes = hp3parApi.createVlun(vol.getNativeId(), hlu, host, null);
                            if (vlunRes != null && vlunRes.getStatus()) {

                                // update hlu obtained as lun from 3apr & add the selected port if required
                                volumeToHLUMap.put(vol.getNativeId(), vlunRes.getAssignedLun());

                                String hstArray = get3parHostname(initiators, vol.getStorageSystemId(), driverRegistry);
                                HostMember hostRes = hp3parApi.getHostDetails(hstArray);

                                // get storage array ports for this host ports
                                List<StoragePort> clusterStoragePorts = new ArrayList<>();
                                getClusterStoragePorts(hostRes, availablePorts, vol.getStorageSystemId(),
                                        clusterStoragePorts);

                                for (StoragePort sp : clusterStoragePorts) {
                                    // assign all these ports as selected ports
                                    if (!selectedPorts.contains(sp)) {
                                        selectedPorts.add(sp);
                                    }
                                }

                                usedRecommendedPorts.setValue(true);

                                for (StoragePort sp : selectedPorts) {
                                    if (!recommendedPorts.contains(sp)) {
                                        usedRecommendedPorts.setValue(false);
                                        break;
                                    }
                                }

                                // Everything is successful, Set as exported in registry
                                attributes = new HashMap<>();
                                expValue = new ArrayList<>();
                                lunValue = new ArrayList<>();

                                expValue.add(exportPath);
                                attributes.put("EXPORT_PATH", expValue);
                                lunValue.add(vlunRes.getAssignedLun());
                                attributes.put(vol.getNativeId(), lunValue);

                                attributes.put(vol.getNativeId(), lunValue);
                                driverRegistry.setDriverAttributesForKey(HP3PARConstants.DRIVER_NAME, exportPath,
                                        attributes);

                                task.setStatus(DriverTask.TaskStatus.READY);

                            } else { // end createVlun
                                task.setStatus(DriverTask.TaskStatus.PARTIALLY_FAILED);
                                _log.warn("3PARDriver: Could not export " + message);
                            }
                        } // doExport

                        driverLockManager.releaseLock(lockName);
                    }
                    
                } // end cluster export

            } catch (Exception e) {
                String msg = String.format("3PARDriver: Unable to export few volumes, error: %s", e);
                _log.error(CompleteError.getStackTrace(e));
                _log.error(msg);
                task.setMessage(msg);
                task.setStatus(DriverTask.TaskStatus.PARTIALLY_FAILED);
                e.printStackTrace();
            }
        } // for each volume

        _log.info("3PARDriver:exportVolumesToInitiators leave");
        return task;
    }

    public DriverTask unexportVolumesFromInitiators(List<Initiator> initiators, List<StorageVolume> volumes, 
            DriverTask task, Registry driverRegistry) {

        _log.info("3PARDriver:unexportVolumesFromInitiators enter");

        String host = null;
        Boolean fullSuccess = true;

        if (initiators.isEmpty() || volumes.isEmpty()) {
            String msg = "3PARDriver:unexportVolumesFromInitiators error blank initiator and/or volumes";
            _log.error(msg);
            task.setMessage(msg);
            task.setStatus(DriverTask.TaskStatus.FAILED);
            return task;
        }

        HashMap<String, String> initiatorToHostMap = new HashMap<String, String>();
        // unexport each volume
        for (StorageVolume volume : volumes) {
            try {
                // get Api client for volume specific array
                HP3PARApi hp3parApi = hp3parUtil.getHP3PARDeviceFromNativeId(volume.getStorageSystemId(),
                        driverRegistry);
                // TBD: Efficiency; use query method
                //VirtualLunsList vlunRes = hp3parApi.getVLunsOfVolume(volume.getWwn());
                VirtualLunsList vlunRes = hp3parApi.getAllVlunDetails();
                
                for (Initiator init : initiators) {

                	boolean bHostSeesEncountered = false;
                	
                	if(initiatorToHostMap.containsKey(init.getPort())){
                		host = initiatorToHostMap.get(init.getPort());
                	}
                	else{
	                    // TBD: Efficiency; Initiator & host name to be stored in hash-map
	                    ArrayList<Initiator> initList = new ArrayList<>();
	                    initList.add(init);
	                    host = get3parHostname(initList, volume.getStorageSystemId(), driverRegistry);
	                    if (host == null) {
	                        fullSuccess = false;
	                        String message = String.format(
	                                "3PARDriver:unexportVolumesFromInitiators for " + "storage system %s, volume %s initiator %s",
	                                volume.getStorageSystemId(), volume.getNativeId(), init.getPort());
	                        _log.warn(message);
	                        task.setMessage(message);
	                        task.setStatus(DriverTask.TaskStatus.PARTIALLY_FAILED);
	                        continue;
	                    }
	                    else{
	                    	initiatorToHostMap.put(init.getPort(), host);
	                    }
                	}
                    if (init.getInitiatorType().equals(Type.Host)) {
                        // get vlun and port details on this export
                        Integer lun = -1;
                        Position pos = null;
                        String portId = init.getPort();
                        portId = portId.replace(":", "");                        
                        
                        for (VirtualLun vLun:vlunRes.getMembers()) {
                        	if(bHostSeesEncountered && (vLun.getType() == HP3PARConstants.vLunType.HOST.getValue()) ){
                        		//If we are in this condition, it means already a host sees VLUN is encountered and
                        		//we have deleted all the vluns of the the Host Sees export in one REST call invocation
                        		//api/v1/vluns/vipr-volX,LunID,HostName. Hence we neglect this vlun entry
                        		continue;
                        	}
                            if (volume.getNativeId().compareTo(vLun.getVolumeName()) != 0 || (!vLun.isActive())
                                    || portId.compareToIgnoreCase(vLun.getRemoteName()) != 0) {
                                continue;
                            }

                            lun = vLun.getLun();
                            pos = vLun.getPortPos();

                            String message = String.format(
                                    "3PARDriver:unexportVolumesFromInitiators for "
                                            + "storage system %s, volume %s host %s hlu %s port %s",
                                            volume.getStorageSystemId(), volume.getNativeId(), host, lun.toString(),
                                            pos.toString());
                            _log.info(message);

                            String posStr = null;
                            if(vLun.getType() == HP3PARConstants.vLunType.MATCHED_SET.getValue()){
                            	posStr = String.format("%s:%s:%s", pos.getNode(), pos.getSlot(), pos.getCardPort());
                            }
                                                        
                            hp3parApi.deleteVlun(volume.getNativeId(), lun.toString(), host, posStr);
                        	if(vLun.getType() == HP3PARConstants.vLunType.HOST.getValue()){
                            	bHostSeesEncountered = true;
                            }   
                        }
                    } else if (init.getInitiatorType().equals(Type.Cluster)) {

                        // cluster unexport
                        String clusterName = "set:" + initiators.get(0).getClusterName();
                        String exportPath = volume.getStorageSystemId() + volume.getNativeId() + clusterName;
                        Map<String, List<String>> attributes = new HashMap<>();
                        List<String> expValue = new ArrayList<>();
                        List<String> lunValue = new ArrayList<>();
                        boolean regPresent = false;

                        String message = String.format(
                                "3PARDriver:unexportVolumesFromInitiators for " + "storage system %s, volume %s Cluster %s",
                                volume.getStorageSystemId(), volume.getNativeId(), clusterName);

                        attributes = driverRegistry.getDriverAttributesForKey(HP3PARConstants.DRIVER_NAME, exportPath);

                        if (attributes != null) {
                            expValue = attributes.get("EXPORT_PATH");
                            if (expValue != null && expValue.get(0).compareTo(exportPath) == 0) {
                                lunValue = attributes.get(volume.getNativeId());
                                regPresent = true;

                                _log.info(message);
                                /*
                                 * below operations are assumed to autonomic
                                 */
                                hp3parApi.deleteVlun(volume.getNativeId(), lunValue.get(0), clusterName, null);
                                driverRegistry.clearDriverAttributesForKey(HP3PARConstants.DRIVER_NAME, exportPath);
                            }
                        }

                        if (!regPresent) {
                            // gracefully exit, nothing to be done
                            _log.info("3PARDriver: Already unexported, exiting gracefully" + message);
                        }
                    } // if cluster
                } // for each initiator

            } catch (Exception e) {
                String msg = String.format("3PARDriver: Unable to unexport few volumes, error: %s", e);
                _log.error(msg);
                _log.error(CompleteError.getStackTrace(e));
                task.setMessage(msg);
                task.setStatus(DriverTask.TaskStatus.PARTIALLY_FAILED);
                e.printStackTrace();
                fullSuccess = false;
            }
        } // for each volume

        if (fullSuccess) {
            task.setStatus(DriverTask.TaskStatus.READY);
        }

        _log.info("3PARDriver:unexportVolumesFromInitiatorss leave");
        return task;
    }

    
    private void getClusterStoragePorts(HostMember hostRes, List<StoragePort> arrayPorts, String volStorageSystemId,
            List<StoragePort> clusterPorts) {

        for (StoragePort sp : arrayPorts) {
            if (volStorageSystemId.compareToIgnoreCase(sp.getStorageSystemId()) != 0) {
                continue;
            }

            String[] pos = sp.getNativeId().split(":");

            for (FcPath fc:hostRes.getFCPaths()) {

                if (fc.getPortPos() != null) {
                    if ((fc.getPortPos().getNode().toString().compareToIgnoreCase(pos[0]) == 0)
                            && (fc.getPortPos().getSlot().toString().compareToIgnoreCase(pos[1]) == 0)
                            && (fc.getPortPos().getCardPort().toString().compareToIgnoreCase(pos[2]) == 0)) {

                        // host connected array port
                        clusterPorts.add(sp);
                    }
                } // porPos != null
            } // for fc
        }
    }


    private String doHostProcessing(List<Initiator> initiators, List<StorageVolume> volumes, 
            Registry driverRegistry, LockManager driverLockManager) {
        String host = null;
        String hostArray = null;
        String clustArray = null;

        // all volumes belong to same storage system
        try {
            // all initiators belong to same host
            if (initiators.get(0).getInitiatorType().equals(Type.Host)) {
                // Exclusive-Host export
                // Some code is repeated with cluster for simplicity
                hostArray = get3parHostname(initiators, volumes.get(0).getStorageSystemId(), driverRegistry);
                if (hostArray == null) {
                    // create a new host or add initiator to existing host
                    HP3PARApi hp3parApi = hp3parUtil.getHP3PARDeviceFromNativeId(volumes.get(0).getStorageSystemId(),
                            driverRegistry);

                    ArrayList<String> portIds = new ArrayList<>();
                    for (Initiator init : initiators) {
                        portIds.add(init.getPort());
                    }

                    Integer persona = getPersona(initiators.get(0).getHostOsType());
                    hp3parApi.createHost(initiators.get(0).getHostName(), portIds, persona);
                    host = initiators.get(0).getHostName();
                } else {
                    host = hostArray;
                }
                // Host available

            } else if (initiators.get(0).getInitiatorType().equals(Type.Cluster)) {
                // Shared-Cluster export
                clustArray = initiators.get(0).getClusterName();
                HP3PARApi hp3parApi = hp3parUtil.getHP3PARDeviceFromNativeId(volumes.get(0).getStorageSystemId(),
                        driverRegistry);

                // Check if host exists, otherwise create
                hostArray = get3parHostname(initiators, volumes.get(0).getStorageSystemId(), driverRegistry);
                if (hostArray == null) {
                    // create a new host or add initiator to existing host
                    ArrayList<String> portIds = new ArrayList<>();
                    for (Initiator init : initiators) {
                        portIds.add(init.getPort());
                    }

                    Integer persona = getPersona(initiators.get(0).getHostOsType());
                    hp3parApi.createHost(initiators.get(0).getHostName(), portIds, persona);
                    hostArray = initiators.get(0).getHostName();
                }

                // only one thread across all nodes should create cluster; 
                String lockName = volumes.get(0).getStorageSystemId() + hostArray;
                if (driverLockManager.acquireLock(lockName, 6, TimeUnit.MINUTES)) {
                    // Check if cluster exists, otherwise create
                    HostSetDetailsCommandResult hostsetRes = hp3parApi.getHostSetDetails(clustArray);
                    if (hostsetRes == null) {
                        hp3parApi.createHostSet(clustArray, initiators.get(0).getHostName());
                    } else {
                        // if this host is not part of the cluster add it
                        boolean present = false;
                        for (String setMember:hostsetRes.getSetmembers()) {
                            if (hostArray.compareTo(setMember) == 0) {
                                present = true;
                                break;
                            }
                        }

                        if (!present) {
                            // update cluster with this host
                            hp3parApi.updateHostSet(clustArray, hostArray);
                        }
                    }

                    // Cluster available
                    host = "set:" + clustArray;
                    driverLockManager.releaseLock(lockName);
                } else {
                    _log.error("3PARDriver:doHostProcessing error: could not acquire thread lock to create cluster");
                    throw new HP3PARException(
                            "3PARDriver:doHostProcessing error: could not acquire thread lock to create cluster");
                } //lock

            } else {
                _log.error("3PARDriver:exportVolumesToInitiators error: Host/Cluster type not supported");
                throw new HP3PARException(
                        "3PARDriver:exportVolumesToInitiators error: Host/Cluster type not supported");
            }
        } catch (Exception e) {
            String msg = String.format("3PARDriver: Unable to export, error: %s", e);
            _log.error(msg);
            _log.error(CompleteError.getStackTrace(e));
            return null;
        }

        return host;
    }

    boolean hostHasAlliSCSIInitiators(List<Initiator> initiators, ArrayList<ISCSIPath> hostScPath) {
        //get list of iqns in this host
        ArrayList<String> hostIqns = new ArrayList<>();
        
        for (ISCSIPath path:hostScPath) {
            hostIqns.add(path.getName());
        }
        
        for (Initiator init:initiators) {
            if (!hostIqns.contains(init.getPort())) {
                //if any of the initiator is not part of the host
                return false;
            }
        }
        
        return true;
    }

    private boolean hostHasAllFcInitiators(List<Initiator> initiators, ArrayList<FcPath> hostFcPath) {
        //get list of wwns in this host
        ArrayList<String> hostWWNs = new ArrayList<>();
        
        for (FcPath path:hostFcPath) {
            hostWWNs.add(SanUtils.formatWWN(path.getWwn()));
        }
        
        for (Initiator init:initiators) {
            if (!hostWWNs.contains(init.getPort())) {
                //if any of the initiator is not part of the host
                return false;
            }
        }
        
        return true;
    }

    private String search3parHostName(List<Initiator> initiators, HostCommandResult hostRes) {
        String hp3parHost = null;

        // for each host in the result
        for(HostMember hostMemb:hostRes.getMembers()) {
            // for each host initiator sent
            for (Initiator init : initiators) {

                // Is initiator FC
                if (Protocols.FC.toString().compareToIgnoreCase(init.getProtocol().toString()) == 0 ) {
                    // verify in all FC ports with host
                    for(FcPath fcPath: hostMemb.getFCPaths()) {                         
                        if (SanUtils.formatWWN(fcPath.getWwn()).compareToIgnoreCase(init.getPort()) == 0) {
                            hp3parHost = hostMemb.getName();
                            // Confirm all initiators are present with this host
                            if (hostHasAllFcInitiators(initiators, hostMemb.getFCPaths())) {
                                _log.info("3PARDriver: get3parHostname FC initiator {} host {}", init.getPort(),
                                        hp3parHost);
                                return hp3parHost;
                            }
                        }
                    }
                } else if (Protocols.iSCSI.toString().compareToIgnoreCase(init.getProtocol().toString()) == 0 ){
                    // verify in all iSCSI ports with host
                    for (ISCSIPath scsiPath:hostMemb.getiSCSIPaths()) {
                        if (scsiPath.getName().compareToIgnoreCase(init.getPort()) == 0) {
                            hp3parHost = hostMemb.getName();
                            // Confirm all initiators are present with this host
                            if (hostHasAlliSCSIInitiators(initiators, hostMemb.getiSCSIPaths())) {
                                _log.info("3PARDriver: get3parHostname iSCSI initiator {} host {}", init.getPort(),
                                        hp3parHost);
                                return hp3parHost;
                            }
                        }
                    }

                } // if FC or iSCSI
            } // each initiator
        } // each host

        return null;
    }

    private String get3parHostname(List<Initiator> initiators, String storageId,
            Registry driverRegistry) throws Exception {
        // Since query works this implementation can be changed
        String hp3parHost = null;
        _log.info("3PARDriver: get3parHostname enter");

        try {
            HP3PARApi hp3parApi = hp3parUtil.getHP3PARDeviceFromNativeId(storageId, driverRegistry);
            HostCommandResult hostRes = hp3parApi.getAllHostDetails();

            hp3parHost = search3parHostName(initiators, hostRes);
            
            _log.info("3PARDriver: get3parHostname leave");
            return hp3parHost;
        } catch (Exception e) {
            _log.error("3PARDriver:get3parHostname could not get 3par registered host name");
            _log.error(CompleteError.getStackTrace(e));
            return null;
        }
    }

    private Integer getPersona(HostOsType hostType) {
        Integer persona = 0;
        
        // Supporting from lower OS versions; 
        switch (hostType) {
            case Windows:
            case Linux:
            case SUNVCS:
                persona = 1;
                break;

            case HPUX:
                persona = 7;
                break;

            case Esx:
                persona = 11;
                break;

            case AIX:
            case AIXVIO:
                persona = 8;
                break;

                // persona 3 is by experimentation, doc is not up-to-date
            case No_OS:
            case Other:
            default:
                persona = 3;
                break;
        }
        return persona;
    }
    
    public HP3PARUtil getHp3parUtil() {
        return hp3parUtil;
    }

    public void setHp3parUtil(HP3PARUtil hp3parUtil) {
        this.hp3parUtil = hp3parUtil;
    }

}
