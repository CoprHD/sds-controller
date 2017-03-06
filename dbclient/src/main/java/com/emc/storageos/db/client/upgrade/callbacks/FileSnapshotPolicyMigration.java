package com.emc.storageos.db.client.upgrade.callbacks;

import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.URIUtil;
import com.emc.storageos.db.client.constraint.ContainmentConstraint;
import com.emc.storageos.db.client.constraint.URIQueryResultList;
import com.emc.storageos.db.client.model.FilePolicy;
import com.emc.storageos.db.client.model.SchedulePolicy;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.client.model.VirtualNAS;
import com.emc.storageos.db.client.model.FilePolicy.FilePolicyApplyLevel;
import com.emc.storageos.db.client.model.FilePolicy.FilePolicyType;
import com.emc.storageos.db.client.model.FileShare.PersonalityTypes;
import com.emc.storageos.db.client.model.FileShare;
import com.emc.storageos.db.client.model.NASServer;
import com.emc.storageos.db.client.model.PhysicalNAS;
import com.emc.storageos.db.client.model.PolicyStorageResource;
import com.emc.storageos.db.client.upgrade.BaseCustomMigrationCallback;
import com.emc.storageos.svcs.errorhandling.resources.MigrationCallbackException;


/**
 * File snapshot policy to file policy migration
 * 
 */
public class FileSnapshotPolicyMigration extends BaseCustomMigrationCallback{
	private static final Logger logger = LoggerFactory.getLogger(FileSnapshotPolicyMigration.class);

    public static final String FILE_STORAGE_RESOURCE = "FILESTORAGERESOURCE";
    public static final String FILE_STORAGE_DEVICE_TYPE = "ISILON";
    
	@Override
	public void process() throws MigrationCallbackException {
		logger.info("File snapshot schedule policy to file policy migration START");
		
		DbClient dbClient = getDbClient();
		try{
			List<URI> schedulePolicyURIs = dbClient.queryByType(SchedulePolicy.class, true);
			Iterator<SchedulePolicy> schedulePolicies = dbClient.queryIterativeObjects(SchedulePolicy.class, schedulePolicyURIs, true);
			List<FilePolicy> filePolicies = new ArrayList<FilePolicy>();
			List<FileShare> fileShares = new ArrayList<FileShare>();
			List<URI> fsURIs = null;
			while (schedulePolicies.hasNext()) {
				SchedulePolicy schedulePolicy = schedulePolicies.next();
				FilePolicy fileSnapshotPolicy = new FilePolicy();
				
				fileSnapshotPolicy.setId(URIUtil.createId(FilePolicy.class));
				logger.info("assigning resource to fileSnapshotPolicy from schedulePolicy : {}" , schedulePolicy.getAssignedResources());
				fileSnapshotPolicy.setAssignedResources(schedulePolicy.getAssignedResources());
				logger.info("Assigned resources from fileSnapshotPolicy : {}", fileSnapshotPolicy.getAssignedResources());
				fileSnapshotPolicy.setFilePolicyDescription(
						"Policy created from Schedule Policy " + schedulePolicy.getLabel() + " while system upgrade");
				String polName = schedulePolicy.getLabel() + "_File_Snapshot_Policy";
				fileSnapshotPolicy.setLabel(polName);
				fileSnapshotPolicy.setFilePolicyName(polName);
				fileSnapshotPolicy.setFilePolicyType(FilePolicyType.file_snapshot.name());
				fileSnapshotPolicy.setScheduleFrequency(schedulePolicy.getScheduleFrequency());
				fileSnapshotPolicy.setScheduleRepeat(schedulePolicy.getScheduleRepeat());
				fileSnapshotPolicy.setScheduleTime(schedulePolicy.getScheduleTime());
				fileSnapshotPolicy.setSnapshotExpireTime(schedulePolicy.getSnapshotExpireTime());
				fileSnapshotPolicy.setSnapshotExpireType(schedulePolicy.getSnapshotExpireType());
				// snapshot policy apply at file system level
				fileSnapshotPolicy.setApplyAt(FilePolicyApplyLevel.file_system.name());

				List<URI> fileShareURIs = getAssignedResourcesURIs(schedulePolicy.getAssignedResources());
				
				for(URI fsURI : fileShareURIs){
					logger.info("ready to create policy storage resources");
					logger.info("FS URI {}" , fsURI);
					FileShare fs = dbClient.queryObject(FileShare.class, fsURI);
					if(!fs.getInactive()){
						StorageSystem system = dbClient.queryObject(StorageSystem.class, fs.getStorageDevice());
						logger.info("updating policy storage resource");
						logger.info("Storage  system : {}" , system.toString());
						updatePolicyStorageResouce(system, fileSnapshotPolicy, fs);
						logger.info("Done updating policy storage resources");
					}
					
				}
				
				filePolicies.add(fileSnapshotPolicy);
				logger.info("Added info to filePolicies : {}" , filePolicies);
				logger.info("Added info to filePolicies : {}" , filePolicies.toString());
			}
			
	
			//Update DB
			if(!filePolicies.isEmpty()){
				logger.info("Created {} file snapshot policies", filePolicies.size());
				logger.info("creating FilePolicies : {}" , filePolicies);
				dbClient.createObject(filePolicies);				
			}
			
		}catch (Exception ex){
			logger.error("Exception occured while migrating file replication policy for Virtual pools");
            logger.error(ex.getMessage(), ex);
		}
	}

