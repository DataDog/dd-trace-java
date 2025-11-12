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

@AutoService(InstrumenterModule.class)
public class OpenTelemetryInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.CanShortcutTypeMatching, Instrumenter.HasMethodAdvice {

  public OpenTelemetryInstrumentation() {
    super("opentelemetry.metrics", "opentelemetry-147");
  }

  @Override
  protected boolean defaultEnabled() {
    // Not activated yet to prevent NPE
    // return InstrumenterConfig.get().isMetricsOtelEnabled();
    return true;
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
      "datadog.opentelemetry.shim.metrics.OtelMeter",
      "datadog.opentelemetry.shim.metrics.OtelMeterBuilder",
      "datadog.opentelemetry.shim.metrics.OtelMeterProvider",
      "datadog.opentelemetry.shim.metrics.Instrument",
      "datadog.opentelemetry.shim.metrics.InstrumentType",
      "datadog.opentelemetry.shim.metrics.InstrumentValueType",
      "datadog.opentelemetry.shim.metrics.InstrumentBuilder",
      "datadog.opentelemetry.shim.metrics.InstrumentBuilder$SynchronousInstrumentConstructor",
      "datadog.opentelemetry.shim.metrics.InstrumentBuilder$SwapBuilder",
      "datadog.opentelemetry.shim.metrics.MetricStreamIdentity",
      "datadog.opentelemetry.shim.metrics.AutoValue_MetricStreamIdentity",
      "datadog.opentelemetry.shim.metrics.OtelDoubleCounter",
      "datadog.opentelemetry.shim.metrics.OtelDoubleCounter$OtelDoubleCounterBuilder",
      "datadog.opentelemetry.shim.metrics.OtelDoubleGauge",
      "datadog.opentelemetry.shim.metrics.OtelDoubleGauge$OtelDoubleGaugeBuilder",
      "datadog.opentelemetry.shim.metrics.OtelDoubleHistogram",
      "datadog.opentelemetry.shim.metrics.OtelDoubleHistogram$OtelDoubleHistogramBuilder",
      "datadog.opentelemetry.shim.metrics.OtelDoubleUpDownCounter",
      "datadog.opentelemetry.shim.metrics.OtelDoubleUpDownCounter$OtelDoubleUpDownCounterBuilder",
      "datadog.opentelemetry.shim.metrics.OtelLongCounter",
      "datadog.opentelemetry.shim.metrics.OtelLongCounter$OtelLongCounterBuilder",
      "datadog.opentelemetry.shim.metrics.OtelLongGauge",
      "datadog.opentelemetry.shim.metrics.OtelLongGauge$OtelLongGaugeBuilder",
      "datadog.opentelemetry.shim.metrics.OtelLongHistogram",
      "datadog.opentelemetry.shim.metrics.OtelLongHistogram$OtelLongHistogramBuilder",
      "datadog.opentelemetry.shim.metrics.OtelLongUpDownCounter",
      "datadog.opentelemetry.shim.metrics.OtelLongUpDownCounter$OtelLongUpDownCounterBuilder",
    };
  }

  // "datadog.opentelemetry.shim.metrics.Instrument",
  //
  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // MeterProvider OpenTelemetry.getMeterProvider()
    transformer.applyAdvice(
        isMethod()
            .and(named("getMeterProvider"))
            .and(takesNoArguments())
            .and(returns(named("io.opentelemetry.api.metrics.MeterProvider"))),
        OpenTelemetryInstrumentation.class.getName() + "$MeterProviderAdvice");
  }

  public static class MeterProviderAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void returnProvider(@Advice.Return(readOnly = false) MeterProvider result) {
      result = OtelMeterProvider.INSTANCE;
    }
  }
}
