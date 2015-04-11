package brooklyn.location.blockstore;

import static brooklyn.location.blockstore.AbstractVolumeManagerLiveTest.assertExecSucceeds;
import static brooklyn.util.ssh.BashCommands.sudo;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import brooklyn.config.BrooklynProperties;
import brooklyn.entity.basic.Entities;
import brooklyn.location.Location;
import brooklyn.location.blockstore.api.BlockDevice;
import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.location.jclouds.JcloudsLocationCustomizer;
import brooklyn.location.jclouds.JcloudsSshMachineLocation;
import brooklyn.management.ManagementContext;
import brooklyn.management.internal.LocalManagementContext;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

public abstract class AbstractVolumeCustomizerLiveTest {

    // FIXME Delete volume? Or will it automatically be deleted when VM is deleted for all clouds?!
    
    private static final Logger LOG = LoggerFactory.getLogger(AbstractVolumeCustomizerLiveTest.class);

    protected BrooklynProperties brooklynProperties;
    protected ManagementContext ctx;
    
    protected JcloudsLocation jcloudsLocation;
    protected JcloudsLocationCustomizer volumeCustomizer;
    protected BlockDevice volume;
    protected JcloudsSshMachineLocation machine;
    
    protected abstract String getProvider();
    protected abstract JcloudsLocation createJcloudsLocation();
    protected abstract int getVolumeSize();
    protected abstract List<String> getMountPoints();
    protected abstract Map<?,?> additionalObtainArgs() throws Exception;

    protected JcloudsSshMachineLocation createJcloudsMachine(List<Integer> capacities) throws Exception {
        Map<String, String> tags = ImmutableMap.of(
                "user", truncate(System.getProperty("user.name"), maxTagLength()),
                "purpose", truncate("brooklyn-blockstore-VolumeCustomizerLiveTest", maxTagLength()));
        
        try {
            return jcloudsLocation.obtain(ImmutableMap.builder()
                    .putAll(additionalObtainArgs())
                    .put(JcloudsLocation.USER_METADATA_MAP, tags)
                    .put(JcloudsLocation.STRING_TAGS, tags.values())
                    .put(JcloudsLocation.JCLOUDS_LOCATION_CUSTOMIZER, createVolumeCustomizer(jcloudsLocation, capacities))
                    .build());
        } catch (Exception e) {
            throw e;
        }
    }
    
    @BeforeMethod(alwaysRun=true)
    public void setUp() throws Exception {
        ctx = new LocalManagementContext();
        brooklynProperties = (BrooklynProperties) ctx.getConfig();
        AbstractVolumeManagerLiveTest.stripBrooklynProperties(brooklynProperties);

        jcloudsLocation = createJcloudsLocation();
    }

    @AfterMethod(alwaysRun=true)
    public void tearDown() throws Exception {
        try {
            if (machine != null) {
                jcloudsLocation.release(machine);
            }
        } finally {
            machine = null;
            
            if (ctx != null) {
                Entities.destroyAll(ctx);
                ctx = null;
            }
        }
    }

    // for overriding - e.g. on SoftLayer, it's 20
    protected int maxTagLength() {
        return 128;
    }
    
    protected void addBrooklynProperties(BrooklynProperties props) {
        // no-op; for overriding
    }

    protected JcloudsLocationCustomizer createVolumeCustomizer(Location location, List<Integer> capacities) {
        return VolumeCustomizers.newVolumesCustomizer(location, capacities);
    }

    @Test(groups="Live")
    public void testCreateVmWithAttachedVolume() throws Throwable {
        // TODO Mount more than one volume
        int volumeSize = getVolumeSize();
        List<String> mountPoints = getMountPoints();
        
        machine = createJcloudsMachine(ImmutableList.of(volumeSize, volumeSize));
        
        for (String mountPoint : mountPoints) {
            assertExecSucceeds(machine, "show mount points", ImmutableList.of(
                    "mount -l", "mount -l | grep \""+mountPoint+"\""));
            assertExecSucceeds(machine, "list mount contents", ImmutableList.of("ls -la "+mountPoint));
        
            String tmpDestFile = "/tmp/myfile.txt";
            String destFile = mountPoint+"/myfile.txt";
            machine.copyTo(new ByteArrayInputStream("abc".getBytes()), tmpDestFile);
            assertExecSucceeds(machine, "write to mount point", ImmutableList.of(
                    sudo("cp "+tmpDestFile+" "+destFile)));
        }
    }
    
    private static String truncate(String s, int length) {
        return (s.length() <= length) ? s : s.substring(0, length);
    }
}
