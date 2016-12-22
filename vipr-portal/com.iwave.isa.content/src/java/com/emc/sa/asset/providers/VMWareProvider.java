/*
 * Copyright (c) 2012-2015 iWave Software LLC
 * All Rights Reserved
 */
package com.emc.sa.asset.providers;

import static com.emc.vipr.client.core.filters.CompatibilityFilter.INCOMPATIBLE;
import static com.emc.vipr.client.core.filters.DiscoveryStatusFilter.COMPLETE;
import static com.emc.vipr.client.core.filters.RegistrationFilter.REGISTERED;

import java.net.URI;
import java.util.*;

import com.emc.sa.util.PerfTimer;
import org.apache.commons.lang.StringUtils;
import org.springframework.stereotype.Component;

import com.emc.sa.asset.AssetOptionsContext;
import com.emc.sa.asset.AssetOptionsUtils;
import com.emc.sa.asset.annotation.Asset;
import com.emc.sa.asset.annotation.AssetDependencies;
import com.emc.sa.asset.annotation.AssetNamespace;
import com.emc.sa.asset.providers.BlockProvider.UnexportedBlockResourceFilter;
import com.emc.sa.machinetags.KnownMachineTags;
import com.emc.sa.machinetags.vmware.VMwareDatastoreTagger;
import com.emc.sa.service.vipr.block.BlockStorageUtils;
import com.emc.sa.util.ResourceType;
import com.emc.storageos.db.client.util.NullColumnValueGetter;
import com.emc.storageos.model.BulkIdParam;
import com.emc.storageos.model.RelatedResourceRep;
import com.emc.storageos.model.block.BlockObjectRestRep;
import com.emc.storageos.model.block.VolumeRestRep;
import com.emc.storageos.model.block.export.ExportGroupRestRep;
import com.emc.storageos.model.block.export.ITLRestRep;
import com.emc.storageos.model.host.HostRestRep;
import com.emc.storageos.model.host.cluster.ClusterRestRep;
import com.emc.storageos.model.host.vcenter.VcenterDataCenterRestRep;
import com.emc.storageos.model.host.vcenter.VcenterRestRep;
import com.emc.vipr.client.ViPRCoreClient;
import com.emc.vipr.client.core.filters.HostTypeFilter;
import com.emc.vipr.client.core.filters.SourceTargetVolumesFilter;
import com.emc.vipr.model.catalog.AssetOption;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

@Component
@AssetNamespace("vipr")
public class VMWareProvider extends BaseHostProvider {

    protected List<VcenterRestRep> listVCenters(AssetOptionsContext context) {
        return api(context).vcenters().getByTenant(context.getTenant(), REGISTERED.and(INCOMPATIBLE.not()));
    }

    protected List<VcenterRestRep> listVcentersForCluster(AssetOptionsContext context, URI clusterId) {
        ClusterRestRep clusterRestRep = api(context).clusters().get(clusterId);
        RelatedResourceRep vcenterDatacenter = clusterRestRep.getVcenterDataCenter();
        if (vcenterDatacenter == null) {
            // return all vcenters
            return api(context).vcenters().getByTenant(context.getTenant(), REGISTERED.and(INCOMPATIBLE.not()));
        }
        else {
            // return the vcenter this vipr cluster is already associated with in vcenter
            RelatedResourceRep vcenterRelatedRestRep = api(context).vcenterDataCenters().get(vcenterDatacenter.getId()).getVcenter();
            VcenterRestRep vcenterRestRep = api(context).vcenters().get(vcenterRelatedRestRep.getId());
            return Arrays.asList(vcenterRestRep);
        }
    }

    protected List<VcenterDataCenterRestRep> listDatacentersByVCenter(AssetOptionsContext context, URI vcenterId) {
        return api(context).vcenterDataCenters().getByVcenter(vcenterId, context.getTenant());
    }

