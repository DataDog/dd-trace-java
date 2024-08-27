package datadog.trace.api.civisibility;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.api.civisibility.domain.TestContext;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.Nullable;

/**
 * Allows non-test instrumentations to register listeners that will be notified about test events.
 */
public abstract class InstrumentationTestBridge {

  private static final CopyOnWriteArrayList<TestListener> TEST_LISTENERS =
      new CopyOnWriteArrayList<>();

  private InstrumentationTestBridge() {}

  @Nullable
  public static TestContext getCurrentTestContext() {
    AgentSpan span = activeSpan();
    if (span == null) {
      return null;
    }
    RequestContext requestContext = span.getRequestContext();
    if (requestContext == null) {
      return null;
    }
    return requestContext.getData(RequestContextSlot.CI_VISIBILITY);
  }

  public static void fireBeforeTestEnd(TestContext testContext) {
    for (TestListener testListener : TEST_LISTENERS) {
      testListener.beforeTestEnd(testContext);
    }
  }

  public static void registerListener(TestListener listener) {
    TEST_LISTENERS.addIfAbsent(listener);
  }

  public interface TestListener {
    void beforeTestEnd(TestContext testContext);
  }
}
