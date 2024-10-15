package datadog.trace.instrumentation.reactor.core;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.QueueTimerHelper;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.bootstrap.instrumentation.java.concurrent.TPEHelper;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

@AutoService(InstrumenterModule.class)
public class SchedulersInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public SchedulersInstrumentation() {
    super("reactor-core", "reactor-schedulers");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(Runnable.class.getName(), State.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        ElementMatchers.isMethod()
            .and(NameMatchers.named("schedule"))
            .and(ElementMatchers.takesArgument(0, ElementMatchers.named("java.lang.Runnable"))),
        getClass().getName() + "$CaptureAdvice");
  }

  @Override
  public String hierarchyMarkerType() {
    return "reactor.core.scheduler.Scheduler";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return HierarchyMatchers.implementsInterface(
            NameMatchers.named("reactor.core.scheduler.Scheduler"))
        .or(
            HierarchyMatchers.implementsInterface(
                NameMatchers.named("reactor.core.scheduler.Scheduler$Worker")));
  }

  public static class CaptureAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.This(typing = Assigner.Typing.DYNAMIC) Object self,
        @Advice.Argument(value = 0) final Runnable runnable) {
      if (!exclude(RUNNABLE, runnable)) {
        TPEHelper.capture(InstrumentationContext.get(Runnable.class, State.class), runnable);
        QueueTimerHelper.startQueuingTimer(
            InstrumentationContext.get(Runnable.class, State.class), self.getClass(), runnable);
      }
    }
  }
}
