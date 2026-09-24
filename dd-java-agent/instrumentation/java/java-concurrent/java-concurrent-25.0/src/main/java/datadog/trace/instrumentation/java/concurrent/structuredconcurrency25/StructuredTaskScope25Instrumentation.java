package datadog.trace.instrumentation.java.concurrent.structuredconcurrency25;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.InstrumentationContext.get;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.java.concurrent.TaskScopeStateRegistry;
import net.bytebuddy.asm.Advice.FieldValue;
import net.bytebuddy.asm.Advice.OnMethodExit;
import net.bytebuddy.asm.Advice.This;

/**
 * This instrumentation releases the continuations captured by {@link
 * StructuredTaskScope25TaskInstrumentation} for subtasks whose thread never starts (e.g. a subtask
 * forked into an already-canceled scope).
 *
 * <p>Each forked subtask is registered in a per-scope {@link TaskScopeStateRegistry} at creation
 * (by {@link StructuredTaskScope25TaskInstrumentation}, so even if {@code fork()} throws), and the
 * registry is swept when the scope closes ({@code close()}). Sweeping at close ensures started
 * subtask has, already consumed its continuation in {@code SubtaskImpl.run()}, while a
 * never-started subtask still holds it and gets it released.
 */
@SuppressWarnings("unused")
public class StructuredTaskScope25Instrumentation
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "java.util.concurrent.StructuredTaskScopeImpl";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("close").and(takesArguments(0)), getClass().getName() + "$CloseAdvice");
  }

  public static final class CloseAdvice {
    /** The {@code StructuredTaskScopeImpl.ST_CLOSED} state value (same from JDK 25 to 27). */
    static final int ST_CLOSED = 4;

    /**
     * Cleans up the scope's {@link TaskScopeStateRegistry} when it closes, releasing the
     * continuation of every subtask whose thread never ran.
     *
     * <p>It will run after {@code close()} which joins all started subtasks. It means only
     * never-started subtasks still hold an unconsumed continuation; releasing an already-consumed
     * one is a no-op.
     *
     * <p>The registry is only swept once the scope reached its closed state: {@code close()} may
     * also throw after joining (e.g. owner did not join after forking), but it throws before
     * joining when called by a non-owner thread, while subtasks may still be pending.
     *
     * @param scope The StructuredTaskScopeImpl object (using {@link Object} as the advice is
     *     compiled against Java 8, meaning the type from JDK 25 can't be referred directly).
     * @param state The StructuredTaskScopeImpl state.
     */
    @OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void afterClose(@This Object scope, @FieldValue("state") int state) {
      if (state != ST_CLOSED) {
        return;
      }
      ContextStore<Object, TaskScopeStateRegistry> registryStore =
          get(
              "java.util.concurrent.StructuredTaskScopeImpl",
              "datadog.trace.bootstrap.instrumentation.java.concurrent.TaskScopeStateRegistry");
      TaskScopeStateRegistry registry = registryStore.remove(scope);
      if (registry != null) {
        registry.cancelAll();
      }
    }
  }
}
