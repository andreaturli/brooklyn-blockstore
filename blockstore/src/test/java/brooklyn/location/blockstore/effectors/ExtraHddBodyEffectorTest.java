package brooklyn.location.blockstore.effectors;

import brooklyn.location.blockstore.api.MountedBlockDevice;
import brooklyn.location.blockstore.api.VolumeOptions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import org.apache.brooklyn.api.effector.Effector;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.camp.brooklyn.AbstractYamlTest;
import org.apache.brooklyn.core.test.entity.TestEntity;
import org.apache.brooklyn.location.jclouds.JcloudsMachineLocation;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.core.flags.TypeCoercions;
import org.testng.annotations.Test;

import java.util.Map;

import static org.testng.Assert.assertEquals;

public class ExtraHddBodyEffectorTest extends AbstractYamlTest {

    @Test
    public void testEffectorIsProperlyAttached() throws Exception {

        Entity app = createAndStartApplication(
                "location:",
                "  localhost",
                "services:",
                "- type: " + TestEntity.class.getName(),
                "  brooklyn.initializers:",
                "  - type: brooklyn.location.blockstore.effectors.ExtraHddBodyEffector");
        waitForApplicationTasks(app);

        TestEntity entity = (TestEntity) Iterables.getOnlyElement(app.getChildren());
        Effector<?> effector = entity.getEntityType().getEffectorByName(ExtraHddBodyEffector.EXTRA_HDD_EFFECTOR_NAME).get();
        assertEffectorIsProperlyAttached(effector);
    }

    // Run with -da (disable assertions) die to bug in jclouds openstack-nova for not properly cloning template options
    @Test
    public void testEffectorFailsForLocationsNotOfJcloudsMachineLocationType() throws Exception {

        Entity app = createAndStartApplication(
                "location:",
                "  localhost",
                "services:",
                "- type: " + TestEntity.class.getName(),
                "  brooklyn.initializers:",
                "  - type: brooklyn.location.blockstore.effectors.ExtraHddBodyEffector");
        waitForApplicationTasks(app);

        TestEntity entity = (TestEntity) Iterables.getOnlyElement(app.getChildren());
        Effector<?> effector = entity.getEntityType().getEffectorByName(ExtraHddBodyEffector.EXTRA_HDD_EFFECTOR_NAME).get();

        String parameterInput = "{\n" +
                "  \"blockDevice\": {\n" +
                "    \"deviceSuffix\": \"h\",\n" +
                "    \"sizeInGb\": 4,\n" +
                "    \"deleteOnTermination\": true,\n" +
                "    \"tags\": {\n" +
                "      \"brooklyn\": \"br-test-1\"\n" +
                "    }\n" +
                "  },\n" +
                "  \"filesystem\": {\n" +
                "    \"mountPoint\": \"/mount/brooklyn/h\",\n" +
                "    \"filesystemType\": \"ext3\"\n" +
                "  }\n" +
                "}";

        try {
            entity.invoke(effector, ImmutableMap.<String, Object>of(ExtraHddBodyEffector.LOCATION_CUSTOMIZER_FIELDS.getName(), parameterInput)).get();
            Asserts.shouldHaveFailedPreviously();
        } catch (Exception e) {
            Asserts.expectedFailureOfType(e, IllegalStateException.class);
            Asserts.expectedFailureContains(e, "requires a single " + JcloudsMachineLocation.class.getName() + ", but has []");
        }
    }

    @Test
    public void testEffectorParametersAreProperlyDeserializedForAWS() throws Exception {

        String parameterInput = "{\n" +
                "  \"blockDevice\": {\n" +
                "    \"deviceSuffix\": \"h\",\n" +
                "    \"sizeInGb\": 4,\n" +
                "    \"deleteOnTermination\": true,\n" +
                "    \"tags\": {\n" +
                "      \"brooklyn\": \"br-test-1\"\n" +
                "    }\n" +
                "  },\n" +
                "  \"filesystem\": {\n" +
                "    \"mountPoint\": \"/mount/brooklyn/h\",\n" +
                "    \"filesystemType\": \"ext3\"\n" +
                "  }\n" +
                "}";

        Map<String, Map<String, ?>> parameterMap = TypeCoercions.coerce(parameterInput, Map.class);

        VolumeOptions transformed = VolumeOptions.fromMap(parameterMap);

        assertEquals(transformed.getBlockDeviceOptions().getSizeInGb(), 4);
        assertEquals(transformed.getBlockDeviceOptions().getDeviceSuffix(), 'h');
        assertEquals(transformed.getBlockDeviceOptions().getTags().get("brooklyn"), "br-test-1");

        assertEquals(transformed.getFilesystemOptions().getFilesystemType(), "ext3");
        assertEquals(transformed.getFilesystemOptions().getMountPoint(), "/mount/brooklyn/h");

        String parameterInputWithDoubleSizeInGB = "{\n" +
                "  \"blockDevice\": {\n" +
                "    \"deviceSuffix\": \"h\",\n" +
                "    \"sizeInGb\": 4.0,\n" +
                "    \"deleteOnTermination\": true,\n" +
                "    \"tags\": {\n" +
                "      \"brooklyn\": \"br-test-1\"\n" +
                "    }\n" +
                "  },\n" +
                "  \"filesystem\": {\n" +
                "    \"mountPoint\": \"/mount/brooklyn/h\",\n" +
                "    \"filesystemType\": \"ext3\"\n" +
                "  }\n" +
                "}";

        parameterMap = TypeCoercions.coerce(parameterInputWithDoubleSizeInGB, Map.class);

        transformed = VolumeOptions.fromMap(parameterMap);

        assertEquals(transformed.getBlockDeviceOptions().getSizeInGb(), 4);
    }

