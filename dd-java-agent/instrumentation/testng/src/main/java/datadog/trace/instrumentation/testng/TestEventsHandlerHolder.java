package datadog.trace.instrumentation.testng;

import datadog.trace.api.civisibility.DDTest;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.bootstrap.ContextStore;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.testng.ITestResult;

public abstract class TestEventsHandlerHolder {

  @SuppressFBWarnings("PA_PUBLIC_PRIMITIVE_ATTRIBUTE")
  public static volatile TestEventsHandler<TestSuiteDescriptor, ITestResult> TEST_EVENTS_HANDLER;

  private static ContextStore<ITestResult, DDTest> TEST_STORE;

  public static synchronized void setContextStore(ContextStore<ITestResult, DDTest> testStore) {
    if (TEST_STORE == null) {
      TEST_STORE = testStore;
    }
  }

  public static void start() {
    TEST_EVENTS_HANDLER =
        InstrumentationBridge.createTestEventsHandler(
            "testng", null, TEST_STORE, TestNGUtils.capabilities(TestNGUtils.getTestNGVersion()));
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
