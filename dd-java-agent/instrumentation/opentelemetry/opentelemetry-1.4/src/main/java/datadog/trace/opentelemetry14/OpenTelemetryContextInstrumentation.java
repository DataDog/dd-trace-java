package datadog.trace.opentelemetry14;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AttachableWrapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class OpenTelemetryContextInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public static final String OTEL_CONTEXT_CLASSNAME = "io.opentelemetry.context.Context";

  public OpenTelemetryContextInstrumentation() {
    super("opentelemetry", "opentelemetry-1");
  }

  @Override
  public String instrumentedType() {
    return OTEL_CONTEXT_CLASSNAME;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".OtelContext", packageName + ".OtelSpan",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    // Context.current()
    transformation.applyAdvice(
        isMethod()
            .and(named("current"))
            .and(takesNoArguments())
            .and(returns(named(OTEL_CONTEXT_CLASSNAME))),
        OpenTelemetryContextInstrumentation.class.getName() + "$ContextCurrentAdvice");
  }

  public static class ContextCurrentAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void current(@Advice.Return(readOnly = false) Context result) {
      AgentSpan agentSpan = AgentTracer.activeSpan();
      Span otelSpan = null;
      if (agentSpan instanceof AttachableWrapper) {
        otelSpan = (OtelSpan) ((AttachableWrapper) agentSpan).getWrapper();
      }
      if (otelSpan == null) {
        otelSpan = agentSpan == null ? OtelSpan.invalid() : new OtelSpan(agentSpan);
      }
      result = new OtelContext(otelSpan);
    }
  }
}
