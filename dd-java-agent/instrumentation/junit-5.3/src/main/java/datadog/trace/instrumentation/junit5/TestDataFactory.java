package datadog.trace.instrumentation.junit5;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.UniqueId;

public abstract class TestDataFactory {

  private TestDataFactory() {}

  private static volatile Map<String, Function<TestDescriptor, TestIdentifier>>
      TEST_IDENTIFIER_FACTORY_BY_ENGINE_ID = Collections.emptyMap();

  private static volatile Map<String, Function<TestDescriptor, TestSourceData>>
      TEST_SOURCE_DATA_FACTORY_BY_ENGINE_ID = Collections.emptyMap();

  private static volatile Map<String, Predicate<TestDescriptor>>
      TEST_DESCRIPTOR_FILTER_BY_ENGINE_ID = Collections.emptyMap();

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
    UniqueId uniqueId = testDescriptor.getUniqueId();
    return uniqueId
        .getEngineId()
        .map(TEST_IDENTIFIER_FACTORY_BY_ENGINE_ID::get)
        .orElse(JUnitPlatformUtils::toTestIdentifier)
        .apply(testDescriptor);
  }

  public static TestSourceData createTestSourceData(TestDescriptor testDescriptor) {
    UniqueId uniqueId = testDescriptor.getUniqueId();
    return uniqueId
        .getEngineId()
        .map(TEST_SOURCE_DATA_FACTORY_BY_ENGINE_ID::get)
        .orElse(JUnitPlatformUtils::toTestSourceData)
        .apply(testDescriptor);
  }

  public static boolean shouldBeTraced(TestDescriptor testDescriptor) {
    UniqueId uniqueId = testDescriptor.getUniqueId();
    return uniqueId
        .getEngineId()
        .map(TEST_DESCRIPTOR_FILTER_BY_ENGINE_ID::get)
        .map(filter -> filter.test(testDescriptor))
        .orElse(true);
  }
}
