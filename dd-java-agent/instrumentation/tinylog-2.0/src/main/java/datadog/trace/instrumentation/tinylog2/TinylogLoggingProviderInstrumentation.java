package datadog.trace.instrumentation.tinylog2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.tinylog.core.LogEntry;

@AutoService(InstrumenterModule.class)
public class TinylogLoggingProviderInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public TinylogLoggingProviderInstrumentation() {
    super("tinylog");
  }

  @Override
  public String instrumentedType() {
    return "org.tinylog.core.TinylogLoggingProvider";
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.tinylog.core.LogEntry", AgentSpanContext.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPrivate())
            .and(named("output"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("org.tinylog.core.LogEntry"))),
        TinylogLoggingProviderInstrumentation.class.getName() + "$OutputAdvice");
  }

  public static class OutputAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) LogEntry event) {
      AgentSpan span = activeSpan();

      if (span != null && traceConfig(span).isLogsInjectionEnabled()) {
        InstrumentationContext.get(LogEntry.class, AgentSpanContext.class)
            .put(event, span.context());
      }
    }
  }
}
