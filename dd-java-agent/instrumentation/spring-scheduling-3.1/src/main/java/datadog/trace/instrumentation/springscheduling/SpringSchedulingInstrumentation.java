package datadog.trace.instrumentation.springscheduling;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.springframework.scheduling.TaskScheduler;

@AutoService(Instrumenter.class)
public final class SpringSchedulingInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public SpringSchedulingInstrumentation() {
    super("spring-scheduling");
  }

  public String hierarchyMarkerType() {
    return "org.springframework.scheduling.TaskScheduler";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SpringSchedulingDecorator", packageName + ".SpringSchedulingRunnableWrapper",
    };
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(nameStartsWith("schedule")).and(takesArgument(0, Runnable.class)),
        getClass().getName() + "$SpringSchedulingAdvice");
  }

  public static class SpringSchedulingAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean beforeSchedule(
        @Advice.Argument(value = 0, readOnly = false) Runnable runnable) {
      if (CallDepthThreadLocalMap.incrementCallDepth(TaskScheduler.class) > 0) {
        return false;
      }
      runnable = SpringSchedulingRunnableWrapper.wrapIfNeeded(runnable);
      return true;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void afterSchedule(
        @Advice.Enter boolean reset,
        @Advice.Argument(value = 0, readOnly = false) Runnable runnable) {
      if (reset) {
        CallDepthThreadLocalMap.reset(TaskScheduler.class);
      }
    }
  }
}
