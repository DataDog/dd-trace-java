package datadog.trace.instrumentation.opentelemetry14;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
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
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class OpenTelemetryContextInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.CanShortcutTypeMatching {

  public OpenTelemetryContextInstrumentation() {
    super("opentelemetry.experimental", "opentelemetry-1");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public String hierarchyMarkerType() {
    return "io.opentelemetry.context.ContextStorage";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "io.opentelemetry.context.ThreadLocalContextStorage",
      "io.opentelemetry.context.StrictContextStorage",
    };
  }

  @Override
  public boolean onlyMatchKnownTypes() {
    return isShortcutMatchingEnabled(false);
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".OtelContext",
      packageName + ".OtelScope",
      packageName + ".OtelSpan",
      packageName + ".OtelSpan$NoopSpan",
      packageName + ".OtelSpan$NoopSpanContext",
      packageName + ".OtelSpanBuilder",
      packageName + ".OtelSpanBuilder$1",
      packageName + ".OtelSpanContext",
      packageName + ".OtelTracer",
      packageName + ".OtelTracerBuilder",
      packageName + ".OtelTracerProvider",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    // Context.current()
    transformation.applyAdvice(
        isMethod()
            .and(named("current"))
            .and(takesNoArguments())
            .and(returns(named("io.opentelemetry.context.Context"))),
        OpenTelemetryContextInstrumentation.class.getName() + "$ContextCurrentAdvice");
  }

  public static class ContextCurrentAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void current(@Advice.Return(readOnly = false) Context result) {
      AgentSpan agentSpan = AgentTracer.activeSpan();
      Span otelSpan = null;
      if (agentSpan instanceof AttachableWrapper) {
        Object wrapper = ((AttachableWrapper) agentSpan).getWrapper();
        if (wrapper instanceof OtelSpan) {
          otelSpan = (OtelSpan) wrapper;
        }
      }
      if (otelSpan == null) {
        otelSpan = agentSpan == null ? OtelSpan.invalid() : new OtelSpan(agentSpan);
      }
      result = new OtelContext(otelSpan);
    }
  }
}
