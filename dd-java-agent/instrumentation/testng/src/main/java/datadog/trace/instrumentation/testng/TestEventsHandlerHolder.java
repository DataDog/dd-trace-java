package datadog.trace.instrumentation.testng;

import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import java.nio.file.Paths;

public abstract class TestEventsHandlerHolder {

  public static final TestEventsHandler TEST_EVENTS_HANDLER =
      InstrumentationBridge.createTestEventsHandler("testng", Paths.get("").toAbsolutePath());

  private TestEventsHandlerHolder() {}
}
