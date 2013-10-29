package brooklyn.location.blockstore.ec2;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.jclouds.ec2.EC2ApiMetadata;
import org.jclouds.ec2.EC2Client;
import org.jclouds.ec2.domain.Attachment;
import org.jclouds.ec2.domain.Volume;
import org.jclouds.ec2.features.TagApi;
import org.jclouds.ec2.options.DetachVolumeOptions;
import org.jclouds.ec2.services.ElasticBlockStoreClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import brooklyn.location.blockstore.AbstractVolumeManager;
import brooklyn.location.blockstore.BlockDeviceOptions;
import brooklyn.location.blockstore.Devices;
import brooklyn.location.blockstore.api.AttachedBlockDevice;
import brooklyn.location.blockstore.api.BlockDevice;
import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.location.jclouds.JcloudsSshMachineLocation;
import brooklyn.util.internal.Repeater;

/**
 * For managing EBS volumes via EC2-compatible APIs.
 */
public class Ec2VolumeManager extends AbstractVolumeManager {

    private static final Logger LOG = LoggerFactory.getLogger(Ec2VolumeManager.class);

    private static final String DEVICE_PREFIX = "/dev/sd";
    private static final String OS_DEVICE_PREFIX = "/dev/xvd";

    @Override
    protected String getVolumeDeviceName(char deviceSuffix) {
        return DEVICE_PREFIX + deviceSuffix;
    }

    @Override
    protected String getOSDeviceName(char deviceSuffix) {
        return OS_DEVICE_PREFIX + deviceSuffix;
    }

    @Override
    public BlockDevice createBlockDevice(JcloudsLocation location, BlockDeviceOptions options) {
        LOG.debug("Creating block device: location={}; options={}", location, options);

        EC2Client ec2Client = location.getComputeService().getContext().unwrap(EC2ApiMetadata.CONTEXT_TOKEN).getApi();
        ElasticBlockStoreClient ebsClient = ec2Client.getElasticBlockStoreServices();
        TagApi tagClient = ec2Client.getTagApi().get();

        Volume volume = ebsClient.createVolumeInAvailabilityZone(options.getZone(), options.getSizeInGb());
        if (options.hasTags()) {
            tagClient.applyToResources(options.getTags(), ImmutableList.of(volume.getId()));
        }

        BlockDevice device = Devices.newBlockDevice(location, volume.getId());
        waitForVolumeToBeAvailable(device);

        return device;
    }

    @Override
    public AttachedBlockDevice attachBlockDevice(JcloudsSshMachineLocation machine, BlockDevice blockDevice, BlockDeviceOptions options) {
        LOG.debug("Attaching block device: machine={}; device={}; options={}", new Object[]{machine, blockDevice, options});

        JcloudsLocation location = machine.getParent();
        String region = getRegionName(location);
        EC2Client ec2Client = location.getComputeService().getContext().unwrap(EC2ApiMetadata.CONTEXT_TOKEN).getApi();
        ElasticBlockStoreClient ebsClient = ec2Client.getElasticBlockStoreServices();
        Attachment attachment = ebsClient.attachVolumeInRegion(region, blockDevice.getId(),
                machine.getNode().getProviderId(), getVolumeDeviceName(options.getDeviceSuffix()));

        LOG.debug("Finished attaching block device: machine={}; device={}; options={}", new Object[]{machine, blockDevice, options});
        return blockDevice.attachedTo(machine, attachment.getDevice());
    }

    @Override
    public BlockDevice detachBlockDevice(AttachedBlockDevice attachedBlockDevice) {
        LOG.debug("Detaching block device: {}", attachedBlockDevice);

        String region = getRegionName(attachedBlockDevice.getLocation());
        String instanceId = attachedBlockDevice.getMachine().getNode().getProviderId();
        EC2Client ec2Client = attachedBlockDevice.getLocation().getComputeService().getContext().unwrap(EC2ApiMetadata.CONTEXT_TOKEN).getApi();
        ElasticBlockStoreClient ebsClient = ec2Client.getElasticBlockStoreServices();
        ebsClient.detachVolumeInRegion(region, attachedBlockDevice.getId(), true,
                DetachVolumeOptions.Builder
                        .fromDevice(attachedBlockDevice.getDeviceName())
                        .fromInstance(instanceId));
        Volume volume = waitForVolumeToBeAvailable(attachedBlockDevice);

        LOG.debug("Finished detaching block device: {}", attachedBlockDevice);
        return Devices.newBlockDevice(attachedBlockDevice.getLocation(), volume.getId());
    }

    @Override
    public void deleteBlockDevice(BlockDevice blockDevice) {
        LOG.debug("Deleting device: {}", blockDevice);

        String region = getRegionName(blockDevice.getLocation());
        EC2Client ec2Client = blockDevice.getLocation().getComputeService().getContext().unwrap(EC2ApiMetadata.CONTEXT_TOKEN).getApi();
        ElasticBlockStoreClient ebsClient = ec2Client.getElasticBlockStoreServices();
        ebsClient.deleteVolumeInRegion(region, blockDevice.getId());
    }

    /**
     * Describes the given volume. Or returns null if it is not found.
     */
    public Volume describeVolume(BlockDevice blockDevice) {
        if (LOG.isDebugEnabled())
            LOG.debug("Describing device: {}", blockDevice);

        String region = getRegionName(blockDevice.getLocation());
        EC2Client ec2Client = blockDevice.getLocation().getComputeService().getContext().unwrap(EC2ApiMetadata.CONTEXT_TOKEN).getApi();
        ElasticBlockStoreClient ebsClient = ec2Client.getElasticBlockStoreServices();
        Set<Volume> volumes = ebsClient.describeVolumesInRegion(region, blockDevice.getId());
        return Iterables.getFirst(volumes, null);
    }
    
    // Naming convention is things like "us-east-1" or "us-east-1c"; strip off the availability zone suffix.
    // This is a hack to get around that jclouds accepts regions with the suffix for creating VMs, but not for ebsClient calls.
    private String getRegionName(JcloudsLocation location) {
        String region = location.getRegion();
        char lastchar = region.charAt(region.length() - 1);
        if (Character.isDigit(lastchar)) {
            return region; // normal region name; return as-is
        } else {
            return region.substring(0, region.length()-1); // remove single char representing availability zone
        }
    }

    /**
     * Waits for the status of the volume to be {@link Volume.Status#AVAILABLE available}.
     * If the status does not reach available after a delay, logs an error.
     * @return the last fetched volume
     */
    private Volume waitForVolumeToBeAvailable(final BlockDevice device) {
        final AtomicReference<Volume> lastVolume = new AtomicReference<Volume>();
        boolean available = Repeater.create("waiting for volume available:" + device)
                .every(1, TimeUnit.SECONDS)
                .limitTimeTo(60, TimeUnit.SECONDS)
                .until(new Callable<Boolean>() {
                    @Override
                    public Boolean call() throws Exception {
                        Volume volume = describeVolume(device);
                        lastVolume.set(volume);
                        return volume.getStatus() == Volume.Status.AVAILABLE;
                    }
                })
                .run();

        if (!available) {
            LOG.error("Volume {} still not available. Last known was: {}; continuing...", device, lastVolume.get());
        }

        return lastVolume.get();
    }

}
