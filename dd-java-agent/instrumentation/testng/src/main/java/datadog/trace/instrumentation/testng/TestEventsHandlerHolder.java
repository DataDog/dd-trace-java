package datadog.trace.instrumentation.testng;

import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.util.AgentThreadFactory;

public abstract class TestEventsHandlerHolder {

  public static volatile TestEventsHandler<TestSuiteDescriptor, TestDescriptor> TEST_EVENTS_HANDLER;

  static {
    start();
    Runtime.getRuntime()
        .addShutdownHook(
            AgentThreadFactory.newAgentThread(
                AgentThreadFactory.AgentThread.CI_TEST_EVENTS_SHUTDOWN_HOOK,
                TestEventsHandlerHolder::stop,
                false));
  }

  public static void start() {
    TEST_EVENTS_HANDLER = InstrumentationBridge.createTestEventsHandler("testng");
  }

  public static void stop() {
    if (TEST_EVENTS_HANDLER != null) {
      TEST_EVENTS_HANDLER.close();
      TEST_EVENTS_HANDLER = null;
    }
  }

  private TestEventsHandlerHolder() {}
}
