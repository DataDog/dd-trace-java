package datadog.trace.instrumentation.karate;

import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;

public abstract class TestEventsHandlerHolder {

  public static volatile TestEventsHandler<TestSuiteDescriptor, TestDescriptor> TEST_EVENTS_HANDLER;

  static {
    start();
  }

  public static synchronized void start() {
    if (TEST_EVENTS_HANDLER == null) {
      TEST_EVENTS_HANDLER =
          InstrumentationBridge.createTestEventsHandler(
              "karate", null, null, KarateUtils.capabilities(KarateTracingHook.FRAMEWORK_VERSION));
    }
  }

  public static synchronized TestEventsHandler<TestSuiteDescriptor, TestDescriptor> getHandler() {
    if (TEST_EVENTS_HANDLER == null) {
      start();
    }
    return TEST_EVENTS_HANDLER;
  }

  /** Used by instrumentation tests */
  public static void stop() {
    if (TEST_EVENTS_HANDLER != null) {
      TEST_EVENTS_HANDLER.close();
      TEST_EVENTS_HANDLER = null;
    }
  }

  private TestEventsHandlerHolder() {}
}
