package datadog.trace.instrumentation.akka.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.capture;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.endTaskScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.startTaskScope;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import net.bytebuddy.asm.Advice;

/**
 * akka.dispatch.ForkJoinExecutorConfigurator$AkkaForkJoinTask requires special treatment and can't
 * be handled generically despite being a subclass of akka.dispatch.ForkJoinTask, because of its
 * error handling.
 */
public final class AkkaForkJoinExecutorTaskInstrumentation
    implements Instrumenter.ForSingleType,
        Instrumenter.ForConfiguredType,
        Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "akka.dispatch.ForkJoinExecutorConfigurator$AkkaForkJoinTask";
  }

  @Override
  public String configuredMatchingType() {
    return InstrumenterConfig.get().getAkkaForkJoinExecutorTaskName();
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor().and(takesArgument(0, named(Runnable.class.getName()))),
        getClass().getName() + "$Construct");
    transformer.applyAdvice(isMethod().and(named("run")), getClass().getName() + "$Run");
  }

  public static final class Construct {
    @Advice.OnMethodExit
    public static void construct(@Advice.Argument(0) Runnable wrapped) {
      capture(InstrumentationContext.get(Runnable.class, State.class), wrapped);
    }
  }

  public static final class Run {
    @Advice.OnMethodEnter
    public static AgentScope before(@Advice.Argument(0) Runnable wrapped) {
      return startTaskScope(InstrumentationContext.get(Runnable.class, State.class), wrapped);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(@Advice.Enter AgentScope scope) {
      endTaskScope(scope);
    }
  }
}
