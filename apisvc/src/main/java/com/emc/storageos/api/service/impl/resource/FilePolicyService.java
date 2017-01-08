/*
 * Copyright (c) 2017 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.api.service.impl.resource;

import static com.emc.storageos.api.mapper.DbObjectMapper.toLink;
import static com.emc.storageos.api.mapper.DbObjectMapper.toNamedRelatedResource;
import static com.emc.storageos.api.mapper.FilePolicyMapper.map;
import static com.emc.storageos.api.mapper.TaskMapper.toTask;

import java.net.URI;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.emc.storageos.api.mapper.functions.MapFilePolicy;
import com.emc.storageos.api.service.authorization.PermissionsHelper;
import com.emc.storageos.api.service.impl.resource.utils.FilePolicyServiceUtils;
import com.emc.storageos.api.service.impl.response.BulkList;
import com.emc.storageos.db.client.URIUtil;
import com.emc.storageos.db.client.model.FilePolicy;
import com.emc.storageos.db.client.model.FilePolicy.FilePolicyApplyLevel;
import com.emc.storageos.db.client.model.FilePolicy.FilePolicyType;
import com.emc.storageos.db.client.model.FilePolicy.SnapshotExpireType;
import com.emc.storageos.db.client.model.Operation;
import com.emc.storageos.db.client.model.Project;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.client.model.VirtualPool;
import com.emc.storageos.fileorchestrationcontroller.FileOrchestrationController;
import com.emc.storageos.model.BulkIdParam;
import com.emc.storageos.model.ResourceOperationTypeEnum;
import com.emc.storageos.model.ResourceTypeEnum;
import com.emc.storageos.model.TaskResourceRep;
import com.emc.storageos.model.auth.ACLAssignmentChanges;
import com.emc.storageos.model.auth.ACLAssignments;
import com.emc.storageos.model.file.policy.FilePolicyAssignParam;
import com.emc.storageos.model.file.policy.FilePolicyAssignResp;
import com.emc.storageos.model.file.policy.FilePolicyBulkRep;
import com.emc.storageos.model.file.policy.FilePolicyCreateParam;
import com.emc.storageos.model.file.policy.FilePolicyCreateResp;
import com.emc.storageos.model.file.policy.FilePolicyListRestRep;
import com.emc.storageos.model.file.policy.FilePolicyRestRep;
import com.emc.storageos.model.file.policy.FilePolicyUnAssignParam;
import com.emc.storageos.model.file.policy.FilePolicyUpdateParam;
import com.emc.storageos.model.file.policy.FileReplicationPolicyParam;
import com.emc.storageos.security.authentication.StorageOSUser;
import com.emc.storageos.security.authorization.CheckPermission;
import com.emc.storageos.security.authorization.DefaultPermissions;
import com.emc.storageos.security.authorization.Role;
import com.emc.storageos.services.OperationTypeEnum;
import com.emc.storageos.svcs.errorhandling.resources.APIException;
import com.emc.storageos.svcs.errorhandling.resources.BadRequestException;
import com.emc.storageos.volumecontroller.impl.monitoring.RecordableEventManager;

/**
 * @author jainm15
 */
@Path("/file/file-policies")
@DefaultPermissions(readRoles = { Role.TENANT_ADMIN, Role.SYSTEM_ADMIN, Role.SYSTEM_MONITOR }, writeRoles = { Role.SYSTEM_ADMIN,
        Role.RESTRICTED_SYSTEM_ADMIN })
public class FilePolicyService extends TaskResourceService {

    private static final Logger _log = LoggerFactory.getLogger(FilePolicyService.class);

    protected static final String EVENT_SERVICE_SOURCE = "FilePolicyService";

    private static final String EVENT_SERVICE_TYPE = "FilePolicy";

    @Autowired
    private RecordableEventManager _evtMgr;

    @Autowired
    private NetworkService networkSvc;

    @Override
    public String getServiceType() {
        return EVENT_SERVICE_TYPE;
    }

    @Override
    protected FilePolicy queryResource(URI id) {
        ArgValidator.checkUri(id);
        FilePolicy filePolicy = _permissionsHelper.getObjectById(id, FilePolicy.class);
        ArgValidator.checkEntityNotNull(filePolicy, id, isIdEmbeddedInURL(id));
        return filePolicy;
    }

    @Override
    protected URI getTenantOwner(URI id) {
        return null;
    }

    @Override
    protected ResourceTypeEnum getResourceType() {
        return ResourceTypeEnum.FILE_POLICY;
    }

