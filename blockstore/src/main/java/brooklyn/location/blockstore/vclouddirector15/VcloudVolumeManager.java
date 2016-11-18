package brooklyn.location.blockstore.vclouddirector15;

import brooklyn.location.blockstore.AbstractVolumeManager;
import brooklyn.location.blockstore.BlockDeviceOptions;
import brooklyn.location.blockstore.Devices;
import brooklyn.location.blockstore.api.AttachedBlockDevice;
import brooklyn.location.blockstore.api.BlockDevice;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import org.apache.brooklyn.location.jclouds.JcloudsMachineLocation;
import org.apache.brooklyn.util.repeat.Repeater;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.ec2.domain.Volume;
import org.jclouds.util.Predicates2;
import org.jclouds.vcloud.director.v1_5.VCloudDirectorApi;
import org.jclouds.vcloud.director.v1_5.domain.RasdItemsList;
import org.jclouds.vcloud.director.v1_5.domain.Task;
import org.jclouds.vcloud.director.v1_5.domain.Vm;
import org.jclouds.vcloud.director.v1_5.domain.dmtf.RasdItem;
import org.jclouds.vcloud.director.v1_5.domain.dmtf.cim.CimString;
import org.jclouds.vcloud.director.v1_5.features.TaskApi;
import org.jclouds.vcloud.director.v1_5.features.VmApi;
import org.jclouds.vcloud.director.v1_5.functions.AddScsiLogicSASBus;
import org.jclouds.vcloud.director.v1_5.functions.NewScsiLogicSASDisk;
import org.jclouds.vcloud.director.v1_5.predicates.TaskSuccess;

import javax.xml.namespace.QName;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class VcloudVolumeManager extends AbstractVolumeManager {
    public static final long EDIT_VM_TIMEOUT_MS = 600000L;
    @Override
    protected String getVolumeDeviceName(char deviceSuffix) {
        return null;
    }

    @Override
    protected String getOSDeviceName(char deviceSuffix) {
        return null;
    }

    // TODO
    @Override
    public BlockDevice createBlockDevice(JcloudsMachineLocation jcloudsMachineLocation, BlockDeviceOptions options) {
        Optional<NodeMetadata> vcloudNodeMetadata = jcloudsMachineLocation.getOptionalNode();
        VCloudDirectorApi vCloudDirectorApi = jcloudsMachineLocation.getParent().getComputeService().getContext().unwrapApi(VCloudDirectorApi.class);
        VmApi vmApi = vCloudDirectorApi.getVmApi();
        TaskApi taskApi = vCloudDirectorApi.getTaskApi();
        Vm vm = Vm.builder().id(vcloudNodeMetadata.get().getId()).build();
        RasdItemsList virtualHardwareSectionDisks = vmApi.getVirtualHardwareSectionDisks(vm.getId());

        if (!Iterables.tryFind(virtualHardwareSectionDisks, NewScsiLogicSASDisk.SCSI_LSILOGICSAS_PREDICATE).isPresent()) {
            virtualHardwareSectionDisks = new AddScsiLogicSASBus().addScsiLogicSASBus(virtualHardwareSectionDisks);
        }

        RasdItem nextDisk = new NewScsiLogicSASDisk().apply(virtualHardwareSectionDisks);

        CimString newDiskHostResource = new CimString(Iterables.getOnlyElement(nextDisk.getHostResources()));
        Preconditions.checkNotNull(newDiskHostResource, "HostResource for the existing disk should not be null");
        newDiskHostResource.getOtherAttributes().put(new QName("http://www.vmware.com/vcloud/v1.5", "capacity"), "" + (64 * 1024));
        RasdItem newDiskToBeCreated = RasdItem.builder()
                .fromRasdItem(nextDisk) // The same AddressOnParent (SCSI Controller)
                .hostResources(ImmutableList.of(newDiskHostResource)) // NB! Use hostResources to override hostResources from newDisk
                .build();
        virtualHardwareSectionDisks.add(newDiskToBeCreated);
        Task task = vmApi.editVirtualHardwareSectionDisks(vm.getId(), virtualHardwareSectionDisks);
        Predicates2.retry(
                new TaskSuccess(taskApi),
                Predicates2.DEFAULT_PERIOD * 5L,
                Predicates2.DEFAULT_MAX_PERIOD * 5L,
                EDIT_VM_TIMEOUT_MS).apply(task);

        return new VcloudBlockDevice(newDiskToBeCreated, jcloudsMachineLocation, vm);
    }


    // TODO
    @Override
    public AttachedBlockDevice attachBlockDevice(JcloudsMachineLocation machine, BlockDevice blockDevice, BlockDeviceOptions options) {
        return new Devices.AttachedBlockDeviceImpl(machine, null, "/dev/sdb");
    }

    @Override
    public BlockDevice detachBlockDevice(AttachedBlockDevice attachedBlockDevice) {
        return null;
    }

    @Override
    public void deleteBlockDevice(BlockDevice blockDevice) {

    }

    protected Volume waitForVolumeToBeAvailable(final BlockDevice device) {
        final AtomicReference<Volume> lastVolume = new AtomicReference<Volume>();

        boolean available = Repeater.create("waiting for volume available:" + device)
                .every(1, TimeUnit.SECONDS)
                .limitTimeTo(60, TimeUnit.SECONDS)
                .until(new Callable<Boolean>() {
                    @Override
                    public Boolean call() throws Exception {
//                        Volume volume = describeVolume(device);
//                        lastVolume.set(volume);
//                        return volume.getStatus() == Volume.Status.AVAILABLE;
                        return null;
                    }})
                .run();

        if (!available) {
//            LOG.error("Volume {} still not available. Last known was: {}; continuing", device, lastVolume.get());
        }

        return lastVolume.get();
    }
}
