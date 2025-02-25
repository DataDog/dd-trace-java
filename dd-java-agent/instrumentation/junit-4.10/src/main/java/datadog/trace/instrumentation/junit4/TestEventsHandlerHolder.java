package datadog.trace.instrumentation.junit4;

import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.util.AgentThreadFactory;
import java.util.HashMap;
import java.util.Map;

public abstract class TestEventsHandlerHolder {

  // store one handler per framework running
  public static Map<
          TestFrameworkInstrumentation, TestEventsHandler<TestSuiteDescriptor, TestDescriptor>>
      HANDLERS = new HashMap<>();

  static {
    Runtime.getRuntime()
        .addShutdownHook(
            AgentThreadFactory.newAgentThread(
                AgentThreadFactory.AgentThread.CI_TEST_EVENTS_SHUTDOWN_HOOK,
                TestEventsHandlerHolder::stop,
                false));
  }

  public static void start(TestFrameworkInstrumentation framework) {
    HANDLERS.put(framework, InstrumentationBridge.createTestEventsHandler("junit", null, null));
  }

  public static void stop() {
    for (Map.Entry<
            TestFrameworkInstrumentation, TestEventsHandler<TestSuiteDescriptor, TestDescriptor>>
        entry : HANDLERS.entrySet()) {
      entry.getValue().close();
    }
    HANDLERS.clear();
  }

  public static void stop(TestFrameworkInstrumentation framework) {
    TestEventsHandler<TestSuiteDescriptor, TestDescriptor> handler = HANDLERS.get(framework);
    if (handler != null) {
      handler.close();
      HANDLERS.remove(framework);
    }
  }

  private TestEventsHandlerHolder() {}
}
