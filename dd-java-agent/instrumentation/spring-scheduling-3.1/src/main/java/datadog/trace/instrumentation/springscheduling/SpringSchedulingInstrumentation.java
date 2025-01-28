package datadog.trace.instrumentation.springscheduling;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class SpringSchedulingInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice, ExcludeFilterProvider {

  public SpringSchedulingInstrumentation() {
    super("spring-scheduling");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.scheduling.config.Task";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SpringSchedulingDecorator",
      packageName + ".SpringSchedulingRunnableWrapper",
      packageName + ".SpringSchedulingRunnableWrapper$SchedulingAware",
      packageName + ".SpringSchedulingRunnableWrapper$1",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor().and(takesArgument(0, Runnable.class)),
        getClass().getName() + "$SpringSchedulingAdvice");
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    return Collections.singletonMap(
        RUNNABLE,
        Collections.singleton(
            "org.springframework.scheduling.config.Task$OutcomeTrackingRunnable"));
  }

  public static class SpringSchedulingAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onConstruction(
        @Advice.Argument(value = 0, readOnly = false) Runnable runnable) {
      runnable = SpringSchedulingRunnableWrapper.wrapIfNeeded(runnable);
    }
  }
}
