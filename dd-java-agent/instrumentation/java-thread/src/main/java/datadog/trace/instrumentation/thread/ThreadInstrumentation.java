package datadog.trace.instrumentation.thread;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public final class ThreadInstrumentation extends Instrumenter.Profiling
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType {

  public ThreadInstrumentation() {
    super("thread");
  }

  @Override
  public String instrumentedType() {
    return "java.lang.Thread";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(takesArguments(0)).and(named("exit")),
        getClass().getName() + "$DetachTracer");
  }

  public static final class DetachTracer {
    @Advice.OnMethodEnter
    public static void detachTracer() {
      AgentTracer.get().detach();
    }
  }
}
