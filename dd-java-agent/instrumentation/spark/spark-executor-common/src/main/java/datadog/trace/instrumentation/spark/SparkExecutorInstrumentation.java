package datadog.trace.instrumentation.spark;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.spark.SparkExecutorDecorator.DECORATE;
import static datadog.trace.instrumentation.spark.SparkExecutorDecorator.SPARK_TASK;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.apache.spark.executor.Executor;

@AutoService(InstrumenterModule.class)
public class SparkExecutorInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public SparkExecutorInstrumentation() {
    super("spark-executor");
  }

  @Override
  public boolean defaultEnabled() {
    return false;
  }

  @Override
  public String instrumentedType() {
    return "org.apache.spark.executor.Executor$TaskRunner";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SparkExecutorDecorator",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("run"))
            .and(isDeclaredBy(named("org.apache.spark.executor.Executor$TaskRunner"))),
        SparkExecutorInstrumentation.class.getName() + "$RunAdvice");
  }

  public static final class RunAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope enter(@Advice.This Executor.TaskRunner taskRunner) {
      final AgentSpan span = startSpan("spark-executor", SPARK_TASK);

      DECORATE.afterStart(span);
      DECORATE.onTaskStart(span, taskRunner);

      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.Enter final AgentScope scope, @Advice.This final Executor.TaskRunner taskRunner) {
      if (scope == null) {
        return;
      }

      final AgentSpan span = scope.span();

      try {
        DECORATE.onTaskEnd(span, taskRunner);
        DECORATE.beforeFinish(scope);
      } finally {
        scope.close();
        span.finish();
      }
    }
  }
}
