package datadog.trace.instrumentation.opentelemetry14;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.opentelemetry.shim.context.propagation.OtelContextPropagators;
import datadog.opentelemetry.shim.trace.OtelTracerProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.propagation.ContextPropagators;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class OpenTelemetryInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.CanShortcutTypeMatching, Instrumenter.HasMethodAdvice {

  public OpenTelemetryInstrumentation() {
    super("opentelemetry.experimental", "opentelemetry-1");
  }

  @Override
  protected boolean defaultEnabled() {
    return InstrumenterConfig.get().isTraceOtelEnabled();
  }

  @Override
  public String hierarchyMarkerType() {
    return "io.opentelemetry.api.OpenTelemetry";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "io.opentelemetry.api.DefaultOpenTelemetry",
      "io.opentelemetry.api.GlobalOpenTelemetry$ObfuscatedOpenTelemetry"
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
      "datadog.opentelemetry.shim.context.propagation.AgentTextMapPropagator",
      "datadog.opentelemetry.shim.context.propagation.OtelContextPropagators",
      "datadog.opentelemetry.shim.context.propagation.TraceStateHelper",
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
    // TracerProvider OpenTelemetry.getTracerProvider()
    transformer.applyAdvice(
        isMethod()
            .and(named("getTracerProvider"))
            .and(takesNoArguments())
            .and(returns(named("io.opentelemetry.api.trace.TracerProvider"))),
        OpenTelemetryInstrumentation.class.getName() + "$TracerProviderAdvice");
    // ContextPropagators OpenTelemetry.getPropagators();
    transformer.applyAdvice(
        isMethod()
            .and(named("getPropagators"))
            .and(takesNoArguments())
            .and(returns(named("io.opentelemetry.context.propagation.ContextPropagators"))),
        OpenTelemetryInstrumentation.class.getName() + "$ContextPropagatorAdvice");
  }

  public static class TracerProviderAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void returnProvider(@Advice.Return(readOnly = false) TracerProvider result) {
      result = OtelTracerProvider.INSTANCE;
    }
  }

  public static class ContextPropagatorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void returnProvider(@Advice.Return(readOnly = false) ContextPropagators result) {
      result = OtelContextPropagators.INSTANCE;
    }
  }
}