    protected List<VcenterDataCenterRestRep> listDatacentersByVCenterAndCluster(AssetOptionsContext context, URI vcenterId, URI clusterId) {
        ClusterRestRep clusterRestRep = api(context).clusters().get(clusterId);
        RelatedResourceRep vcenterDatacenter = clusterRestRep.getVcenterDataCenter();
        if (vcenterDatacenter == null) {
            // return all datacenters for this datacenter
            return api(context).vcenterDataCenters().getByVcenter(vcenterId, context.getTenant());
        }
        else {
            // return the datacenter this vipr cluster is already associated with in vcenter
            VcenterDataCenterRestRep vcenterDataCenterRestRep = api(context).vcenterDataCenters().get(vcenterDatacenter.getId());
            return Arrays.asList(vcenterDataCenterRestRep);
        }
    }

    protected List<HostRestRep> listEsxHostsByDatacenter(AssetOptionsContext context, URI datacenterId) {
        return api(context).hosts().getByDataCenter(datacenterId, HostTypeFilter.ESX.and(REGISTERED).and(INCOMPATIBLE.not()).and(COMPLETE));
    }

    protected List<String> listFileDatastoresByProjectAndDatacenter(AssetOptionsContext context, URI projectId,
            URI datacenterId) {
        return new VMwareDatastoreTagger(api(context)).getDatastoreNames(projectId, datacenterId);
    }

    @Asset("vcenter")
    public List<AssetOption> getVCenters(AssetOptionsContext context) {
        debug("getting vcenters");
        return createBaseResourceOptions(listVCenters(context));
    }

    @Asset("datacenter")
    @AssetDependencies("vcenter")
    public List<AssetOption> getDatacenters(AssetOptionsContext context, URI vcenter) {
        debug("getting datacenters (vcenter=%s)", vcenter);
        return createBaseResourceOptions(listDatacentersByVCenter(context, vcenter));
    }

    @Asset("esxHost")
    @AssetDependencies({ "datacenter" })
    public List<AssetOption> getEsxHosts(AssetOptionsContext context, URI datacenter) {
        debug("getting esxHosts (datacenter=%s)", datacenter);
        return createHostOptions(context, listEsxHostsByDatacenter(context, datacenter));
    }

    @Asset("esxHost")
    @AssetDependencies({ "datacenter", "blockStorageType" })
    public List<AssetOption> getEsxHosts(AssetOptionsContext context, URI datacenter, String storageType) {
        debug("getting esxHosts (datacenter=%s)", datacenter);

        List<HostRestRep> esxHosts = listEsxHostsByDatacenter(context, datacenter);

        filterClusterHosts(context, datacenter, storageType, esxHosts);

        return getHostOrClusterOptions(context, esxHosts, storageType);
    }

    /**
     * Filter out hosts in the cluster when other hosts in that cluster have failed discovery or
     * are incompatible.
     * 
     * @param context
     *            asset context
     * @param datacenter
     *            data center
     * @param storageType
     *            storage type (exclusive or shared)
     * @param esxHosts
     *            list of all esx hosts that are discovered and compatible
     */
    private void filterClusterHosts(AssetOptionsContext context, URI datacenter, String storageType, List<HostRestRep> esxHosts) {
        // Gather all ESX hosts that didn't match the filter
        if (esxHosts != null && !esxHosts.isEmpty() && storageType.equalsIgnoreCase(BlockProvider.SHARED_STORAGE.toString())) {
            List<HostRestRep> misfitEsxHosts = api(context).hosts().getByDataCenter(datacenter,
                    HostTypeFilter.ESX.and(REGISTERED.not()).or(INCOMPATIBLE).or(COMPLETE.not()));
            Set<URI> misfitEsxClusterIds = new HashSet<>();
            if (misfitEsxHosts != null && !misfitEsxHosts.isEmpty()) {
                for (HostRestRep misfitEsxHost : misfitEsxHosts) {
                    if (misfitEsxHost.getCluster() != null && !NullColumnValueGetter.isNullURI(misfitEsxHost.getCluster().getId())) {
                        misfitEsxClusterIds.add(misfitEsxHost.getCluster().getId());
                    }
                }

                Iterator<HostRestRep> esxHostIter = esxHosts.iterator();
                while (esxHostIter.hasNext()) {
                    HostRestRep esxHost = esxHostIter.next();
                    if (esxHost.getCluster() != null && !NullColumnValueGetter.isNullURI(esxHost.getCluster().getId())
                            && misfitEsxClusterIds.contains(esxHost.getCluster().getId())) {
                        esxHostIter.remove();
                    }
                }
            }
        }
    }