    @Test
    public void testBehaviourWithWrongParametersForAWS() {
        Map<String, Map<String, ?>> parameterMap;

        String parameterInputWithMissingSizeInGb = "{\n" +
                "  \"blockDevice\": {\n" +
                "    \"deviceSuffix\": \"h\",\n" +
                "    \"deleteOnTermination\": true,\n" +
                "    \"tags\": {\n" +
                "      \"brooklyn\": \"br-test-1\"\n" +
                "    }\n" +
                "  },\n" +
                "  \"filesystem\": {\n" +
                "    \"mountPoint\": \"/mount/brooklyn/h\",\n" +
                "    \"filesystemType\": \"ext3\"\n" +
                "  }\n" +
                "}";

        try {
            parameterMap = TypeCoercions.coerce(parameterInputWithMissingSizeInGb, Map.class);
            VolumeOptions.fromMap(parameterMap);
            Asserts.shouldHaveFailedPreviously("\"blockDevice\" should contain value for \"sizeInGb\"");
        } catch (Exception e) {
            Asserts.expectedFailureOfType(e, IllegalArgumentException.class);
            Asserts.expectedFailureContains(e, "\"blockDevice\" should contain value for \"sizeInGb\"");
        }

        String parameterInputWithNonIntegerSizeInGb = "{\n" +
                "  \"blockDevice\": {\n" +
                "    \"sizeInGb\": 1.7,\n" +
                "    \"deviceSuffix\": \"h\",\n" +
                "    \"deleteOnTermination\": true,\n" +
                "    \"tags\": {\n" +
                "      \"brooklyn\": \"br-test-1\"\n" +
                "    }\n" +
                "  },\n" +
                "  \"filesystem\": {\n" +
                "    \"mountPoint\": \"/mount/brooklyn/h\",\n" +
                "    \"filesystemType\": \"ext3\"\n" +
                "  }\n" +
                "}";

        try {
            parameterMap = TypeCoercions.coerce(parameterInputWithNonIntegerSizeInGb, Map.class);
            VolumeOptions.fromMap(parameterMap);
            Asserts.shouldHaveFailedPreviously("Trying to set block device with not allowed sizeInGb value");
        } catch (Exception e) {
            Asserts.expectedFailureOfType(e, UnsupportedOperationException.class);
            Asserts.expectedFailureContains(e, "sizeInGb must have integer value.");
        }

        String parameterInputWithWrongValueTypes = "{\n" +
                "  \"blockDevice\": {\n" +
                "    \"deviceSuffix\": \"h\",\n" +
                "    \"sizeInGb\": \"dummy-value\",\n" +
                "    \"deleteOnTermination\": true,\n" +
                "    \"tags\": {\n" +
                "    \t\"brooklyn\": \"br-test-1\"\n" +
                "    }\n" +
                "  },\n" +
                "  \"filesystem\": {\n" +
                "    \"mountPoint\": \"/mount/brooklyn/h\",\n" +
                "    \"filesystemType\": \"ext3\"\n" +
                "  }\n" +
                "}";

        try {
            parameterMap = TypeCoercions.coerce(parameterInputWithWrongValueTypes, Map.class);
            VolumeOptions.fromMap(parameterMap);
            Asserts.shouldHaveFailedPreviously();
        } catch (Exception e) {
            Asserts.expectedFailureOfType(e, ClassCastException.class);
        }

    }

    private void assertEffectorIsProperlyAttached(Effector<?> effector) {
        assertEquals("addExtraHdd", effector.getName());
        assertEquals("An effector to add extra hdd to provisioned vm", effector.getDescription());
        assertEquals(MountedBlockDevice.class, effector.getReturnType());
        assertEquals(1, effector.getParameters().size());
        assertEquals(ExtraHddBodyEffector.LOCATION_CUSTOMIZER_FIELDS.getType(), effector.getParameters().iterator().next().getParameterClass());
    }
}
