package datadog.trace.instrumentation.junit5;

import datadog.trace.api.civisibility.DDTest;
import datadog.trace.api.civisibility.DDTestSuite;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.util.AgentThreadFactory;
import org.junit.platform.engine.TestDescriptor;

public abstract class TestEventsHandlerHolder {

  public static volatile ContextStore<TestDescriptor, DDTestSuite> SUITE_STORE;
  public static volatile ContextStore<TestDescriptor, DDTest> TEST_STORE;
  public static volatile TestEventsHandler<TestDescriptor, TestDescriptor> TEST_EVENTS_HANDLER;

  static {
    Runtime.getRuntime()
        .addShutdownHook(
            AgentThreadFactory.newAgentThread(
                AgentThreadFactory.AgentThread.CI_TEST_EVENTS_SHUTDOWN_HOOK,
                TestEventsHandlerHolder::stop,
                false));
  }

  public static void start() {
    TEST_EVENTS_HANDLER =
        InstrumentationBridge.createTestEventsHandler("junit", SUITE_STORE, TEST_STORE);
  }

  public static void stop() {
    if (TEST_EVENTS_HANDLER != null) {
      TEST_EVENTS_HANDLER.close();
      TEST_EVENTS_HANDLER = null;
    }
  }

  private TestEventsHandlerHolder() {}
}