    @Asset("datastore")
    @AssetDependencies({ "datacenter", "project" })
    public List<AssetOption> getFileDatastores(AssetOptionsContext context, URI datacenter, URI project) {
        debug("getting fileDatastores (datacenter=%s, project=%s)", datacenter, project);
        return createStringOptions(listFileDatastoresByProjectAndDatacenter(context, project, datacenter));
    }

    private static List<URI> getVolumeList(List<? extends BlockObjectRestRep> objects) {
        List<URI> volumes = Lists.newArrayList();
        for (BlockObjectRestRep rep : objects) {
            volumes.add(rep.getId());
        }
        return volumes;
    }

    @Asset("unassignedBlockDatastore")
    @AssetDependencies({ "esxHost", "project" })
    public List<AssetOption> getUnassignedDatastores(AssetOptionsContext ctx, URI hostOrClusterId, final URI projectId) {
        PerfTimer timer = new PerfTimer();
        timer.start();
        info("getting blockDatastores");
        ViPRCoreClient client = api(ctx);
        Set<URI> exportedBlockResources = BlockProvider.getExportedVolumes(api(ctx), projectId, hostOrClusterId, null);
        info("========= Got exported vols. Time = %s", timer.probe());
        UnexportedBlockResourceFilter<VolumeRestRep> unexportedFilter = new UnexportedBlockResourceFilter<VolumeRestRep>(
                exportedBlockResources);
        SourceTargetVolumesFilter sourceTargetVolumesFilter = new SourceTargetVolumesFilter();
        List<VolumeRestRep> volumes = client.blockVolumes().findByProject(projectId, unexportedFilter.and(sourceTargetVolumesFilter));
        List<URI> volumeIds = getVolumeList(volumes);
        Map<URI, Integer> volumeHlus = getVolumeHLUs(ctx, volumeIds);
        // return createBlockVolumeDatastoreOptions(volumeHlus, volumes, hostOrClusterId);
        List<AssetOption> options = createBlockVolumeDatastoreOptions(volumeHlus, volumes, hostOrClusterId);
        info("========= Done. Time = %s", timer.probe());
        return options;
    }

    @Asset("assignedBlockDatastore")
    @AssetDependencies("esxHost")
    public List<AssetOption> getAssignedDatastores(AssetOptionsContext context, URI esxHost) {
        PerfTimer timer = new PerfTimer();
        timer.start();
        info("getting blockDatastores (esxHost=%s)", esxHost);

        List<ExportGroupRestRep> exports = BlockProviderUtils.getExportsForHostOrCluster(api(context), context.getTenant(), esxHost);
        info("========= Got exports. Time = %s", timer.probe());
        Collection<ExportGroupRestRep> filteredExportGroups = BlockStorageUtils.filterExportsByType(exports, esxHost);
        Set<URI> volumeIds = BlockProviderUtils.getExportedResourceIds(filteredExportGroups, ResourceType.VOLUME);
        info("========= Got exported vols. Time = %s", timer.probe());
        Set<URI> snapshotIds = BlockProviderUtils.getExportedResourceIds(filteredExportGroups, ResourceType.BLOCK_SNAPSHOT);
        info("========= Got exported snapshots. Time = %s", timer.probe());

        List<BlockObjectRestRep> resources = new ArrayList<>();
        resources.addAll(api(context).blockVolumes().getByIds(volumeIds));
        resources.addAll(api(context).blockSnapshots().getByIds(snapshotIds));

        List<URI> resourceIds = getVolumeList(resources);
        // Map<URI, Integer> volumeHlus = getVolumeHLUs(context, resourceIds);
        // return createBlockVolumeDatastoreOptions(volumeHlus, resources, esxHost);
        info("========= all db query done. Before transform. Time = %s", timer.probe());
        List<AssetOption> options = createBlockVolumeDatastoreOptions(null, resources, esxHost);
        info("========= Done. Time = %s", timer.probe());
        return options;
    }

