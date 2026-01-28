package datadog.trace.instrumentation.pekko.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.capture;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.nameEndsWith;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;

/** Active span capturing and continuation for Pekko's async scheduled tasks. */
@AutoService(InstrumenterModule.class)
public class PekkoSchedulerInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public PekkoSchedulerInstrumentation() {
    super("java_concurrent", "pekko_concurrent", "pekko_scheduler");
  }

  @Override
  protected boolean defaultEnabled() {
    return InstrumenterConfig.get().isPekkoSchedulerEnabled();
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(Runnable.class.getName(), State.class.getName());
  }

  @Override
  public String instrumentedType() {
    return "org.apache.pekko.actor.LightArrayRevolverScheduler";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(nameEndsWith("schedule"))
            .and(takesArgument(0, named("scala.concurrent.ExecutionContext")))
            .and(takesArgument(1, Runnable.class))
            .and(takesArgument(2, named("scala.concurrent.duration.FiniteDuration"))),
        getClass().getName() + "$Schedule");
  }

  public static final class Schedule {
    @Advice.OnMethodEnter
    public static void schedule(@Advice.Argument(1) Runnable runnable) {
      capture(InstrumentationContext.get(Runnable.class, State.class), runnable);
    }
  }
}
