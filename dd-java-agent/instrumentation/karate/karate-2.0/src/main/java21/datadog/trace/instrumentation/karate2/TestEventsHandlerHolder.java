package datadog.trace.instrumentation.karate2;

import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.api.internal.VisibleForTesting;

public abstract class TestEventsHandlerHolder {

  public static volatile TestEventsHandler<TestSuiteDescriptor, TestDescriptor> TEST_EVENTS_HANDLER;

  static {
    start();
  }

  public static void start() {
    TEST_EVENTS_HANDLER =
        InstrumentationBridge.createTestEventsHandler(
            "karate", null, null, KarateUtils.capabilities());
  }

  @VisibleForTesting
  public static void stop() {
    if (TEST_EVENTS_HANDLER != null) {
      TEST_EVENTS_HANDLER.close();
      TEST_EVENTS_HANDLER = null;
    }
  }

  private TestEventsHandlerHolder() {}
}