    @Asset("blockdatastore")
    @AssetDependencies("esxHost")
    public List<AssetOption> getBlockDatastores(AssetOptionsContext context, URI esxHost) {
        debug("getting blockDatastores (esxHost=%s)", esxHost);
        List<? extends BlockObjectRestRep> mountedVolumes = BlockProviderUtils.getBlockResources(
                api(context), context.getTenant(), esxHost, true);

        return createDatastoreOptions(mountedVolumes, esxHost);
    }

    @Asset("vcentersForCluster")
    @AssetDependencies("cluster")
    public List<AssetOption> getVcentersForCluster(AssetOptionsContext context, URI cluster) {
        debug("getting vcenters for Cluster");
        return createBaseResourceOptions(listVcentersForCluster(context, cluster));
    }

    @Asset("datacentersForCluster")
    @AssetDependencies({ "vcentersForCluster", "cluster" })
    public List<AssetOption> getDatacentersForCluster(AssetOptionsContext context, URI vcentersForCluster, URI cluster) {
        debug("getting datacenters (vcenter=%s, cluster=%s)", vcentersForCluster, cluster);
        return createBaseResourceOptions(listDatacentersByVCenterAndCluster(context, vcentersForCluster, cluster));
    }

    @Asset("vcentersForEsxCluster")
    @AssetDependencies("esxCluster")
    public List<AssetOption> getVcentersForEsxCluster(AssetOptionsContext context, URI esxCluster) {
        debug("getting vcenters for esxCluster");
        return createBaseResourceOptions(listVcentersForCluster(context, esxCluster));
    }

    @Asset("datacentersForEsxCluster")
    @AssetDependencies({ "vcentersForEsxCluster", "esxCluster" })
    public List<AssetOption> getDatacentersForEsxCluster(AssetOptionsContext context, URI vcentersForEsxCluster, URI esxCluster) {
        debug("getting datacenters (vcenter=%s, cluster=%s)", vcentersForEsxCluster, esxCluster);
        return createBaseResourceOptions(listDatacentersByVCenterAndCluster(context, vcentersForEsxCluster, esxCluster));
    }

    protected static List<AssetOption> createBlockVolumeDatastoreOptions(Map<URI, Integer> volumeHlus,
            List<? extends BlockObjectRestRep> mountedVolumes, URI hostId) {
        List<AssetOption> options = Lists.newArrayList();

        for (BlockObjectRestRep volume : mountedVolumes) {
            Set<String> datastoreNames = VMwareDatastoreTagger.getDatastoreNames(volume);
            // Integer hlu = volumeHlus.get(volume.getId());
            Integer hlu = null;
            String hluLabel = hlu == null ? "N/A" : hlu.toString();
            String datastoresLabel = datastoreNames.isEmpty() ? "N/A" : StringUtils.join(datastoreNames, ",");
            options.add(newAssetOption(volume.getId(), "volume.hlu.datastore", volume.getName(), hluLabel, datastoresLabel));
        }
        AssetOptionsUtils.sortOptionsByLabel(options);
        return options;
    }

    private Map<URI, Integer> getVolumeHLUs(AssetOptionsContext context, List<URI> volumeIds) {
        BulkIdParam bulkParam = new BulkIdParam();
        Map<URI, Integer> volumeHLUs = Maps.newHashMap();
        bulkParam.getIds().addAll(volumeIds);

        List<ITLRestRep> bulkResponse = api(context).blockVolumes().getExports(bulkParam).getExportList();
        for (ITLRestRep export : bulkResponse) {
            volumeHLUs.put(export.getBlockObject().getId(), export.getHlu());
        }
        return volumeHLUs;

    }

    protected static List<AssetOption> createDatastoreOptions(List<? extends BlockObjectRestRep> mountedVolumes, URI hostId) {
        Set<String> datastores = Sets.newHashSet(); // There can be multiple volumes to a DS, so de-dupe in a Set
        for (BlockObjectRestRep volume : mountedVolumes) {
            datastores.add(KnownMachineTags.getBlockVolumeVMFSDatastore(hostId, volume));
        }

        List<AssetOption> options = Lists.newArrayList();
        for (String datastore : datastores) {
            options.add(new AssetOption(datastore, datastore));
        }

        AssetOptionsUtils.sortOptionsByLabel(options);
        return options;
    }
}
