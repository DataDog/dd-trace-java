package datadog.trace.instrumentation.opentelemetry147;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.opentelemetry.shim.metrics.OtelMeterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import io.opentelemetry.api.metrics.MeterProvider;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Provides our metrics implementations to OpenTelemetry clients. */
@AutoService(InstrumenterModule.class)
public class OpenTelemetryMetricsInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.CanShortcutTypeMatching, Instrumenter.HasMethodAdvice {

  public OpenTelemetryMetricsInstrumentation() {
    super("opentelemetry-metrics", "opentelemetry-1.47");
  }

  @Override
  protected boolean defaultEnabled() {
    // TODO: return InstrumenterConfig.get().isMetricsOtelEnabled(); when fully implemented
    return false;
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
      "datadog.opentelemetry.shim.OtelInstrumentationScope",
      "datadog.opentelemetry.shim.metrics.OtelMeter",
      "datadog.opentelemetry.shim.metrics.OtelMeterBuilder",
      "datadog.opentelemetry.shim.metrics.OtelMeterProvider",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // MeterProvider OpenTelemetry.getMeterProvider()
    transformer.applyAdvice(
        isMethod()
            .and(named("getMeterProvider"))
            .and(takesNoArguments())
            .and(returns(named("io.opentelemetry.api.metrics.MeterProvider"))),
        OpenTelemetryMetricsInstrumentation.class.getName() + "$MeterProviderAdvice");
  }

  public static class MeterProviderAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void returnProvider(@Advice.Return(readOnly = false) MeterProvider result) {
      result = OtelMeterProvider.INSTANCE;
    }
  }
}
