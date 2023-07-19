package datadog.trace.instrumentation.junit5;

import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import java.nio.file.Paths;

public abstract class TestEventsHandlerHolder {

  public static volatile TestEventsHandler TEST_EVENTS_HANDLER;

  static {
    reset();
  }

  private TestEventsHandlerHolder() {}

  // TODO a hack to work around shared state problems in integration tests;
  //  to be removed later
  public static void reset() {
    TEST_EVENTS_HANDLER =
        InstrumentationBridge.createTestEventsHandler("junit", Paths.get("").toAbsolutePath());
  }
}
