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
import datadog.trace.api.InstrumenterConfig;
import io.opentelemetry.api.metrics.DoubleGauge;
import io.opentelemetry.api.metrics.MeterProvider;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Provides our metrics implementations to OpenTelemetry clients.
 *
 * <p>Note that the minimum version for Datadog support of the OpenTelemetry metrics API is 1.47.
 * Tracing support is handled by a separate instrumentation under the 'opentelemetry-1.4' module.
 */
@AutoService(InstrumenterModule.class)
public class OpenTelemetryMetricsInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.CanShortcutTypeMatching, Instrumenter.HasMethodAdvice {

  public OpenTelemetryMetricsInstrumentation() {
    super("opentelemetry-metrics", "opentelemetry-1.47", "opentelemetry-1");
  }

  @Override
  protected boolean defaultEnabled() {
    return InstrumenterConfig.get().isMetricsOtelEnabled();
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

    public static void muzzleCheck(DoubleGauge doubleGauge) {
      doubleGauge.set(0); // not available before 1.38
    }
  }
}
