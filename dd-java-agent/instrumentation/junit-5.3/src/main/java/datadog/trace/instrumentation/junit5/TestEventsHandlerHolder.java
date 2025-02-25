package datadog.trace.instrumentation.junit5;

import datadog.trace.api.civisibility.DDTest;
import datadog.trace.api.civisibility.DDTestSuite;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.execution.TestExecutionHistory;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.util.AgentThreadFactory;
import java.util.HashMap;
import java.util.Map;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestEngine;

public abstract class TestEventsHandlerHolder {

  // store one handler per framework running
  public static Map<TestFrameworkInstrumentation, TestEventsHandler<TestDescriptor, TestDescriptor>>
      HANDLERS = new HashMap<>();
  public static volatile TestEventsHandler<TestDescriptor, TestDescriptor> DEFAULT_HANDLER;
  private static ContextStore<TestDescriptor, DDTestSuite> SUITE_STORE;
  private static ContextStore<TestDescriptor, DDTest> TEST_STORE;
  private static volatile ContextStore<TestDescriptor, TestExecutionHistory>
      EXECUTION_HISTORY_STORE;

  static {
    Runtime.getRuntime()
        .addShutdownHook(
            AgentThreadFactory.newAgentThread(
                AgentThreadFactory.AgentThread.CI_TEST_EVENTS_SHUTDOWN_HOOK,
                TestEventsHandlerHolder::stop,
                false));
  }

  public static synchronized void setContextStores(
      ContextStore<TestDescriptor, DDTestSuite> suiteStore,
      ContextStore<TestDescriptor, DDTest> testStore) {
    if (SUITE_STORE == null) {
      SUITE_STORE = suiteStore;
    }
    if (TEST_STORE == null) {
      TEST_STORE = testStore;
    }
  }

  public static synchronized void setExecutionHistoryStore(
      ContextStore<TestDescriptor, TestExecutionHistory> executionHistoryStore) {
    if (EXECUTION_HISTORY_STORE == null) {
      EXECUTION_HISTORY_STORE = executionHistoryStore;
    }
  }

  public static void setExecutionHistory(
      TestDescriptor testDescriptor, TestExecutionHistory history) {
    if (EXECUTION_HISTORY_STORE != null) {
      EXECUTION_HISTORY_STORE.put(testDescriptor, history);
    }
  }

  public static TestExecutionHistory getExecutionHistory(TestDescriptor testDescriptor) {
    if (EXECUTION_HISTORY_STORE != null) {
      return EXECUTION_HISTORY_STORE.get(testDescriptor);
    } else {
      return null;
    }
  }

  public static synchronized void start(TestEngine testEngine) {
    TestFrameworkInstrumentation framework = JUnitPlatformUtils.engineToFramework(testEngine);
    TestEventsHandler<TestDescriptor, TestDescriptor> handler = HANDLERS.get(framework);
    if (handler == null) {
      handler = InstrumentationBridge.createTestEventsHandler("junit", SUITE_STORE, TEST_STORE);
      HANDLERS.put(framework, handler);
      if (DEFAULT_HANDLER == null) {
        DEFAULT_HANDLER = handler;
      }
    }
  }

  // used by instrumentation tests
  public static synchronized void startForcefully(TestFrameworkInstrumentation framework) {
    if (SUITE_STORE != null && TEST_STORE != null) {
      TestEventsHandler<TestDescriptor, TestDescriptor> handler =
          InstrumentationBridge.createTestEventsHandler("junit", SUITE_STORE, TEST_STORE);
      HANDLERS.put(framework, handler);
      DEFAULT_HANDLER = handler;
    }
  }

  public static synchronized void stop() {
    for (Map.Entry<TestFrameworkInstrumentation, TestEventsHandler<TestDescriptor, TestDescriptor>>
        entry : HANDLERS.entrySet()) {
      entry.getValue().close();
    }
    HANDLERS.clear();
    if (DEFAULT_HANDLER != null) {
      DEFAULT_HANDLER.close();
      DEFAULT_HANDLER = null;
    }
  }

  private TestEventsHandlerHolder() {}
}
