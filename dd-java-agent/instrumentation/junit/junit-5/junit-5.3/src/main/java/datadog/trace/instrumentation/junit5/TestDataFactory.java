package datadog.trace.instrumentation.junit5;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import org.junit.platform.engine.TestDescriptor;

public abstract class TestDataFactory {

  private TestDataFactory() {}

  private static volatile Map<String, Function<TestDescriptor, TestIdentifier>>
      TEST_IDENTIFIER_FACTORY_BY_ENGINE_ID = Collections.emptyMap();

  private static volatile Map<String, Function<TestDescriptor, TestSourceData>>
      TEST_SOURCE_DATA_FACTORY_BY_ENGINE_ID = Collections.emptyMap();

  private static volatile Map<String, Predicate<TestDescriptor>>
      TEST_DESCRIPTOR_FILTER_BY_ENGINE_ID = Collections.emptyMap();

  @SuppressFBWarnings(
      value = "USO_UNSAFE_STATIC_METHOD_SYNCHRONIZATION",
      justification = "Holder class not exposed to application code; locking on its Class is safe")
  public static synchronized void register(
      String engineId,
      Function<TestDescriptor, TestIdentifier> testIdentifierFactory,
      Function<TestDescriptor, TestSourceData> testSourceDataFactory,
      @Nullable Predicate<TestDescriptor> testDescriptorFilter) {
    TEST_IDENTIFIER_FACTORY_BY_ENGINE_ID =
        addEntry(TEST_IDENTIFIER_FACTORY_BY_ENGINE_ID, engineId, testIdentifierFactory);
    TEST_SOURCE_DATA_FACTORY_BY_ENGINE_ID =
        addEntry(TEST_SOURCE_DATA_FACTORY_BY_ENGINE_ID, engineId, testSourceDataFactory);
    if (testDescriptorFilter != null) {
      TEST_DESCRIPTOR_FILTER_BY_ENGINE_ID =
          addEntry(TEST_DESCRIPTOR_FILTER_BY_ENGINE_ID, engineId, testDescriptorFilter);
    }
  }

  private static <K, V> Map<K, V> addEntry(Map<K, V> originalMap, K key, V value) {
    Map<K, V> updatedMap = new HashMap<>(originalMap);
    updatedMap.put(key, value);
    return updatedMap;
  }

  public static TestIdentifier createTestIdentifier(TestDescriptor testDescriptor) {
    return engineId(testDescriptor)
        .map(TEST_IDENTIFIER_FACTORY_BY_ENGINE_ID::get)
        .orElse(JUnitPlatformUtils::toTestIdentifier)
        .apply(testDescriptor);
  }

  public static TestSourceData createTestSourceData(TestDescriptor testDescriptor) {
    return engineId(testDescriptor)
        .map(TEST_SOURCE_DATA_FACTORY_BY_ENGINE_ID::get)
        .orElse(JUnitPlatformUtils::toTestSourceData)
        .apply(testDescriptor);
  }

  public static boolean shouldBeTraced(TestDescriptor testDescriptor) {
    return engineId(testDescriptor)
        .map(TEST_DESCRIPTOR_FILTER_BY_ENGINE_ID::get)
        .map(filter -> filter.test(testDescriptor))
        .orElse(true);
  }

  /**
   * Resolves the innermost {@code engine} segment rather than the root one returned by the JUnit
   * Platform {@code UniqueId#getEngineId()}.
   *
   * <p>Per-engine factories are registered under leaf engine ids ({@code cucumber}, {@code spock},
   * ...). When a framework runs nested under {@code junit-platform-suite-engine}, the unique id is
   * rooted at the suite engine (e.g. {@code
   * [engine:junit-platform-suite]/[suite:...]/[engine:cucumber]/...}).
   */
  private static Optional<String> engineId(TestDescriptor testDescriptor) {
    return Optional.ofNullable(JUnitPlatformUtils.getEngineId(testDescriptor));
  }
}