    @SuppressWarnings("unchecked")
    @Override
    protected Class<FilePolicy> getResourceClass() {
        return FilePolicy.class;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public FilePolicyBulkRep queryBulkResourceReps(List<URI> ids) {
        Iterator<FilePolicy> _dbIterator = _dbClient.queryIterativeObjects(
                getResourceClass(), ids);
        BulkList.ResourceFilter filter = new BulkList.FilePolicyResourceFilter(getUserFromContext(), _permissionsHelper);
        return new FilePolicyBulkRep(BulkList.wrapping(_dbIterator,
                MapFilePolicy.getInstance(_dbClient), filter));
    }

    @Override
    public FilePolicyBulkRep queryFilteredBulkResourceReps(List<URI> ids) {
        return queryBulkResourceReps(ids);
    }

    /**
     * Retrieve resource representations based on input ids.
     * 
     * @param param POST data containing the id list.
     * @brief List of file policies of given ids.
     * @return list of representations.
     */
    @POST
    @Path("/bulk")
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Override
    public FilePolicyBulkRep getBulkResources(BulkIdParam param) {
        return (FilePolicyBulkRep) super.getBulkResources(param);
    }

    /**
     * @brief Create File Snapshot, Replication Policy
     * 
     * @param param FilePolicyParam
     * @return FilePolicyCreateResp
     */
    @POST
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @CheckPermission(roles = { Role.SYSTEM_ADMIN, Role.RESTRICTED_SYSTEM_ADMIN })
    public FilePolicyCreateResp createFilePolicy(FilePolicyCreateParam param) {
        FilePolicyCreateResp resp = new FilePolicyCreateResp();
        // Make policy name as mandatory field
        ArgValidator.checkFieldNotNull(param.getPolicyName(), "policyName");

        // Check for duplicate policy name
        if (param.getPolicyName() != null && !param.getPolicyName().isEmpty()) {
            checkForDuplicateName(param.getPolicyName(), FilePolicy.class);
        }

        // check policy type is valid or not
        ArgValidator.checkFieldValueFromEnum(param.getPolicyType(), "policy_type",
                EnumSet.allOf(FilePolicyType.class));

        // check the policy apply level is valid or not
        ArgValidator.checkFieldValueFromEnum(param.getApplyAt(), "apply_at",
                EnumSet.allOf(FilePolicyApplyLevel.class));

        _log.info("file policy creation started -- ");
        if (param.getPolicyType().equals(FilePolicyType.file_replication.name())) {
            return createFileReplicationPolicy(param);

        } else if (param.getPolicyType().equals(FilePolicyType.file_snapshot.name())) {
            return createFileSnapshotPolicy(param);
        }
        return resp;
    }

    /**
     * Gets the ids and self links of all file policies.
     * 
     * @brief List file policies
     * @return A list of file policy reference specifying the ids and self links.
     */
    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @CheckPermission(roles = { Role.SYSTEM_ADMIN, Role.SYSTEM_MONITOR, Role.TENANT_ADMIN })
    public FilePolicyListRestRep getFilePolicies() {
        return getFilePoliciesForGivenUser();
    }

    private boolean userHasTenantAdminRoles() {
        StorageOSUser user = getUserFromContext();

        if (_permissionsHelper.userHasGivenRole(user,
                null, Role.TENANT_ADMIN)) {
            return true;
        }
        return false;
    }

    private boolean userHasSystemAdminRoles() {
        StorageOSUser user = getUserFromContext();

        if (_permissionsHelper.userHasGivenRole(user,
                null, Role.SYSTEM_ADMIN, Role.RESTRICTED_SYSTEM_ADMIN)) {
            return true;
        }
        return false;
    }

    private boolean canUserAssignPolicyAtGivenLevel(FilePolicy policy) {

        // user should have system admin role to assign policy to vpool
        if (policy.getApplyAt() != null) {
            switch (policy.getApplyAt()) {
                case "vpool":
                    if (!userHasSystemAdminRoles()) {
                        _log.error("User does not sufficient roles to assign policy at vpool");
                        throw APIException.forbidden.onlySystemAdminsCanAssignVpoolPolicies(policy.getFilePolicyName());
                    }
                    break;
                case "project":
                    if (!userHasTenantAdminRoles()) {
                        _log.error("User does not sufficient roles to assign policy at project");
                        throw APIException.forbidden.onlyTenantAdminsCanAssignProjectPolicies(policy.getFilePolicyName());
                    }
                    // Verify the user has an access to given projects
                    break;
                case "file_system":
                    if (!userHasTenantAdminRoles()) {
                        _log.error("User does not sufficient roles to assign policy at file system");
                        throw APIException.forbidden.onlyTenantAdminsCanAssignFileSystemPolicies(policy.getFilePolicyName());
                    }
                    break;
                default:
                    return false;
            }
            return true;
        }

        return false;
    }

    protected FilePolicyListRestRep getFilePoliciesForGivenUser() {

        FilePolicyListRestRep filePolicyList = new FilePolicyListRestRep();
        List<URI> ids = _dbClient.queryByType(FilePolicy.class, true);
        List<FilePolicy> filePolicies = _dbClient.queryObject(FilePolicy.class, ids);

        StorageOSUser user = getUserFromContext();
        // full list if role is {Role.SYSTEM_ADMIN, Role.SYSTEM_MONITOR} AND no tenant restriction from input
        // else only return the list, which input tenant has access.
        if (_permissionsHelper.userHasGivenRole(user, null, Role.SYSTEM_ADMIN, Role.SYSTEM_MONITOR)) {
            for (FilePolicy filePolicy : filePolicies) {
                filePolicyList.add(toNamedRelatedResource(filePolicy, filePolicy.getFilePolicyName()));
            }
        } else {
            // otherwise, filter by only authorized to use
            URI tenant = null;

            tenant = URI.create(user.getTenantId());

            Set<FilePolicy> policySet = new HashSet<FilePolicy>();
            for (FilePolicy filePolicy : filePolicies) {
                if (_permissionsHelper.tenantHasUsageACL(tenant, filePolicy)) {
                    policySet.add(filePolicy);
                }
            }

            // Also adding vpools which sub-tenants of the user have access to.
            List<URI> subtenants = _permissionsHelper.getSubtenantsWithRoles(user);
            for (FilePolicy filePolicy : filePolicies) {
                if (_permissionsHelper.tenantHasUsageACL(subtenants, filePolicy)) {
                    policySet.add(filePolicy);
                }
            }

            for (FilePolicy filePolicy : policySet) {
                filePolicyList.add(toNamedRelatedResource(filePolicy, filePolicy.getFilePolicyName()));
            }
        }

        return filePolicyList;

    }

    private boolean canAccessFilePolicy(FilePolicy filePolicy) {
        StringSet tenants = filePolicy.getTenantOrg();

        if (tenants == null || tenants.isEmpty()) {
            return true;
        }

        if (isSystemAdmin()) {
            return true;
        }

        StorageOSUser user = getUserFromContext();
        String userTenantId = user.getTenantId();

        return tenants.contains(userTenantId);
    }

    /**
     * @brief Get details of a file policy.
     * 
     * @param id of the file policy.
     * @return File policy information.
     */
    @GET
    @Path("/{id}")
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @CheckPermission(roles = { Role.SYSTEM_ADMIN, Role.SYSTEM_MONITOR, Role.TENANT_ADMIN })
    public FilePolicyRestRep getFilePolicy(@PathParam("id") URI id) {

        _log.info("Request recieved to get the file policy of id: {}", id);
        FilePolicy filepolicy = queryResource(id);
        ArgValidator.checkEntity(filepolicy, id, true);
        return map(filepolicy, _dbClient);
    }

    /**
     * @brief Delete file policy.
     * @param id of the file policy.
     * @return
     */
    @DELETE
    @Path("/{id}")
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @CheckPermission(roles = { Role.SYSTEM_ADMIN, Role.RESTRICTED_SYSTEM_ADMIN })
    public Response deleteFilePolicy(@PathParam("id") URI id) {

        FilePolicy filepolicy = queryResource(id);
        ArgValidator.checkEntity(filepolicy, id, true);

        ArgValidator.checkReference(FilePolicy.class, id, checkForDelete(filepolicy));

        String policyAppliedAt = filepolicy.getApplyAt();
        if (policyAppliedAt != null) {
            _log.error("Delete file policy failed because the policy is applied at " + policyAppliedAt);
            throw APIException.badRequests.failedToDeleteFilePolicy(filepolicy.getLabel(), "This policy is applied at: " + policyAppliedAt);
        }
        StringSet assignedResources = filepolicy.getAssignedResources();

        if (assignedResources != null && !assignedResources.isEmpty()) {
            _log.error("Delete file pocicy failed because the policy has associacted resources");
            throw APIException.badRequests.failedToDeleteFilePolicy(filepolicy.getLabel(), "This policy has assigned resources.");
        }

        _dbClient.markForDeletion(filepolicy);

        auditOp(OperationTypeEnum.DELETE_FILE_POLICY, true, null, filepolicy.getId().toString(),
                filepolicy.getLabel());
        return Response.ok().build();
    }

    /**
     * @brief Assign File Policy to vpool, project, file system
     * 
     * @param id of the file policy.
     * @param param FilePolicyAssignParam
     * @return FilePolicyAssignResp
     */
    @POST
    @Path("/{id}/assign-policy")
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @CheckPermission(roles = { Role.SYSTEM_ADMIN, Role.TENANT_ADMIN })
    public FilePolicyAssignResp assignFilePolicy(@PathParam("id") URI id, FilePolicyAssignParam param) {
        FilePolicyAssignResp resp = new FilePolicyAssignResp();
        ArgValidator.checkFieldUriType(id, FilePolicy.class, "id");
        FilePolicy filepolicy = this._dbClient.queryObject(FilePolicy.class, id);
        ArgValidator.checkEntity(filepolicy, id, true);

        if (filepolicy.getApplyAt().equals(FilePolicyApplyLevel.vpool.name())) {
            return assignFilePolicyToVpool(param, filepolicy);
        } else if (filepolicy.getApplyAt().equals(FilePolicyApplyLevel.project.name())) {
            return assignFilePolicyToProject(param, filepolicy);
        } else if (filepolicy.getApplyAt().equals(FilePolicyApplyLevel.file_system.name())) {
            return assignFilePolicyToFS(param, filepolicy);
        }
        return resp;
    }

    /**
     * @brief Unassign File Policy from vpool, project, file system.
     * @param id of the file policy.
     * @param FilePolicyUnAssignParam
     * @return TaskResourceRep
     */
    @POST
    @Path("/{id}/unassign-policy")
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @CheckPermission(roles = { Role.SYSTEM_ADMIN, Role.TENANT_ADMIN })
    public TaskResourceRep unassignFilePolicy(@PathParam("id") URI id, FilePolicyUnAssignParam param) {

        _log.info("Unassign File Policy :{}  request received.", id);
        String task = UUID.randomUUID().toString();

        ArgValidator.checkFieldUriType(id, FilePolicy.class, "id");
        FilePolicy filepolicy = this._dbClient.queryObject(FilePolicy.class, id);
        ArgValidator.checkEntity(filepolicy, id, true);
        StringBuilder errorMsg = new StringBuilder();

        if (filepolicy.getAssignedResources() == null || filepolicy.getAssignedResources().isEmpty()) {
            _log.info("File Policy: " + id + " doesn't have any assigned resources.");
            return new TaskResourceRep();
        }
        if (!param.getForceUnassign() && filepolicy.getPolicyStorageResources() != null
                && !filepolicy.getPolicyStorageResources().isEmpty()) {
            errorMsg.append("File Policy" + id + " is currently active and running. Try again with 'force unassign flag' ");
            _log.error(errorMsg.toString());
            throw APIException.badRequests.invalidFilePolicyUnAssignParam(filepolicy.getFilePolicyName(), errorMsg.toString());
        }
        ArgValidator.checkFieldNotNull(param.getUnassignfrom(), "unassign_from");
        Set<URI> unassignFrom = param.getUnassignfrom();
        if (unassignFrom != null) {
            for (URI uri : unassignFrom) {
                if (!filepolicy.getAssignedResources().contains(uri.toString())) {
                    errorMsg.append("Provided resource URI is either being not assigned to the file policy:" + filepolicy.getId()
                            + " or it is a invalid URI");
                    _log.error(errorMsg.toString());
                    throw APIException.badRequests.invalidFilePolicyUnAssignParam(filepolicy.getFilePolicyName(), errorMsg.toString());
                }
            }
        }
        Operation op = _dbClient.createTaskOpStatus(FilePolicy.class, filepolicy.getId(),
                task, ResourceOperationTypeEnum.UNASSIGN_FILE_POLICY);
        op.setDescription("unassign File Policy from resources ");
        FileOrchestrationController controller = getController(FileOrchestrationController.class,
                FileOrchestrationController.FILE_ORCHESTRATION_DEVICE);
        try {
            controller.unassignFilePolicy(filepolicy.getId(), unassignFrom, task);
            auditOp(OperationTypeEnum.UNASSIGN_FILE_POLICY, true, "BEGIN", filepolicy.getId().toString(),
                    filepolicy.getLabel());

        } catch (BadRequestException e) {
            op = _dbClient.error(FilePolicy.class, filepolicy.getId(), task, e);
            _log.error("Error Unassigning File policy {}, {}", e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            _log.error("Error Unassigning Files Policy {}, {}", e.getMessage(), e);
            throw APIException.badRequests.unableToProcessRequest(e.getMessage());
        }
        return toTask(filepolicy, task, op);
    }

    /**
     * Add or remove individual File Policy ACL entry(s). Request body must include at least one add or remove operation.
     * 
     * @param id the URN of a ViPR File Policy
     * @param changes ACL assignment changes
     * @brief Add or remove ACL entries from file store VirtualPool
     * @return No data returned in response body
     */
    @PUT
    @Path("/{id}/acl")
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @CheckPermission(roles = { Role.SECURITY_ADMIN, Role.SYSTEM_ADMIN, Role.RESTRICTED_SECURITY_ADMIN }, blockProxies = true)
    public ACLAssignments updateAcls(@PathParam("id") URI id,
            ACLAssignmentChanges changes) {

        FilePolicy policy = queryResource(id);
        ArgValidator.checkEntityNotNull(policy, id, isIdEmbeddedInURL(id));
        _permissionsHelper.updateACLs(policy, changes,
                new PermissionsHelper.UsageACLFilter(_permissionsHelper));
        _dbClient.updateObject(policy);
        return getAclsOnPolicy(id);
    }

    /**
     * Get File Policy ACLs
     * 
     * @param id the URI of a ViPR FilePolicy
     * @brief Show ACL entries for File policy
     * @return ACL Assignment details
     */
    @GET
    @Path("/{id}/acl")
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @CheckPermission(roles = { Role.SECURITY_ADMIN, Role.SYSTEM_ADMIN, Role.SYSTEM_MONITOR })
    public ACLAssignments getAcls(@PathParam("id") URI id) {
        return getAclsOnPolicy(id);
    }

    protected ACLAssignments getAclsOnPolicy(URI id) {
        FilePolicy policy = queryResource(id);
        ArgValidator.checkEntityNotNull(policy, id, isIdEmbeddedInURL(id));
        ACLAssignments response = new ACLAssignments();
        response.setAssignments(_permissionsHelper.convertToACLEntries(policy.getAcls()));
        return response;
    }

    /**
     * @brief Update the file policy
     * 
     * @param id the URI of a ViPR FilePolicy
     * @param param FilePolicyUpdateParam
     * @return FilePolicyCreateResp
     */
    @PUT
    @Path("/{id}")
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @CheckPermission(roles = { Role.SYSTEM_ADMIN, Role.RESTRICTED_SYSTEM_ADMIN })
    public FilePolicyCreateResp updateFilePolicy(@PathParam("id") URI id, FilePolicyUpdateParam param) {
        FilePolicyCreateResp resp = new FilePolicyCreateResp();
        ArgValidator.checkFieldUriType(id, FilePolicy.class, "id");
        FilePolicy filepolicy = this._dbClient.queryObject(FilePolicy.class, id);
        ArgValidator.checkEntity(filepolicy, id, true);

        _log.info("file policy updation started -- ");
        if (filepolicy.getFilePolicyType().equals(FilePolicyType.file_replication.name())) {
            return updateFileReplicationPolicy(filepolicy, param);

        } else if (filepolicy.getFilePolicyType().equals(FilePolicyType.file_snapshot.name())) {
            return updateFileSnapshotPolicy(filepolicy, param);
        }
        return resp;
    }

    /**
     * Validate and create replication policy.
     * 
     * @param param
     * @return
     */
    private FilePolicyCreateResp createFileReplicationPolicy(FilePolicyCreateParam param) {
        StringBuilder errorMsg = new StringBuilder();
        FilePolicy fileReplicationPolicy = new FilePolicy();

        // Validate replication policy schedule parameters
        boolean isValidSchedule = FilePolicyServiceUtils.validatePolicySchdeuleParam(
                param.getReplicationPolicyParams().getPolicySchedule(), fileReplicationPolicy, errorMsg);
        if (!isValidSchedule && errorMsg.length() > 0) {
            _log.error("Failed to create file replication policy due to {} ", errorMsg.toString());
            throw APIException.badRequests.invalidFilePolicyScheduleParam(param.getPolicyName(), errorMsg.toString());
        }
        // validate replication type and copy mode parameters
        ArgValidator.checkFieldValueFromEnum(param.getReplicationPolicyParams().getReplicationType(), "replicationType",
                EnumSet.allOf(FilePolicy.FileReplicationType.class));

        ArgValidator.checkFieldValueFromEnum(param.getReplicationPolicyParams().getReplicationCopyMode(), "replicationCopyMode",
                EnumSet.allOf(FilePolicy.FileReplicationCopyMode.class));

        fileReplicationPolicy.setId(URIUtil.createId(FilePolicy.class));
        fileReplicationPolicy.setFilePolicyName(param.getPolicyName());
        fileReplicationPolicy.setFilePolicyType(param.getPolicyType());
        fileReplicationPolicy.setPriority(param.getPriority());
        if (param.getPolicyDescription() != null && !param.getPolicyDescription().isEmpty()) {
            fileReplicationPolicy.setFilePolicyDescription(param.getPolicyDescription());
        }
        fileReplicationPolicy.setScheduleFrequency(param.getReplicationPolicyParams().getPolicySchedule().getScheduleFrequency());
        fileReplicationPolicy.setFileReplicationType(param.getReplicationPolicyParams().getReplicationType());
        fileReplicationPolicy.setFileReplicationCopyMode(param.getReplicationPolicyParams().getReplicationCopyMode());
        fileReplicationPolicy.setApplyAt(param.getApplyAt());
        _dbClient.createObject(fileReplicationPolicy);
        _log.info("Policy {} created successfully", fileReplicationPolicy);

        return new FilePolicyCreateResp(fileReplicationPolicy.getId(), toLink(ResourceTypeEnum.FILE_POLICY,
                fileReplicationPolicy.getId()), fileReplicationPolicy.getLabel());
    }

    /**
     * Validate and create snapshot policy.
     * 
     * @param param
     * @return
     */
    private FilePolicyCreateResp createFileSnapshotPolicy(FilePolicyCreateParam param) {
        StringBuilder errorMsg = new StringBuilder();
        FilePolicy fileSnapshotPolicy = new FilePolicy();

        // Validate snapshot policy schedule parameters
        boolean isValidSchedule = FilePolicyServiceUtils.validatePolicySchdeuleParam(
                param.getSnapshotPolicyPrams().getPolicySchedule(), fileSnapshotPolicy, errorMsg);
        if (!isValidSchedule && errorMsg.length() > 0) {
            _log.error("Failed to create file snapshot policy due to {} ", errorMsg.toString());
            throw APIException.badRequests.invalidFilePolicyScheduleParam(param.getPolicyName(), errorMsg.toString());
        }

        // Validate snapshot policy expire parameters..
        if (param.getSnapshotPolicyPrams() != null) {
            FilePolicyServiceUtils.validateSnapshotPolicyParam(param.getSnapshotPolicyPrams());
        } else {
            errorMsg.append("Required parameter snapshot_params was missing or empty");
            _log.error("Failed to create snapshot policy due to {} ", errorMsg.toString());
            throw APIException.badRequests.invalidFilePolicyScheduleParam(param.getPolicyName(), errorMsg.toString());
        }
        fileSnapshotPolicy.setId(URIUtil.createId(FilePolicy.class));
        fileSnapshotPolicy.setLabel(param.getPolicyName());
        fileSnapshotPolicy.setFilePolicyType(param.getPolicyType());
        fileSnapshotPolicy.setFilePolicyName(param.getPolicyName());
        if (param.getPolicyDescription() != null && !param.getPolicyDescription().isEmpty()) {
            fileSnapshotPolicy.setFilePolicyDescription(param.getPolicyDescription());
        }
        fileSnapshotPolicy.setScheduleFrequency(param.getSnapshotPolicyPrams().getPolicySchedule().getScheduleFrequency());
        fileSnapshotPolicy.setSnapshotExpireType(param.getSnapshotPolicyPrams().getSnapshotExpireParams().getExpireType());
        fileSnapshotPolicy.setSnapshotNamePattern(param.getSnapshotPolicyPrams().getSnapshotNamePattern());
        fileSnapshotPolicy.setApplyAt(param.getApplyAt());
        if (!param.getSnapshotPolicyPrams().getSnapshotExpireParams().getExpireType()
                .equalsIgnoreCase(SnapshotExpireType.NEVER.toString())) {
            fileSnapshotPolicy.setSnapshotExpireTime((long) param.getSnapshotPolicyPrams().getSnapshotExpireParams().getExpireValue());
        }
        _dbClient.createObject(fileSnapshotPolicy);
        _log.info("Snapshot policy {} created successfully", fileSnapshotPolicy);

        return new FilePolicyCreateResp(fileSnapshotPolicy.getId(), toLink(ResourceTypeEnum.FILE_POLICY,
                fileSnapshotPolicy.getId()), fileSnapshotPolicy.getLabel());
    }

    /**
     * Update the replication policy.
     * 
     * @param fileReplicationPolicy
     * @param param
     * @return
     */
    private FilePolicyCreateResp updateFileReplicationPolicy(FilePolicy fileReplicationPolicy, FilePolicyUpdateParam param) {
        StringBuilder errorMsg = new StringBuilder();
        // validate and update common parameters!!
        updatePolicyCommonParameters(fileReplicationPolicy, param);

        if (param.getPriority() != null) {
            fileReplicationPolicy.setPriority(param.getPriority());
        }

        // Validate replication policy schedule parameters
        if (param.getReplicationPolicyParams().getPolicySchedule() != null) {
            boolean isValidSchedule = FilePolicyServiceUtils.validatePolicySchdeuleParam(
                    param.getReplicationPolicyParams().getPolicySchedule(), fileReplicationPolicy, errorMsg);
            if (!isValidSchedule && errorMsg.length() > 0) {
                _log.error("Failed to update file replication policy due to {} ", errorMsg.toString());
                throw APIException.badRequests.invalidFilePolicyScheduleParam(fileReplicationPolicy.getFilePolicyName(),
                        errorMsg.toString());
            }
            if (param.getReplicationPolicyParams().getPolicySchedule().getScheduleFrequency() != null &&
                    !param.getReplicationPolicyParams().getPolicySchedule().getScheduleFrequency().isEmpty()) {
                fileReplicationPolicy.setScheduleFrequency(param.getReplicationPolicyParams().getPolicySchedule().getScheduleFrequency());
            }
        }
        // validate and update replication parameters!!!
        if (param.getReplicationPolicyParams() != null) {
            FileReplicationPolicyParam replParam = param.getReplicationPolicyParams();

            if (replParam.getReplicationCopyMode() != null && !replParam.getReplicationCopyMode().isEmpty()) {
                ArgValidator.checkFieldValueFromEnum(param.getReplicationPolicyParams().getReplicationCopyMode(), "replicationCopyMode",
                        EnumSet.allOf(FilePolicy.FileReplicationCopyMode.class));
                fileReplicationPolicy.setFileReplicationCopyMode(replParam.getReplicationCopyMode());
            }

            if (replParam.getReplicationType() != null && !replParam.getReplicationType().isEmpty()) {
                ArgValidator.checkFieldValueFromEnum(replParam.getReplicationType(), "replicationType",
                        EnumSet.allOf(FilePolicy.FileReplicationType.class));
                // <TODO>
                // Dont change the replication type, if policy was applied to storage resources!!

                fileReplicationPolicy.setFileReplicationType(replParam.getReplicationType());
            }
        }

        _dbClient.updateObject(fileReplicationPolicy);
        // <TODO>
        // If policy was applied on storage system resources
        // then, Change all existing/applied policy parameters!!!
        // and return task as response to this api
        _log.info("File Policy {} updated successfully", fileReplicationPolicy.toString());
        return new FilePolicyCreateResp(fileReplicationPolicy.getId(), toLink(ResourceTypeEnum.FILE_POLICY,
                fileReplicationPolicy.getId()), fileReplicationPolicy.getLabel());
    }

    /**
     * Update snapshot policy.
     * 
     * @param param
     * @param fileSnapshotPolicy
     * @return
     */
    private FilePolicyCreateResp updateFileSnapshotPolicy(FilePolicy fileSnapshotPolicy, FilePolicyUpdateParam param) {
        StringBuilder errorMsg = new StringBuilder();
        // validate and update common parameters!!
        updatePolicyCommonParameters(fileSnapshotPolicy, param);

        // Validate replication policy schedule parameters
        if (param.getSnapshotPolicyPrams().getPolicySchedule() != null) {
            boolean isValidSchedule = FilePolicyServiceUtils.validatePolicySchdeuleParam(
                    param.getReplicationPolicyParams().getPolicySchedule(), fileSnapshotPolicy, errorMsg);
            if (!isValidSchedule && errorMsg.length() > 0) {
                _log.error("Failed to update file replication policy due to {} ", errorMsg.toString());
                throw APIException.badRequests.invalidFilePolicyScheduleParam(fileSnapshotPolicy.getFilePolicyName(),
                        errorMsg.toString());
            }
            if (param.getSnapshotPolicyPrams().getPolicySchedule().getScheduleFrequency() != null &&
                    !param.getReplicationPolicyParams().getPolicySchedule().getScheduleFrequency().isEmpty()) {
                fileSnapshotPolicy.setScheduleFrequency(param.getReplicationPolicyParams().getPolicySchedule().getScheduleFrequency());
            }
        }
        // Validate snapshot policy expire parameters..
        if (param.getSnapshotPolicyPrams() != null) {
            FilePolicyServiceUtils.validateSnapshotPolicyParam(param.getSnapshotPolicyPrams());

            if (param.getSnapshotPolicyPrams().getSnapshotExpireParams().getExpireType() != null) {
                fileSnapshotPolicy.setSnapshotExpireType(param.getSnapshotPolicyPrams().getSnapshotExpireParams().getExpireType());
            }
            if (!SnapshotExpireType.NEVER.toString().equalsIgnoreCase(
                    param.getSnapshotPolicyPrams().getSnapshotExpireParams().getExpireType())) {
                fileSnapshotPolicy.setSnapshotExpireTime((long) param.getSnapshotPolicyPrams().getSnapshotExpireParams().getExpireValue());
            }
            if (param.getSnapshotPolicyPrams().getSnapshotNamePattern() != null) {
                fileSnapshotPolicy.setSnapshotNamePattern(param.getSnapshotPolicyPrams().getSnapshotNamePattern());
            }
        }
        _dbClient.updateObject(fileSnapshotPolicy);

        // <TODO>
        // If policy was applied on storage system resources
        // then, Change all existing/applied policy parameters!!!
        // and return task as response to this api
        _log.info("Snapshot policy {} updated successfully", fileSnapshotPolicy.toString());

        return new FilePolicyCreateResp(fileSnapshotPolicy.getId(), toLink(ResourceTypeEnum.FILE_POLICY,
                fileSnapshotPolicy.getId()), fileSnapshotPolicy.getLabel());
    }

    private boolean updatePolicyCommonParameters(FilePolicy existingPolicy, FilePolicyUpdateParam param) {

        // Verify and updated the policy name!!!
        if (param.getPolicyName() != null && !param.getPolicyName().isEmpty()
                && !existingPolicy.getLabel().equalsIgnoreCase(param.getPolicyName())) {
            checkForDuplicateName(param.getPolicyName(), FilePolicy.class);
            existingPolicy.setLabel(param.getPolicyName());
            existingPolicy.setFilePolicyName(param.getPolicyName());
        }

        if (param.getPolicyDescription() != null && !param.getPolicyDescription().isEmpty()) {
            existingPolicy.setFilePolicyDescription(param.getPolicyDescription());
        }
        return true;
    }

    /**
     * Assigning policy at vpool level
     * 
     * @param param
     * @param filepolicy
     */
    private FilePolicyAssignResp assignFilePolicyToVpool(FilePolicyAssignParam param, FilePolicy filepolicy) {
        ArgValidator.checkFieldNotNull(param.getVpoolAssignParams(), "vpool_assign_param");

        ArgValidator.checkFieldNotNull(param.getVpoolAssignParams().getAssigntoVpools(), "assign_to_vpools");
        Set<URI> vpoolURIs = param.getVpoolAssignParams().getAssigntoVpools();
        StringSet assignedResources = filepolicy.getAssignedResources();
        if (assignedResources == null) {
            assignedResources = new StringSet();
        }
        for (URI vpoolURI : vpoolURIs) {
            ArgValidator.checkFieldUriType(vpoolURI, VirtualPool.class, "vpool");
            VirtualPool virtualPool = _dbClient.queryObject(VirtualPool.class, vpoolURI);
            ArgValidator.checkEntity(virtualPool, vpoolURI, false);

            if (assignedResources.contains(virtualPool.getId().toString())) {
                _log.info("policy {} had been assigned to vpool {} ", filepolicy.getFilePolicyName(), virtualPool.getLabel());
                continue;
            } else {
                FilePolicyServiceUtils.validateVpoolSupportPolicyType(filepolicy, virtualPool);

                // Verify user has permision to assign policy
                canUserAssignPolicyAtGivenLevel(filepolicy);

                assignedResources.add(virtualPool.getId().toString());
                StringSet vpoolPolicies = virtualPool.getFilePolices();
                if (vpoolPolicies == null) {
                    vpoolPolicies = new StringSet();
                }
                vpoolPolicies.add(filepolicy.getId().toString());
                virtualPool.setFilePolices(vpoolPolicies);
                _dbClient.updateObject(virtualPool);
            }
        }
        filepolicy.setAssignedResources(assignedResources);

        if (param.getApplyOnTargetSite() != null) {
            filepolicy.setApplyOnTargetSite(param.getApplyOnTargetSite());
        }
        _dbClient.updateObject(filepolicy);
        return new FilePolicyAssignResp(filepolicy.getId(), toLink(ResourceTypeEnum.FILE_POLICY, filepolicy.getId()),
                filepolicy.getLabel(), filepolicy.getApplyAt(), filepolicy.getAssignedResources());
    }

    /**
     * Assign policy at project level
     * 
     * @param param
     * @param filepolicy
     */
    private FilePolicyAssignResp assignFilePolicyToProject(FilePolicyAssignParam param, FilePolicy filepolicy) {
        StringBuilder errorMsg = new StringBuilder();
        ArgValidator.checkFieldNotNull(param.getProjectAssignParams(), "project_assign_param");

        if (filepolicy.getFilePolicyVpool() == null) {
            ArgValidator.checkFieldNotNull(param.getProjectAssignParams().getVpool(), "vpool");
            ArgValidator.checkFieldUriType(param.getProjectAssignParams().getVpool(), VirtualPool.class, "vpool");
            VirtualPool vpool = _dbClient.queryObject(VirtualPool.class, param.getProjectAssignParams().getVpool());
            ArgValidator.checkEntity(vpool, param.getProjectAssignParams().getVpool(), false);

            // Check if the vpool supports provided policy type..
            FilePolicyServiceUtils.validateVpoolSupportPolicyType(filepolicy, vpool);

            // Check if the vpool supports policy at project level..
            if (!vpool.isFilePolicyAtProjectLevel()) {
                errorMsg.append("Provided vpool :" + vpool.getLabel() + " doesn't support policy at project level");
                _log.error(errorMsg.toString());
                throw APIException.badRequests.invalidFilePolicyAssignParam(filepolicy.getFilePolicyName(), errorMsg.toString());
            }
            filepolicy.setFilePolicyVpool(param.getProjectAssignParams().getVpool());

        } else if (param.getProjectAssignParams().getVpool() != null
                && !param.getProjectAssignParams().getVpool().equals(filepolicy.getFilePolicyVpool())) {
            VirtualPool vpool = _dbClient.queryObject(VirtualPool.class, filepolicy.getFilePolicyVpool());
            errorMsg.append("File policy :" + filepolicy.getFilePolicyName() + "is already assigned at project level under the vpool: "
                    + vpool.getLabel());
            _log.error(errorMsg.toString());
            throw APIException.badRequests.invalidFilePolicyAssignParam(filepolicy.getFilePolicyName(), errorMsg.toString());
        }

        ArgValidator.checkFieldNotNull(param.getProjectAssignParams().getAssigntoProjects(), "assign_to_projects");
        Set<URI> projectURIs = param.getProjectAssignParams().getAssigntoProjects();
        StringSet assignedResources = filepolicy.getAssignedResources();
        if (assignedResources == null) {
            assignedResources = new StringSet();
        }
        for (URI projectURI : projectURIs) {
            ArgValidator.checkFieldUriType(projectURI, Project.class, "project");
            Project project = _dbClient.queryObject(Project.class, projectURI);
            ArgValidator.checkEntity(project, projectURI, false);
            if (assignedResources.contains(project.getId().toString())) {
                continue;
            } else {
                assignedResources.add(projectURI.toString());
                StringSet projectPolicies = project.getFilePolices();
                if (projectPolicies == null) {
                    projectPolicies = new StringSet();
                }
                projectPolicies.add(filepolicy.getId().toString());
                project.setFilePolices(projectPolicies);
                _dbClient.updateObject(project);
            }
        }

        filepolicy.setAssignedResources(assignedResources);
        if (param.getApplyOnTargetSite() != null) {
            filepolicy.setApplyOnTargetSite(param.getApplyOnTargetSite());

        }
        _dbClient.updateObject(filepolicy);
        return new FilePolicyAssignResp(filepolicy.getId(), toLink(ResourceTypeEnum.FILE_POLICY, filepolicy.getId()), filepolicy.getLabel(),
                filepolicy.getApplyAt(), filepolicy.getAssignedResources());
    }

    /**
     * Assign policy at File system level
     * 
     * @param param
     * @param filepolicy
     */
    private FilePolicyAssignResp assignFilePolicyToFS(FilePolicyAssignParam param, FilePolicy filepolicy) {
        StringBuilder errorMsg = new StringBuilder();

        ArgValidator.checkFieldNotNull(param.getFileSystemAssignParams(), "filesystem_assign_param");

        if (filepolicy.getFilePolicyVpool() == null) {
            ArgValidator.checkFieldNotNull(param.getFileSystemAssignParams().getVpool(), "vpool");
            ArgValidator.checkFieldUriType(param.getFileSystemAssignParams().getVpool(), VirtualPool.class, "vpool");
            VirtualPool vpool = _dbClient.queryObject(VirtualPool.class, param.getFileSystemAssignParams().getVpool());
            ArgValidator.checkEntity(vpool, param.getFileSystemAssignParams().getVpool(), false);

            // Check if the vpool supports provided policy type..
            FilePolicyServiceUtils.validateVpoolSupportPolicyType(filepolicy, vpool);

            // Check if the vpool supports policy at file system level..
            if (!vpool.isFilePolicyAtFSLevel()) {
                errorMsg.append("Provided vpool :" + vpool.getLabel() + " doesn't support policy at file system level");
                _log.error(errorMsg.toString());
                throw APIException.badRequests.invalidFilePolicyAssignParam(filepolicy.getFilePolicyName(), errorMsg.toString());
            }
            filepolicy.setFilePolicyVpool(param.getFileSystemAssignParams().getVpool());

        } else if (param.getFileSystemAssignParams().getVpool() != null
                && !param.getFileSystemAssignParams().getVpool().equals(filepolicy.getFilePolicyVpool())) {
            VirtualPool vpool = _dbClient.queryObject(VirtualPool.class, filepolicy.getFilePolicyVpool());
            errorMsg.append("File policy :" + filepolicy.getFilePolicyName() + "is already assigned at file system level under the vpool: "
                    + vpool.getLabel());
            _log.error(errorMsg.toString());
            throw APIException.badRequests.invalidFilePolicyAssignParam(filepolicy.getFilePolicyName(), errorMsg.toString());
        }
        if (param.getApplyOnTargetSite() != null) {
            filepolicy.setApplyOnTargetSite(param.getApplyOnTargetSite());
        }
        _dbClient.updateObject(filepolicy);
        return new FilePolicyAssignResp(filepolicy.getId(), toLink(ResourceTypeEnum.FILE_POLICY,
                filepolicy.getId()), filepolicy.getLabel(), filepolicy.getApplyAt());
    }
}
