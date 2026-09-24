package datadog.trace.instrumentation.java.concurrent.structuredconcurrency25;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.InstrumentationContext.get;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.bootstrap.instrumentation.java.concurrent.SubtaskRegistry;
import net.bytebuddy.asm.Advice.OnMethodExit;
import net.bytebuddy.asm.Advice.This;

/**
 * This instrumentation releases the continuations captured by {@link
 * StructuredTaskScope25TaskInstrumentation} for subtasks whose thread never starts (e.g. a subtask
 * forked into an already-canceled scope).
 *
 * <p>Each forked subtask is registered in a per-scope {@link SubtaskRegistry} at creation (by
 * {@link StructuredTaskScope25TaskInstrumentation}, so even if {@code fork()} throws), and the
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
    /**
     * Cleans up the scope's {@link SubtaskRegistry} when it closes, releasing the continuation of
     * every subtask whose thread never ran.
     *
     * <p>It will run after {@code close()} which joins all started subtasks. It means only
     * never-started subtasks still hold an unconsumed continuation; releasing an already-consumed
     * one is a no-op.
     *
     * @param scope The StructuredTaskScopeImpl object (using {@link Object} as the advice is
     *     compiled against Java 8, meaning the type from JDK 25 can't be referred directly).
     */
    @OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void afterClose(@This Object scope) {
      ContextStore<Object, SubtaskRegistry> registryStore =
          get(
              "java.util.concurrent.StructuredTaskScopeImpl",
              "datadog.trace.bootstrap.instrumentation.java.concurrent.SubtaskRegistry");
      SubtaskRegistry registry = registryStore.remove(scope);
      if (registry != null) {
        registry.cancelAll(get(Runnable.class, State.class));
      }
    }
  }
}
