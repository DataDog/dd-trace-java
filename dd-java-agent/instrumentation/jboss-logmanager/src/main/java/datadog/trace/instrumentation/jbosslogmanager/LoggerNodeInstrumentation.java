package datadog.trace.instrumentation.jbosslogmanager;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.jboss.logmanager.ExtLogRecord;

@AutoService(Instrumenter.class)
public class LoggerNodeInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public LoggerNodeInstrumentation() {
    super("jboss-logmanager");
  }

  @Override
  public String instrumentedType() {
    return "org.jboss.logmanager.LoggerNode";
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.jboss.logmanager.ExtLogRecord", AgentSpan.Context.class.getName());
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("publish"))
            .and(takesArgument(0, named("org.jboss.logmanager.ExtLogRecord"))),
        LoggerNodeInstrumentation.class.getName() + "$AttachContextAdvice");
  }

  public static class AttachContextAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean attachContext(@Advice.Argument(0) ExtLogRecord record) {
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(ExtLogRecord.class);
      if (callDepth > 0) {
        return false;
      }

      AgentSpan span = activeSpan();

      if (span != null && traceConfig(span).isLogsInjectionEnabled()) {
        InstrumentationContext.get(ExtLogRecord.class, AgentSpan.Context.class)
            .put(record, span.context());
      }

      return true;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void resetDepth(@Advice.Enter boolean shouldReset) {
      if (shouldReset) {
        CallDepthThreadLocalMap.reset(ExtLogRecord.class);
      }
    }
  }
}
