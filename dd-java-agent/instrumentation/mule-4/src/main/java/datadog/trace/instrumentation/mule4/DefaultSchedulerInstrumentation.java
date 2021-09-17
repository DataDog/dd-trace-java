package datadog.trace.instrumentation.mule4;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class DefaultSchedulerInstrumentation extends Instrumenter.Tracing {

  public DefaultSchedulerInstrumentation() {
    super("mule");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("org.mule.service.scheduler.internal.DefaultScheduler");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        namedOneOf("newTaskFor", "schedule")
            .and(
                takesArgument(
                    0, namedOneOf("java.lang.Runnable", "java.util.concurrent.Callable"))),
        packageName + ".DefaultSchedulerAdvice");
  }
}
