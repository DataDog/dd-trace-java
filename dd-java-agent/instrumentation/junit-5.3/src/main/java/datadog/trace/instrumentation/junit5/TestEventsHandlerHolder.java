package datadog.trace.instrumentation.junit5;

import datadog.trace.api.civisibility.DDTest;
import datadog.trace.api.civisibility.DDTestSuite;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.execution.TestExecutionHistory;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.util.ConcurrentEnumMap;
import java.util.Map;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestEngine;

public abstract class TestEventsHandlerHolder {

  // store one handler per framework running
  public static final Map<
          TestFrameworkInstrumentation, TestEventsHandler<TestDescriptor, TestDescriptor>>
      HANDLERS = new ConcurrentEnumMap<>(TestFrameworkInstrumentation.class);

  private static volatile ContextStore<TestDescriptor, TestExecutionHistory>
      EXECUTION_HISTORY_STORE;

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

  public static synchronized void start(
      TestEngine testEngine,
      ContextStore<TestDescriptor, DDTestSuite> suiteStore,
      ContextStore<TestDescriptor, DDTest> testStore) {
    TestFrameworkInstrumentation framework = JUnitPlatformUtils.engineToFramework(testEngine);
    TestEventsHandler<TestDescriptor, TestDescriptor> handler = HANDLERS.get(framework);
    if (handler == null) {
      handler =
          InstrumentationBridge.createTestEventsHandler(
              framework.name().toLowerCase(),
              suiteStore,
              testStore,
              JUnitPlatformUtils.capabilities(testEngine));
      HANDLERS.put(framework, handler);
    }
  }

  /** Used by instrumentation tests */
  public static synchronized void stop() {
    for (TestEventsHandler<TestDescriptor, TestDescriptor> handler : HANDLERS.values()) {
      handler.close();
    }
    HANDLERS.clear();
  }

  private TestEventsHandlerHolder() {}
}
