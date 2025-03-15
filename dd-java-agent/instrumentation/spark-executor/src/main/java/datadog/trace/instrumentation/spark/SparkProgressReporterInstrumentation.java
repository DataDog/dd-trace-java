package datadog.trace.instrumentation.spark;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.spark.SparkProgressReporterDecorator.DECORATE;
import static datadog.trace.instrumentation.spark.SparkProgressReporterDecorator.SPARK_PROGRESS_REPORTER;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class SparkProgressReporterInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType {

  public SparkProgressReporterInstrumentation() {
    super("spark-executor");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.spark.sql.execution.streaming.ProgressReporter";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("finishTrigger")).and(takesArguments(1)),
        SparkProgressReporterInstrumentation.class.getName() + "$UpdateProgressAdvice");
  }

  public static final class UpdateProgressAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope enter() {
      final AgentSpan span = startSpan("spark-executor", SPARK_PROGRESS_REPORTER);
      SparkExecutorDecorator.DECORATE.afterStart(span);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.Enter final AgentScope scope, @Advice.This final Object reporter) {
      if (scope == null) {
        return;
      }

      final AgentSpan span = scope.span();
      try {
        DECORATE.onStreamingQueryProgress(span, reporter);
        DECORATE.beforeFinish(scope);
      } finally {
        scope.close();
        span.finish();
      }
    }
  }
}