	private List<URI> getAssignedResourcesURIs(StringSet assignedResources) {
		List<URI> resourceURIs = new ArrayList<URI>();
		if(!assignedResources.isEmpty()){
			logger.info("Converting String to URIs");
			for(String resource : assignedResources){
				URI resourceURI = URI.create(resource);
				resourceURIs.add(resourceURI);
			}
		}
		return resourceURIs;
	}

	private String generateNativeGuidForFilePolicyResource(StorageSystem system, String nasServer, String filePolicyType,
			String path) {
		return String.format("%s+%s+%s+%s+%s+%s", FILE_STORAGE_DEVICE_TYPE, system.getSerialNumber(), FILE_STORAGE_RESOURCE,
                nasServer, filePolicyType, path);
	}
	
	private PhysicalNAS getSystemPhysicalNAS(StorageSystem system) {
        List<URI> nasServers = dbClient.queryByType(PhysicalNAS.class, true);
        List<PhysicalNAS> phyNasServers = dbClient.queryObject(PhysicalNAS.class, nasServers);
        for (PhysicalNAS nasServer : phyNasServers) {
            if (nasServer.getStorageDeviceURI().toString().equalsIgnoreCase(system.getId().toString())) {
                return nasServer;
            }
        }		
		return null;
	}
	
	private void updatePolicyStorageResouce(StorageSystem system, FilePolicy fileSnapshotPolicy, FileShare fs) {
		
		logger.info("Creating policy storage resource for storage {} fs {} and policy {} ", system.getLabel(), fs.getLabel(),
                fileSnapshotPolicy.getFilePolicyName());
        PolicyStorageResource policyStorageResource = new PolicyStorageResource();
        policyStorageResource.setId(URIUtil.createId(PolicyStorageResource.class));
        policyStorageResource.setFilePolicyId(fileSnapshotPolicy.getId());
        policyStorageResource.setStorageSystem(system.getId());
        policyStorageResource.setPolicyNativeId(fs.getName());
        policyStorageResource.setAppliedAt(fs.getId());
        NASServer nasServer = null;
        if (fs.getVirtualNAS() != null) {
            nasServer = dbClient.queryObject(VirtualNAS.class, fs.getVirtualNAS());
        } else {
            // Get the physical NAS for the storage system!!
            PhysicalNAS pNAS = getSystemPhysicalNAS(system);
            if (pNAS != null) {
                nasServer = pNAS;
            }
        }
        if (nasServer != null) {
            logger.info("Found NAS server {} ", nasServer.getNasName());
            policyStorageResource.setNasServer(nasServer.getId());
            policyStorageResource.setNativeGuid(generateNativeGuidForFilePolicyResource(system,
                    nasServer.getNasName(), fileSnapshotPolicy.getFilePolicyType(), fs.getNativeId()));
        }

        dbClient.createObject(policyStorageResource);

        fileSnapshotPolicy.addPolicyStorageResources(policyStorageResource.getId());
        fileSnapshotPolicy.addAssignedResources(fs.getId());
        logger.info("PolicyStorageResource object created successfully for {} ",
                system.getLabel() + policyStorageResource.getAppliedAt());
    }

}
