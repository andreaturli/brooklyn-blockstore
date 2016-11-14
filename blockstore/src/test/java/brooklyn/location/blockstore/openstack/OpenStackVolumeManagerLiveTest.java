package brooklyn.location.blockstore.openstack;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

import com.google.common.collect.ImmutableList;
import org.apache.brooklyn.core.internal.BrooklynProperties;
import org.apache.brooklyn.location.jclouds.JcloudsLocation;
import org.apache.brooklyn.location.jclouds.JcloudsSshMachineLocation;
import org.apache.brooklyn.util.text.Identifiers;
import org.jclouds.openstack.cinder.v1.domain.Volume;
import org.testng.annotations.Test;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

import brooklyn.location.blockstore.AbstractVolumeManagerLiveTest;
import brooklyn.location.blockstore.api.BlockDevice;

@Test
public class OpenStackVolumeManagerLiveTest extends AbstractVolumeManagerLiveTest {

    public static final String PROVIDER = "openstack-nova";
    public static final String ENDPOINT = "https://cloudsoft2-lon.openstack.blueboxgrid.com:5000/v2.0/";
    public static final String LOCATION_SPEC = PROVIDER+":"+ENDPOINT;
    public static final String NAMED_LOCATION = "OpenStackVolumeManagerLiveTest" + Identifiers.makeRandomId(4);
    public static final String IMAGE_NAME_REGEX = "CentOS 7";

    @Override
    protected String getProvider() {
        return PROVIDER;
    }

    @Override
    protected void addBrooklynProperties(BrooklynProperties props) {
        // re-using rackspace credentials, but pointing at it as a raw OpenStack nova endpoint
        Object identity = props.get(BROOKLYN_PROPERTIES_JCLOUDS_PREFIX+"openstack-cinder.identity");
        Object credential = props.get(BROOKLYN_PROPERTIES_JCLOUDS_PREFIX+"openstack-cinder.credential");
        Object autoGenerateKeypairs = props.get(BROOKLYN_PROPERTIES_JCLOUDS_PREFIX+"openstack-nova.auto-generate-keypairs");
        Object keyPair = props.get(BROOKLYN_PROPERTIES_JCLOUDS_PREFIX+"openstack-nova.keyPair");
        Object privateKeyFile = props.get(BROOKLYN_PROPERTIES_JCLOUDS_PREFIX+"openstack-nova.loginUser.privateKeyFile");
        Object keystoneCredentialType = props.get(BROOKLYN_PROPERTIES_JCLOUDS_PREFIX+"openstack-nova.jclouds.keystone.credential-type");
        props.put("brooklyn.location.named."+NAMED_LOCATION, LOCATION_SPEC);
        props.put("brooklyn.location.named."+NAMED_LOCATION+".identity", identity);
        props.put("brooklyn.location.named."+NAMED_LOCATION+".credential", credential);
        props.put("brooklyn.location.named."+NAMED_LOCATION+".region", "RegionOne");
        props.put("brooklyn.location.named."+NAMED_LOCATION+".jclouds.openstack-nova.auto-generate-keypairs", autoGenerateKeypairs);
        props.put("brooklyn.location.named."+NAMED_LOCATION+".keyPair", keyPair);
        props.put("brooklyn.location.named."+NAMED_LOCATION+".loginUser.privateKeyFile", privateKeyFile);
        props.put("brooklyn.location.named."+NAMED_LOCATION+".credential-type", keystoneCredentialType);
    }

    @Override
    protected JcloudsLocation createJcloudsLocation() {
        return (JcloudsLocation) ctx.getLocationRegistry().getLocationManaged("named:"+NAMED_LOCATION);
    }
    
    @Override
    protected int getVolumeSize() {
        return 100; // min on rackspace is 100
    }

    @Override
    protected String getDefaultAvailabilityZone() {
        return null;
    }

    @Override
    protected void assertVolumeAvailable(BlockDevice device) {
        Volume volume = ((AbstractOpenstackVolumeManager)volumeManager).describeVolume(device);
        assertNotNull(volume);
        assertEquals(volume.getStatus(), Volume.Status.AVAILABLE);
    }

    @Override
    protected Optional<JcloudsSshMachineLocation> rebindJcloudsMachine() {
        return Optional.absent();
    }
    
    @Override
    protected JcloudsSshMachineLocation createJcloudsMachine() throws Exception {
        // TODO Wanted to specify hardware id, but this failed; and wanted to force no imageId (in case specified in brooklyn.properties)
        return (JcloudsSshMachineLocation) jcloudsLocation.obtain(ImmutableMap.builder()
                .put(JcloudsLocation.IMAGE_NAME_REGEX, IMAGE_NAME_REGEX)
                .put("generate.hostname", true)
                .put("loginUser", "centos")
                .put("user", "amp")
                .put("securityGroups", "VPN_local")
                .put("auto-generate-keypairs", true)
                .put("privateKeyFile", "~/.ssh/openstack.pem")
                .put("templateOptions", ImmutableMap.of(
                        "networks", ImmutableList.of("426bb8f6-c8c7-4f84-ad3c-19f66b28a288")
                ))
                .put("cloudMachineNamer", "org.apache.brooklyn.core.location.cloud.names.CustomMachineNamer")
                .put("minRam", "2000")
                .put("custom.machine.namer.machine", "QA-valentin-xxxx")
                .build());
    }
}
