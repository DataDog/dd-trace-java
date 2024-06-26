package datadog.trace.instrumentation.testng;

import datadog.trace.api.civisibility.DDTest;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.util.AgentThreadFactory;
import org.testng.ITestResult;

public abstract class TestEventsHandlerHolder {

  public static volatile TestEventsHandler<TestSuiteDescriptor, ITestResult> TEST_EVENTS_HANDLER;

  private static ContextStore<ITestResult, DDTest> TEST_STORE;

  static {
    Runtime.getRuntime()
        .addShutdownHook(
            AgentThreadFactory.newAgentThread(
                AgentThreadFactory.AgentThread.CI_TEST_EVENTS_SHUTDOWN_HOOK,
                TestEventsHandlerHolder::stop,
                false));
  }

  public static synchronized void setContextStore(ContextStore<ITestResult, DDTest> testStore) {
    if (TEST_STORE == null) {
      TEST_STORE = testStore;
    }
  }

  public static void start() {
    TEST_EVENTS_HANDLER = InstrumentationBridge.createTestEventsHandler("testng", null, TEST_STORE);
  }

  public static void stop() {
    if (TEST_EVENTS_HANDLER != null) {
      TEST_EVENTS_HANDLER.close();
      TEST_EVENTS_HANDLER = null;
    }
  }

  private TestEventsHandlerHolder() {}
}
