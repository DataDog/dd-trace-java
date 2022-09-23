package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.endTaskScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.startTaskScope;
import static datadog.trace.instrumentation.java.concurrent.AbstractExecutorInstrumentation.EXEC_NAME;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Map;
import java.util.concurrent.ForkJoinTask;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class ConsumerTaskInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType {
  public ConsumerTaskInstrumentation() {
    super(EXEC_NAME, "consumer-task");
  }

  @Override
  public String instrumentedType() {
    return "java.util.concurrent.SubmissionPublisher$ConsumerTask";
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("java.util.concurrent.ForkJoinTask", State.class.getName());
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(isConstructor(), getClass().getName() + "$Construct");
    // execution will be instrumented as ForkJoinTask
    transformation.applyAdvice(named("run"), getClass().getName() + "$Run");
  }

  public static class Construct {
    @Advice.OnMethodExit
    public static void construct(@Advice.This ForkJoinTask<?> task) {
      AgentScope activeScope = activeScope();
      if (null != activeScope) {
        State state = State.FACTORY.create();
        state.captureAndSetContinuation(activeScope);
        InstrumentationContext.get(ForkJoinTask.class, State.class).put(task, state);
      }
    }
  }

  public static final class Run {
    @Advice.OnMethodEnter
    public static <T> AgentScope before(@Advice.This ForkJoinTask<T> task) {
      return startTaskScope(InstrumentationContext.get(ForkJoinTask.class, State.class), task);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(@Advice.Enter AgentScope scope) {
      endTaskScope(scope);
    }
  }
}
