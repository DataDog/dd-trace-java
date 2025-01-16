package datadog.trace.instrumentation.hystrix;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.isAsyncPropagationEnabled;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.setAsyncPropagationEnabled;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class HystrixThreadPoolInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public HystrixThreadPoolInstrumentation() {
    super("hystrix");
  }

  @Override
  public String instrumentedType() {
    return "com.netflix.hystrix.strategy.concurrency.HystrixContextScheduler$ThreadPoolWorker";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("schedule")).and(takesArguments(1)),
        HystrixThreadPoolInstrumentation.class.getName() + "$EnableAsyncAdvice");
  }

  public static class EnableAsyncAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean enableAsyncTracking() {
      if (!isAsyncPropagationEnabled()) {
        setAsyncPropagationEnabled(true);
        return true;
      }
      return false;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void disableAsyncTracking(@Advice.Enter final boolean wasEnabled) {
      if (wasEnabled) {
        setAsyncPropagationEnabled(false);
      }
    }
  }
}
