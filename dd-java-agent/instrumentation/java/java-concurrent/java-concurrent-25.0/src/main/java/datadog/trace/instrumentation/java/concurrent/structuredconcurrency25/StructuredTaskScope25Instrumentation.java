package datadog.trace.instrumentation.java.concurrent.structuredconcurrency25;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.InstrumentationContext.get;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.TaskScopeStateRegistry.STRUCTURED_TASK_SCOPE_CLASS_NAME;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.TaskScopeStateRegistry.TASK_SCOPE_STATE_REGISTRY_CLASS_NAME;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.java.concurrent.TaskScopeStateRegistry;
import net.bytebuddy.asm.Advice.OnMethodExit;
import net.bytebuddy.asm.Advice.This;
import net.bytebuddy.asm.Advice.Thrown;

/**
 * This instrumentation releases the continuations captured by {@link
 * StructuredTaskScope25TaskInstrumentation} for subtasks whose thread never starts (e.g. a subtask
 * forked into an already-canceled scope, or {@code fork()} throwing).
 */
@SuppressWarnings("unused")
public class StructuredTaskScope25Instrumentation
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return STRUCTURED_TASK_SCOPE_CLASS_NAME;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("close").and(takesArguments(0)), getClass().getName() + "$CloseAdvice");
  }

  public static final class CloseAdvice {
    /**
     * Cleans up the scope's {@link TaskScopeStateRegistry} when it closes, releasing the
     * continuation of every subtask whose thread never ran.
     *
     * <p>It will run after {@code close()} which joins all started subtasks. It means only
     * never-started subtasks still hold an unconsumed continuation; releasing an already-consumed
     * one is a no-op.
     *
     * <p>When {@code close()} throws, the registry is swept depending on the exception:
     *
     * <ul>
     *   <li>an {@link IllegalStateException} is thrown after joining (e.g. the owner did not join
     *       after forking): the scope is closed, so the registry is swept;
     *   <li>a {@code WrongThreadException} is thrown before joining when called by a non-owner
     *       thread: subtasks may still be pending, so the registry is kept.
     * </ul>
     *
     * <p>The exception is checked rather than the scope state, as the state constants differ across
     * JDK versions.
     *
     * @param scope The StructuredTaskScopeImpl object (using {@link Object} as the advice is
     *     compiled against Java 8, meaning the type from JDK 25 can't be referred directly).
     * @param thrown The exception thrown by {@code close()}, {@code null} if it returned normally.
     */
    @OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void afterClose(@This Object scope, @Thrown Throwable thrown) {
      // Matched by name as WrongThreadException (JDK 19+) can't be referred from Java 8 advice
      if (thrown != null && "java.lang.WrongThreadException".equals(thrown.getClass().getName())) {
        return;
      }
      ContextStore<Object, TaskScopeStateRegistry> registryStore =
          get(STRUCTURED_TASK_SCOPE_CLASS_NAME, TASK_SCOPE_STATE_REGISTRY_CLASS_NAME);
      TaskScopeStateRegistry registry = registryStore.remove(scope);
      if (registry != null) {
        registry.cancelAll();
      }
    }
  }
}
