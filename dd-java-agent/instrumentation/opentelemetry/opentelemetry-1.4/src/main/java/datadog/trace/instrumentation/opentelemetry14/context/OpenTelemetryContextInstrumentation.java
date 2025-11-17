package datadog.trace.instrumentation.opentelemetry14.context;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.opentelemetry.shim.context.OtelContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import io.opentelemetry.context.Context;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class OpenTelemetryContextInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.CanShortcutTypeMatching, Instrumenter.HasMethodAdvice {

  public OpenTelemetryContextInstrumentation() {
    super("opentelemetry.experimental", "opentelemetry-1");
  }

  @Override
  protected boolean defaultEnabled() {
    return InstrumenterConfig.get().isTraceOtelEnabled();
  }

  @Override
  public String hierarchyMarkerType() {
    return "io.opentelemetry.context.Context";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "io.opentelemetry.context.ArrayBasedContext",
    };
  }

  @Override
  public boolean onlyMatchKnownTypes() {
    return isShortcutMatchingEnabled(false);
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.opentelemetry.shim.context.OtelContext",
      "datadog.opentelemetry.shim.context.OtelScope",
      "datadog.opentelemetry.shim.baggage.OtelBaggage",
      "datadog.opentelemetry.shim.baggage.OtelBaggage$ValueOnly",
      "datadog.opentelemetry.shim.baggage.OtelBaggageBuilder",
      "datadog.opentelemetry.shim.trace.OtelExtractedContext",
      "datadog.opentelemetry.shim.trace.OtelConventions",
      "datadog.opentelemetry.shim.trace.OtelConventions$1",
      "datadog.opentelemetry.shim.trace.OtelSpan",
      "datadog.opentelemetry.shim.trace.OtelSpan$1",
      "datadog.opentelemetry.shim.trace.OtelSpan$NoopSpan",
      "datadog.opentelemetry.shim.trace.OtelSpan$NoopSpanContext",
      "datadog.opentelemetry.shim.trace.OtelSpanBuilder",
      "datadog.opentelemetry.shim.trace.OtelSpanBuilder$1",
      "datadog.opentelemetry.shim.trace.OtelSpanContext",
      "datadog.opentelemetry.shim.trace.OtelSpanEvent",
      "datadog.opentelemetry.shim.trace.OtelSpanEvent$AttributesJsonParser",
      "datadog.opentelemetry.shim.trace.OtelSpanLink",
      "datadog.opentelemetry.shim.trace.OtelTracer",
      "datadog.opentelemetry.shim.trace.OtelTracerBuilder",
      "datadog.opentelemetry.shim.trace.OtelTracerProvider",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // Context Context.root()
    transformer.applyAdvice(
        isMethod()
            .and(named("root"))
            .and(takesNoArguments())
            .and(returns(named("io.opentelemetry.context.Context"))),
        OpenTelemetryContextInstrumentation.class.getName() + "$ContextRootAdvice");
  }

  public static class ContextRootAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void current(@Advice.Return(readOnly = false) Context result) {
      result = OtelContext.ROOT;
    }
  }
}
