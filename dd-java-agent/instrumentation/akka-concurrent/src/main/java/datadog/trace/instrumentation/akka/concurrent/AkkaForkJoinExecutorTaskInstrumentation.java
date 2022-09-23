package datadog.trace.instrumentation.akka.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.capture;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.endTaskScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.startTaskScope;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;

/**
 * akka.dispatch.ForkJoinExecutorConfigurator$AkkaForkJoinTask requires special treatment and can't
 * be handled generically despite being a subclass of akka.dispatch.ForkJoinTask, because of its
 * error handling.
 */
@AutoService(Instrumenter.class)
public final class AkkaForkJoinExecutorTaskInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public AkkaForkJoinExecutorTaskInstrumentation() {
    super("java_concurrent", "akka_concurrent");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(Runnable.class.getName(), State.class.getName());
  }

  @Override
  public String instrumentedType() {
    return "akka.dispatch.ForkJoinExecutorConfigurator$AkkaForkJoinTask";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isConstructor().and(takesArgument(0, named(Runnable.class.getName()))),
        getClass().getName() + "$Construct");
    transformation.applyAdvice(isMethod().and(named("run")), getClass().getName() + "$Run");
  }

  public static final class Construct {
    @Advice.OnMethodExit
    public static void construct(@Advice.Argument(0) Runnable wrapped) {
      capture(InstrumentationContext.get(Runnable.class, State.class), wrapped, true);
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
