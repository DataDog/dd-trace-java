package datadog.trace.instrumentation.opentelemetry14;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.InstrumenterConfig;
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
    return InstrumenterConfig.get().isTraceOtelEnabled();
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
      packageName + ".OtelExtractedContext",
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
      AgentSpan agentCurrentSpan = AgentTracer.activeSpan();
      if (null == agentCurrentSpan) {
        if (result instanceof OtelContext) {
          // context was ours, but is now out of scope, so invalidate it
          result = new OtelContext(OtelSpan.invalid(), OtelSpan.invalid());
        } else {
          // otherwise leave non-Datadog context unchanged
        }
        return;
      }
      // Get OTel current span
      Span otelCurrentSpan = null;
      if (agentCurrentSpan instanceof AttachableWrapper) {
        Object wrapper = ((AttachableWrapper) agentCurrentSpan).getWrapper();
        if (wrapper instanceof OtelSpan) {
          otelCurrentSpan = (OtelSpan) wrapper;
        }
      }
      if (otelCurrentSpan == null) {
        otelCurrentSpan = new OtelSpan(agentCurrentSpan);
      }
      // Get OTel root span
      Span otelRootSpan = null;
      AgentSpan agentRootSpan = agentCurrentSpan.getLocalRootSpan();
      if (agentRootSpan instanceof AttachableWrapper) {
        Object wrapper = ((AttachableWrapper) agentRootSpan).getWrapper();
        if (wrapper instanceof OtelSpan) {
          otelRootSpan = (OtelSpan) wrapper;
        }
      }
      if (otelRootSpan == null) {
        otelRootSpan = new OtelSpan(agentRootSpan);
      }
      result = new OtelContext(otelCurrentSpan, otelRootSpan);
    }
  }
}
