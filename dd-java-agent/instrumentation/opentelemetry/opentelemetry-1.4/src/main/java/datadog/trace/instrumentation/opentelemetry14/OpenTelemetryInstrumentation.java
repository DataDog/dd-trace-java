package datadog.trace.instrumentation.opentelemetry14;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.instrumentation.opentelemetry14.context.propagation.OtelContextPropagators;
import datadog.trace.instrumentation.opentelemetry14.trace.OtelTracerProvider;
import datadog.trace.util.Strings;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.propagation.ContextPropagators;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class OpenTelemetryInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.CanShortcutTypeMatching {
  public static final String ROOT_PACKAGE_NAME =
      Strings.getPackageName(OpenTelemetryInstrumentation.class.getName());

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
      packageName + ".context.OtelContext",
      packageName + ".context.OtelScope",
      packageName + ".context.propagation.AgentTextMapPropagator",
      packageName + ".context.propagation.OtelContextPropagators",
      packageName + ".context.propagation.TraceStateHelper",
      packageName + ".trace.OtelExtractedContext",
      packageName + ".trace.OtelConventions",
      packageName + ".trace.OtelConventions$1",
      packageName + ".trace.OtelSpan",
      packageName + ".trace.OtelSpan$1",
      packageName + ".trace.OtelSpan$NoopSpan",
      packageName + ".trace.OtelSpan$NoopSpanContext",
      packageName + ".trace.OtelSpanBuilder",
      packageName + ".trace.OtelSpanBuilder$1",
      packageName + ".trace.OtelSpanContext",
      packageName + ".trace.OtelSpanLink",
      packageName + ".trace.OtelSpanLink$1",
      packageName + ".trace.OtelTracer",
      packageName + ".trace.OtelTracerBuilder",
      packageName + ".trace.OtelTracerProvider",
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
