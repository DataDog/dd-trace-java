package datadog.trace.instrumentation.renaissance;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(Instrumenter.class)
public class RenaissanceInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  private static final Logger log = LoggerFactory.getLogger(RenaissanceInstrumentation.class);

  public RenaissanceInstrumentation() {
    super("renaissance");
  }

  @Override
  public String instrumentedType() {
    return "org.renaissance.harness.ExecutionDriver";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("executeOperation")).and(takesArgument(0, int.class)),
        RenaissanceInstrumentation.class.getName() + "$BenchmarkAdvice");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  public static final class BenchmarkAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.Argument(0) int index, @Advice.FieldValue("benchmarkName") String benchmarkName) {
      AgentSpan span = startSpan("renaissance.benchmark").setResourceName(benchmarkName);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      scope.close();
      scope.span().finish();
    }
  }
}
