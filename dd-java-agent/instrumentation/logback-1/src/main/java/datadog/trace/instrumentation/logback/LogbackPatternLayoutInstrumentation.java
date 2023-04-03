package datadog.trace.instrumentation.logback;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class LogbackPatternLayoutInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public LogbackPatternLayoutInstrumentation() {
    super("logback");
  }

  @Override
  public String instrumentedType() {
    return "ch.qos.logback.core.pattern.PatternLayoutBase";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("setPattern"))
            .and(takesArguments(1))
            .and(takesArgument(0, String.class)),
        LogbackPatternLayoutInstrumentation.class.getName() + "$SetPatternAdvice");
  }

  public static class SetPatternAdvice {
    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(value = 0, readOnly = false) String pattern) {
      String patternAddition = " [datadog trace_id=%X{dd.trace_id} span_id=%X{dd.span_id}]";
      boolean addTraceId = !pattern.contains("%X{dd.trace_id}");
      if (addTraceId) {
        int insertAt = pattern.lastIndexOf("%n");
        pattern = pattern.substring(0, insertAt) + patternAddition + pattern.substring(insertAt);
      }
    }
  }
}
