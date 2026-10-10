package datadog.trace.instrumentation.java.concurrent.structuredconcurrency25;

import static datadog.environment.JavaVirtualMachine.isJavaVersionAtLeast;
import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.bootstrap.instrumentation.java.concurrent.TaskScopeStateRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This module propagates context across {@code java.util.concurrent.StructuredTaskScope} forked
 * subtasks (JDK 25+).
 */
@SuppressWarnings("unused")
@AutoService(InstrumenterModule.class)
public class StructuredTaskScope25Module extends InstrumenterModule.ContextTracking {
  public StructuredTaskScope25Module() {
    super("java_concurrent", "structured-task-scope", "structured-task-scope-25");
  }

  @Override
  public boolean isEnabled() {
    return isJavaVersionAtLeast(25) && super.isEnabled();
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStores = new HashMap<>();
    // Per-subtask continuation captured at SubtaskImpl creation, restored by Runnable advice.
    contextStores.put(Runnable.class.getName(), State.class.getName());
    // Per-scope registry of subtasks state, swept at scope close to release leaked continuations.
    contextStores.put(
        "java.util.concurrent.StructuredTaskScopeImpl", TaskScopeStateRegistry.class.getName());
    return contextStores;
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(
        new StructuredTaskScope25TaskInstrumentation(), new StructuredTaskScope25Instrumentation());
  }
}
