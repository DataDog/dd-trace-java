package datadog.trace.instrumentation.junit5.execution;

import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.util.MethodHandles;
import datadog.trace.util.UnsafeUtils;
import java.lang.invoke.MethodHandle;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import org.junit.platform.commons.util.ClassLoaderUtils;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;

public class TestDescriptorHandle {

  private static final MethodHandles METHOD_HANDLES =
      new MethodHandles(ClassLoaderUtils.getDefaultClassLoader());

  private static final MethodHandle UNIQUE_ID_SETTER =
      METHOD_HANDLES.privateFieldSetter(AbstractTestDescriptor.class, "uniqueId");

  public static final class MuzzleHelper {
    public static Collection<? extends Reference> compileReferences() {
      return Collections.singletonList(
          new Reference.Builder(AbstractTestDescriptor.class.getName())
              .withField(new String[0], 0, "uniqueId", "Lorg/junit/platform/engine/UniqueId;")
              .build());
    }
  }

  private final TestDescriptor testDescriptor;

  public TestDescriptorHandle(TestDescriptor testDescriptor) {
    /*
     * We're cloning the descriptor to preserve its original state:
     * JUnit will modify some of its fields during and after test execution
     * (one example is parameterized test descriptor,
     * whose invocation context is overwritten with null).
     * Cloning has to be done before each test retry to
     * compensate for the state modifications.
     */
    this.testDescriptor = UnsafeUtils.tryShallowClone(testDescriptor);
  }

  public TestDescriptor withIdSuffix(Map<String, Object> suffices) {
    UniqueId updatedId = testDescriptor.getUniqueId();
    for (Map.Entry<String, Object> e : suffices.entrySet()) {
      updatedId = updatedId.append(e.getKey(), String.valueOf(e.getValue()));
    }

    TestDescriptor descriptorClone = UnsafeUtils.tryShallowClone(testDescriptor);
    METHOD_HANDLES.invoke(UNIQUE_ID_SETTER, descriptorClone, updatedId);
    return descriptorClone;
  }
}
