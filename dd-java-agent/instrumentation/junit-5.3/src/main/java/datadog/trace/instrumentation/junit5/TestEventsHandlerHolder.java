package datadog.trace.instrumentation.junit5;

import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.util.AgentThreadFactory;
import java.nio.file.Paths;

public abstract class TestEventsHandlerHolder {

  public static volatile TestEventsHandler TEST_EVENTS_HANDLER;

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
    TEST_EVENTS_HANDLER =
        InstrumentationBridge.createTestEventsHandler("junit", Paths.get("").toAbsolutePath());
  }

  public static void stop() {
    if (TEST_EVENTS_HANDLER != null) {
      TEST_EVENTS_HANDLER.close();
      TEST_EVENTS_HANDLER = null;
    }
  }

  private TestEventsHandlerHolder() {}
}
