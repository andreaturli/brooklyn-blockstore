package brooklyn.location.blockstore;

import brooklyn.location.blockstore.api.MountedBlockDevice;
import brooklyn.location.blockstore.api.VolumeManager;
import brooklyn.location.blockstore.api.VolumeOptions;
import brooklyn.location.blockstore.ec2.Ec2VolumeManager;
import brooklyn.location.blockstore.openstack.OpenstackVolumeManager;
import brooklyn.location.blockstore.vclouddirector15.VcloudVolumeManager;
import com.google.common.base.Optional;
import com.google.common.reflect.TypeToken;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.location.jclouds.BasicJcloudsLocationCustomizer;
import org.apache.brooklyn.location.jclouds.JcloudsLocation;
import org.apache.brooklyn.location.jclouds.JcloudsLocationConfig;
import org.apache.brooklyn.location.jclouds.JcloudsMachineLocation;
import org.apache.brooklyn.util.collections.MutableList;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.domain.NodeMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Creates a location customizer that:
 * <ul>
 * <li>configures the EC2 availability zone</li>
 * <li>attaches the specified (existing) volume to the newly-provisioned EC2 instance</li>
 * <li>mounts the filesystem under the requested path</li>
 * </ul>
 *
 * Can be used for attaching additional disk on provisioning time for AWS.
 * Below is shown an example:
 *
 * <pre>
 *   provisioning.properties:
 *     customizers:
 *     - $brooklyn:object:
 *         type: io.brooklyn.blockstore.brooklyn-blockstore:brooklyn.location.blockstore.NewVolumeCustomizer
 *         brooklyn.config:
 *           volumes:
 *           - blockDevice:
 *               sizeInGb: 3
 *               deviceSuffix: 'h'
 *               deleteOnTermination: true
 *               tags:
 *                 brooklyn: br-example-test-1
 *            filesystem:
 *              mountPoint: /mount/brooklyn/h
 *              filesystemType: ext3
 * </pre>
 */
public class NewVolumeCustomizer extends BasicJcloudsLocationCustomizer {
    public static final String AWS_CLOUD = "aws-ec2";
    public static final String OPENSTACK_NOVA = "openstack-nova";
    public static final String VCLOUD_DIRECTOR = "vcloud-director";


    private static final Logger LOG = LoggerFactory.getLogger(NewVolumeCustomizer.class);

    public static final ConfigKey<List<VolumeOptions>> VOLUMES = ConfigKeys.newConfigKey(
            new TypeToken<List<VolumeOptions>>() {},
            "volumes", "List of volumes to be attached");

    /**
     * Used only for checking results from customization
     */
    // TODO test using that customizer after rebind. Good example is on cluster resize.
    protected transient List<MountedBlockDevice> mountedBlockDeviceList;

    public NewVolumeCustomizer() {
        mountedBlockDeviceList = MutableList.of();
    }

    public NewVolumeCustomizer(List<VolumeOptions> volumesOptions) {
        this.config().set(VOLUMES, volumesOptions);
        mountedBlockDeviceList = MutableList.of();
    }

    public List<VolumeOptions> getVolumes() {
        return this.getConfig(VOLUMES);
    }

    public List<MountedBlockDevice> getMountedBlockDeviceList() {
        return mountedBlockDeviceList;
    }

    protected VolumeManager getVolumeManager(JcloudsMachineLocation machine) {
        String provider;
        provider = getConfig(JcloudsLocationConfig.CLOUD_PROVIDER);
        if (provider == null) {
            provider = machine.getParent().getProvider();
        }

        switch (provider) {
            case AWS_CLOUD:
                return new Ec2VolumeManager();
            case OPENSTACK_NOVA:
                return new OpenstackVolumeManager();
            case VCLOUD_DIRECTOR:
                return new VcloudVolumeManager();
            default:
                throw new UnsupportedOperationException("Tried to invoke addExtraHdd effector on entity " + getCallerContext(machine) + " for cloud "
                        + provider + " which does not support adding disks from an effector.");
        }

    }

    public void setVolumes(List<VolumeOptions> volumes) {
        this.config().set(VOLUMES,volumes);
    }

    public void customize(JcloudsLocation location, ComputeService computeService, JcloudsMachineLocation machine) {
        if (!getVolumes().isEmpty()) {
            createAndAttachDisks(machine);
        } else {
            throw new UnsupportedOperationException("There is no volume data populated to create and attach disk.");
        }
    }

    protected void createAndAttachDisks(JcloudsMachineLocation machine) {
        for (VolumeOptions volume : getVolumes()) {
            createAndAttachDisk(machine, volume);
        }
    }

    protected void createAndAttachDisk(JcloudsMachineLocation machine, VolumeOptions volumeOptions) {
        if (volumeOptions.getFilesystemOptions() != null) {
            BlockDeviceOptions blockOptionsCopy = BlockDeviceOptions.copy(volumeOptions.getBlockDeviceOptions());
            Optional<NodeMetadata> node = machine.getOptionalNode();
            if (node.isPresent()) {
                blockOptionsCopy.zone(node.get().getLocation().getId());
            } else {
                LOG.warn("JcloudsNodeMetadata is not available for the MachineLocation. Using zone specified from a parameter.");
            }
            mountedBlockDeviceList.add(getVolumeManager(machine).createAttachAndMountVolume(machine, blockOptionsCopy, volumeOptions.getFilesystemOptions()));
        }
    }
}
