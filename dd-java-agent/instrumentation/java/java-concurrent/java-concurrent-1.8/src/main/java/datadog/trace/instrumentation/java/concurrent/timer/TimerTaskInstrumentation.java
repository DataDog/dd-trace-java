package datadog.trace.instrumentation.java.concurrent.timer;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.instrumentation.java.concurrent.runnable.RunnableInstrumentation;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Instruments {@code java.util.TimerTask}.
 *
 * <p>Only the cancel part is handled here because the execution is handled by the {@link
 * RunnableInstrumentation}
 */
public final class TimerTaskInstrumentation
    implements Instrumenter.ForBootstrap,
        Instrumenter.CanShortcutTypeMatching,
        Instrumenter.HasMethodAdvice {

  @Override
  public boolean onlyMatchKnownTypes() {
    return false;
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {"java.util.TimerTask"};
  }

  @Override
  public String hierarchyMarkerType() {
    return null;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named("java.util.TimerTask"));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("cancel").and(takesArguments(0)).and(isPublic()),
        TimerTaskInstrumentation.class.getName() + "$CancelAdvice");
  }

  public static class CancelAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onCancel(@Advice.This Runnable self) {
      AdviceUtils.cancelTask(InstrumentationContext.get(Runnable.class, State.class), self);
    }
  }
}
