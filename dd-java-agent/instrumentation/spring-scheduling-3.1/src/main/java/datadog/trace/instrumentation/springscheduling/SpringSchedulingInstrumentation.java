package datadog.trace.instrumentation.springscheduling;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public final class SpringSchedulingInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

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
      packageName + ".SpringSchedulingDecorator", packageName + ".SpringSchedulingRunnableWrapper",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isConstructor().and(takesArgument(0, Runnable.class)),
        SpringSchedulingInstrumentation.class.getName() + "$SpringSchedulingAdvice");
  }

  public static class SpringSchedulingAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onConstruction(
        @Advice.Argument(value = 0, readOnly = false) Runnable runnable) {
      runnable = SpringSchedulingRunnableWrapper.wrapIfNeeded(runnable);
    }
  }
}
