package datadog.trace.instrumentation.junit5.execution;

import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.instrumentation.junit5.JUnitPlatformUtils;
import datadog.trace.util.MethodHandles;
import datadog.trace.util.UnsafeUtils;
import java.lang.invoke.MethodHandle;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.platform.commons.util.ClassLoaderUtils;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;

public class TestDescriptorHandle {

  private static final MethodHandles METHOD_HANDLES =
      new MethodHandles(ClassLoaderUtils.getDefaultClassLoader());

  private static final String JUPITER_TEST_DESCRIPTOR =
      "org.junit.jupiter.engine.descriptor.JupiterTestDescriptor";
  private static final Class<?> JUPITER_TEST_DESCRIPTOR_CLASS =
      JUnitPlatformUtils.loadClass(JUPITER_TEST_DESCRIPTOR);

  /** {@code JupiterTestDescriptor#copyIncludingDescendants(UnaryOperator<UniqueId>)} (5.13+) */
  private static final MethodHandle COPY_INCLUDING_DESCENDANTS =
      METHOD_HANDLES.method(
          JUPITER_TEST_DESCRIPTOR, "copyIncludingDescendants", UnaryOperator.class);

  // Legacy fallback used when copyIncludingDescendants is unavailable.
  // Overwrites the final unique ID field by reflection. Lazily created to avoid JEP 500 warnings.
  private static volatile MethodHandle uniqueIdSetter;

  private static MethodHandle uniqueIdSetter() {
    MethodHandle handle = uniqueIdSetter;
    if (handle == null) {
      handle = METHOD_HANDLES.privateFieldSetter(AbstractTestDescriptor.class, "uniqueId");
      uniqueIdSetter = handle;
    }
    return handle;
  }

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
     * We're copying the descriptor to preserve its original state:
     * JUnit will modify some of its fields during and after test execution
     * (one example is parameterized test descriptor,
     * whose invocation context is overwritten with null).
     * The snapshot is taken before the first execution so that every retry
     * can be derived from the pristine state.
     */
    this.testDescriptor = copy(testDescriptor, UnaryOperator.identity());
  }

  public TestDescriptor withIdSuffix(Map<String, Object> suffices) {
    return copy(
        testDescriptor,
        id -> {
          UniqueId updatedId = id;
          for (Map.Entry<String, Object> e : suffices.entrySet()) {
            updatedId = updatedId.append(e.getKey(), String.valueOf(e.getValue()));
          }
          return updatedId;
        });
  }

  private static TestDescriptor copy(
      TestDescriptor testDescriptor, UnaryOperator<UniqueId> idTransform) {
    if (COPY_INCLUDING_DESCENDANTS != null
        && JUPITER_TEST_DESCRIPTOR_CLASS != null
        && JUPITER_TEST_DESCRIPTOR_CLASS.isInstance(testDescriptor)) {
      TestDescriptor copy =
          METHOD_HANDLES.invoke(COPY_INCLUDING_DESCENDANTS, testDescriptor, idTransform);
      if (copy != null) {
        // copyIncludingDescendants returns a detached copy so we link it back to its suite
        if (copy instanceof AbstractTestDescriptor) {
          ((AbstractTestDescriptor) copy).setParent(testDescriptor.getParent().orElse(null));
        }
        return copy;
      }
    }

    // per-engine reconstruction (Spock, Cucumber)
    RetryDescriptorFactory factory =
        RetryDescriptorFactories.forEngine(JUnitPlatformUtils.getEngineId(testDescriptor));
    if (factory != null) {
      TestDescriptor copy = factory.copy(testDescriptor, idTransform);
      if (copy != null) {
        // reconstructed descriptors are detached, so we link them back to the original's suite
        if (copy instanceof AbstractTestDescriptor) {
          ((AbstractTestDescriptor) copy).setParent(testDescriptor.getParent().orElse(null));
        }
        return copy;
      }
    }

    return legacyCopy(testDescriptor, idTransform);
  }

  /**
   * Fallback for engines without {@code copyIncludingDescendants}: shallow-clone the descriptor and
   * overwrite the cloned unique ID field by reflection. Not JEP 500 compliant.
   */
  private static TestDescriptor legacyCopy(
      TestDescriptor testDescriptor, UnaryOperator<UniqueId> idTransform) {
    TestDescriptor descriptorClone = UnsafeUtils.tryShallowClone(testDescriptor);
    UniqueId updatedId = idTransform.apply(testDescriptor.getUniqueId());
    if (descriptorClone != testDescriptor && !updatedId.equals(testDescriptor.getUniqueId())) {
      METHOD_HANDLES.invoke(uniqueIdSetter(), descriptorClone, updatedId);
    }
    return descriptorClone;
  }
}
