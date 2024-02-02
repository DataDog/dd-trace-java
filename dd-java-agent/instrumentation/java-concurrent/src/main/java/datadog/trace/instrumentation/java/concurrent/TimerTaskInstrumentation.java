package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Map;
import java.util.TimerTask;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Instrument {@link java.util.TimerTask}
 *
 * <p>Only the cancel part is handled here because the execution is handled by the {@link
 * RunnableInstrumentation}
 */
@AutoService(Instrumenter.class)
public final class TimerTaskInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForBootstrap, Instrumenter.ForTypeHierarchy {

  public TimerTaskInstrumentation() {
    super("java_timer", AbstractExecutorInstrumentation.EXEC_NAME, "runnable");
  }

  @Override
  public String hierarchyMarkerType() {
    return null; // bootstrap type
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(TimerTask.class.getName()));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(Runnable.class.getName(), State.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("cancel").and(takesArguments(0)).and(isPublic()),
        TimerTaskInstrumentation.class.getName() + "$CancelAdvice");
  }

  public static class CancelAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onCancel(@Advice.This TimerTask self) {
      AdviceUtils.cancelTask(InstrumentationContext.get(Runnable.class, State.class), self);
    }
  }
}
