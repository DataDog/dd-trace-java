package datadog.trace.common.sampling;

import static datadog.trace.api.config.AppSecConfig.APPSEC_ENABLED;
import static datadog.trace.api.config.AppSecConfig.APPSEC_SCA_ENABLED;
import static datadog.trace.api.config.GeneralConfig.APM_TRACING_ENABLED;
import static datadog.trace.api.config.IastConfig.IAST_ENABLED;
import static datadog.trace.api.config.OtlpConfig.TRACE_OTEL_EXPORTER;
import static datadog.trace.api.config.TracerConfig.PRIORITY_SAMPLING;
import static datadog.trace.api.config.TracerConfig.PRIORITY_SAMPLING_FORCE;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLE_RATE;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import datadog.trace.api.Config;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import org.junit.jupiter.api.Test;

class SamplerTest extends DDJavaSpecification {

  @WithConfig(key = APM_TRACING_ENABLED, value = "false")
  @WithConfig(key = APPSEC_ENABLED, value = "true")
  @Test
  void asmStandaloneSamplerSelectedWhenApmTracingDisabledAndAppsecEnabled() {
    Config config = Config.get();

    Sampler sampler = Sampler.Builder.forConfig(config, null);

    assertInstanceOf(AsmStandaloneSampler.class, sampler);
  }

  @WithConfig(key = APM_TRACING_ENABLED, value = "false")
  @WithConfig(key = IAST_ENABLED, value = "true")
  @Test
  void asmStandaloneSamplerSelectedWhenApmTracingDisabledAndIastEnabled() {
    Config config = Config.get();

    Sampler sampler = Sampler.Builder.forConfig(config, null);

    assertInstanceOf(AsmStandaloneSampler.class, sampler);
  }

  @WithConfig(key = APM_TRACING_ENABLED, value = "false")
  @WithConfig(key = APPSEC_SCA_ENABLED, value = "true")
  @Test
  void asmStandaloneSamplerSelectedWhenApmTracingDisabledAndScaEnabled() {
    Config config = Config.get();

    Sampler sampler = Sampler.Builder.forConfig(config, null);

    assertInstanceOf(AsmStandaloneSampler.class, sampler);
  }

  @WithConfig(key = APM_TRACING_ENABLED, value = "false")
  @Test
  void asmStandaloneSamplerNotSelectedWhenApmTracingAndAsmNotEnabled() {
    Config config = Config.get();

    Sampler sampler = Sampler.Builder.forConfig(config, null);

    assertFalse(sampler instanceof AsmStandaloneSampler);
  }

  @Test
  void asmStandaloneSamplerNotSelectedWhenApmTracingEnabledAndAsmNotEnabled() {
    Config config = Config.get();

    Sampler sampler = Sampler.Builder.forConfig(config, null);

    assertFalse(sampler instanceof AsmStandaloneSampler);
  }

  @WithConfig(key = TRACE_OTEL_EXPORTER, value = "otlp")
  @WithConfig(key = PRIORITY_SAMPLING, value = "false")
  @Test
  void parentBasedAlwaysOnSamplerReplacesAllSamplerWhenOtlpEnabledAndPrioritySamplingDisabled() {
    Config config = Config.get();

    Sampler sampler = Sampler.Builder.forConfig(config, null);

    assertInstanceOf(ParentBasedAlwaysOnSampler.class, sampler);
  }

  @WithConfig(key = PRIORITY_SAMPLING, value = "false")
  @Test
  void allSamplerSelectedWhenOtlpDisabledAndPrioritySamplingDisabled() {
    Config config = Config.get();

    Sampler sampler = Sampler.Builder.forConfig(config, null);

    assertInstanceOf(AllSampler.class, sampler);
    assertFalse(sampler instanceof ParentBasedAlwaysOnSampler);
  }

  @WithConfig(key = TRACE_OTEL_EXPORTER, value = "otlp")
  @WithConfig(key = TRACE_SAMPLE_RATE, value = "0.5")
  @Test
  void traceSamplingRulesRespectedWhenOtlpEnabled() {
    Config config = Config.get();

    Sampler sampler = Sampler.Builder.forConfig(config, null);

    assertInstanceOf(RuleBasedTraceSampler.class, sampler);
    assertFalse(sampler instanceof ParentBasedAlwaysOnSampler);
  }

  @WithConfig(key = TRACE_OTEL_EXPORTER, value = "otlp")
  @Test
  void
      parentBasedAlwaysOnSamplerReplacesRateByServiceTraceSamplerWhenOtlpEnabledWithDefaultPrioritySampling() {
    Config config = Config.get();

    Sampler sampler = Sampler.Builder.forConfig(config, null);

    assertInstanceOf(ParentBasedAlwaysOnSampler.class, sampler);
    assertFalse(sampler instanceof RateByServiceTraceSampler);
  }

  @WithConfig(key = TRACE_OTEL_EXPORTER, value = "otlp")
  @WithConfig(key = PRIORITY_SAMPLING_FORCE, value = "keep")
  @Test
  void forcePrioritySamplerRespectedWhenOtlpEnabledAndPrioritySamplingForcedKeep() {
    Config config = Config.get();

    Sampler sampler = Sampler.Builder.forConfig(config, null);

    assertInstanceOf(ForcePrioritySampler.class, sampler);
    assertFalse(sampler instanceof ParentBasedAlwaysOnSampler);
  }

  @WithConfig(key = TRACE_OTEL_EXPORTER, value = "otlp")
  @WithConfig(key = PRIORITY_SAMPLING_FORCE, value = "drop")
  @Test
  void forcePrioritySamplerRespectedWhenOtlpEnabledAndPrioritySamplingForcedDrop() {
    Config config = Config.get();

    Sampler sampler = Sampler.Builder.forConfig(config, null);

    assertInstanceOf(ForcePrioritySampler.class, sampler);
    assertFalse(sampler instanceof ParentBasedAlwaysOnSampler);
  }

  @WithConfig(key = TRACE_OTEL_EXPORTER, value = "otlp")
  @WithConfig(key = PRIORITY_SAMPLING, value = "false")
  @Test
  void spansBuiltWithOtlpEnabledAndPrioritySamplingDisabledHaveNonUnsetSamplingPriority() {
    Config config = Config.get();
    Sampler sampler = Sampler.Builder.forConfig(config, null);
    CoreTracer tracer = CoreTracer.builder().writer(new ListWriter()).sampler(sampler).build();
    try {
      DDSpan span = (DDSpan) tracer.buildSpan("datadog", "test").start();
      ((PrioritySampler) sampler).setSamplingPriority(span);

      assertNotNull(span.getSamplingPriority());
      assertEquals(SAMPLER_KEEP, (int) span.getSamplingPriority());

      span.finish();
    } finally {
      tracer.close();
    }
  }
}
