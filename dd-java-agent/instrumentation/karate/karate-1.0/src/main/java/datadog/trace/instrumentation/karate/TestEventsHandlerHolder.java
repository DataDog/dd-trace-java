package datadog.trace.instrumentation.karate;

import com.intuit.karate.core.FeatureRuntime;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public abstract class TestEventsHandlerHolder {

  @SuppressFBWarnings("PA_PUBLIC_PRIMITIVE_ATTRIBUTE")
  public static volatile TestEventsHandler<FeatureRuntime, TestDescriptor> TEST_EVENTS_HANDLER;

  static {
    start();
  }

  public static void start() {
    TEST_EVENTS_HANDLER =
        InstrumentationBridge.createTestEventsHandler(
            "karate", null, null, KarateUtils.capabilities(KarateTracingHook.FRAMEWORK_VERSION));
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
