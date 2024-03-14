package datadog.trace.instrumentation.junit5;

import datadog.trace.api.civisibility.config.TestIdentifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.UniqueId;

public abstract class TestIdentifierFactory {

  private TestIdentifierFactory() {}

  private static volatile Map<String, Function<TestDescriptor, TestIdentifier>>
      FACTORY_BY_ENGINE_ID = Collections.emptyMap();

  public static synchronized void register(
      String engineId, Function<TestDescriptor, TestIdentifier> factory) {
    Map<String, Function<TestDescriptor, TestIdentifier>> updated =
        new HashMap<>(FACTORY_BY_ENGINE_ID);
    updated.put(engineId, factory);
    FACTORY_BY_ENGINE_ID = updated;
  }

  public static TestIdentifier createTestIdentifier(TestDescriptor testDescriptor) {
    UniqueId uniqueId = testDescriptor.getUniqueId();
    return uniqueId
        .getEngineId()
        .map(FACTORY_BY_ENGINE_ID::get)
        .orElse(JUnitPlatformUtils::toTestIdentifier)
        .apply(testDescriptor);
  }
}
