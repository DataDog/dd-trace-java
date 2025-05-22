package datadog.trace.instrumentation.junit4;

import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.config.LibraryCapability;
import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.util.ConcurrentEnumMap;
import java.util.Collection;
import java.util.Map;

public abstract class TestEventsHandlerHolder {

  // store one handler per framework running
  public static final Map<
          TestFrameworkInstrumentation, TestEventsHandler<TestSuiteDescriptor, TestDescriptor>>
      HANDLERS = new ConcurrentEnumMap<>(TestFrameworkInstrumentation.class);

  public static synchronized void start(
      TestFrameworkInstrumentation framework, Collection<LibraryCapability> capabilities) {
    TestEventsHandler<TestSuiteDescriptor, TestDescriptor> handler = HANDLERS.get(framework);
    if (handler == null) {
      HANDLERS.put(
          framework,
          InstrumentationBridge.createTestEventsHandler(
              framework.name().toLowerCase(), null, null, capabilities));
    }
  }

  /** Used by instrumentation tests */
  public static synchronized void stop(TestFrameworkInstrumentation framework) {
    TestEventsHandler<TestSuiteDescriptor, TestDescriptor> handler = HANDLERS.remove(framework);
    if (handler != null) {
      handler.close();
    }
  }

  private TestEventsHandlerHolder() {}
}
