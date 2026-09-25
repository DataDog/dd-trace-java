package datadog.trace.common.sampling;

import static datadog.trace.api.config.AppSecConfig.APPSEC_ENABLED;
import static datadog.trace.api.config.AppSecConfig.APPSEC_SCA_ENABLED;
import static datadog.trace.api.config.GeneralConfig.APM_TRACING_ENABLED;
import static datadog.trace.api.config.GeneralConfig.DATA_JOBS_ENABLED;
import static datadog.trace.api.config.IastConfig.IAST_ENABLED;
import static datadog.trace.api.config.LlmObsConfig.LLMOBS_ENABLED;
import static datadog.trace.api.config.OtlpConfig.TRACE_OTEL_EXPORTER;
import static datadog.trace.api.config.TracerConfig.PRIORITY_SAMPLING;
import static datadog.trace.api.config.TracerConfig.PRIORITY_SAMPLING_FORCE;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLE_RATE;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.ProductTraceSource;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
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

  @WithConfig(key = APM_TRACING_ENABLED, value = "false")
  @Test
  void apmTracesDroppedWhenApmTracingDisabledAndNoOtherProductEnabled() {
    assertApmTracesDropped();
  }

  /**
   * LLM Observability rides the tracer but ships its spans to the LLM Observability intake, so
   * disabling APM tracing must drop the APM traces without disabling the tracer.
   */
  @WithConfig(key = APM_TRACING_ENABLED, value = "false")
  @WithConfig(key = LLMOBS_ENABLED, value = "true")
  @Test
  void apmTracesDroppedWhenApmTracingDisabledAndLlmObsEnabled() {
    assertApmTracesDropped();
  }

  /**
   * {@code manual.keep} force-keeps the span long before the sampler votes, locking the sampling
   * priority. With APM tracing disabled the drop still has to win, or the trace would be indexed
   * and billed as APM anyway — exactly what the setting opts out of.
   */
  @WithConfig(key = APM_TRACING_ENABLED, value = "false")
  @Test
  void apmTracesDroppedWhenApmTracingDisabledAndTraceManuallyKept() {
    Sampler sampler = Sampler.Builder.forConfig(Config.get(), null);

    assertEquals(
        SAMPLER_DROP,
        (int) samplingPriorityOfTrace(sampler, span -> span.setTag(DDTags.MANUAL_KEEP, true)));
  }

  /**
   * A trace a product asked for is not an APM trace the setting opts out of, so {@code manual.keep}
   * still wins once {@code _dd.p.ts} is marked — here for ASM. This mirrors dd-trace-js, which
   * honors {@code manual.keep} in standalone mode only alongside a product trace source.
   */
  @WithConfig(key = APM_TRACING_ENABLED, value = "false")
  @Test
  void manuallyKeptTracesKeptWhenApmTracingDisabledAndMarkedForAsm() {
    Sampler sampler = Sampler.Builder.forConfig(Config.get(), null);

    assertEquals(
        USER_KEEP,
        (int)
            samplingPriorityOfTrace(
                sampler,
                span -> {
                  span.setTag(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.ASM);
                  span.setTag(DDTags.MANUAL_KEEP, true);
                }));
  }

  /**
   * Data Jobs keeps its traces with a {@link SamplingMechanism#DATA_JOBS} priority rather than a
   * {@code _dd.p.ts} mark, so the APM traces drop has to leave it alone.
   */
  @WithConfig(key = APM_TRACING_ENABLED, value = "false")
  @WithConfig(key = DATA_JOBS_ENABLED, value = "true")
  @Test
  void dataJobsTracesKeptWhenApmTracingDisabled() {
    Sampler sampler = Sampler.Builder.forConfig(Config.get(), null);

    assertEquals(
        USER_KEEP,
        (int)
            samplingPriorityOfTrace(
                sampler, span -> span.setSamplingPriority(USER_KEEP, SamplingMechanism.DATA_JOBS)));
  }

  /**
   * AppSec can be activated by Remote Configuration after the sampler has been built, and that only
   * flips {@link ActiveSubsystems#APPSEC_ACTIVE} — the immutable config the sampler was chosen from
   * still says AppSec is off. Standalone ASM then needs its trickle of 1 APM trace per minute to
   * keep the service in the service catalog, so the first trace has to be kept.
   */
  @WithConfig(key = APM_TRACING_ENABLED, value = "false")
  @Test
  void apmTracesKeptOncePerMinuteWhenAppSecIsActivatedAtRuntime() {
    Sampler sampler = Sampler.Builder.forConfig(Config.get(), null);
    assertInstanceOf(ApmTracingDisabledSampler.class, sampler);

    // the trickle is one per minute, so only the first of the two traces is kept
    withAppSecActive(
        () ->
            assertEquals(
                asList((int) SAMPLER_KEEP, (int) SAMPLER_DROP),
                samplingPrioritiesOfTraces(sampler, 2)));
  }

  /** Deactivating AppSec at runtime puts the drop back. */
  @WithConfig(key = APM_TRACING_ENABLED, value = "false")
  @Test
  void apmTracesDroppedWhenAppSecIsDeactivatedAtRuntime() {
    Sampler sampler = Sampler.Builder.forConfig(Config.get(), null);

    withAppSecActive(
        () ->
            assertEquals(
                singletonList((int) SAMPLER_KEEP), samplingPrioritiesOfTraces(sampler, 1)));

    assertEquals(singletonList((int) SAMPLER_DROP), samplingPrioritiesOfTraces(sampler, 1));
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

  /**
   * Runs {@code assertions} with AppSec active, as Remote Configuration would leave it, always
   * restoring the flag afterwards — it is global mutable state shared with every other test.
   */
  private static void withAppSecActive(Runnable assertions) {
    boolean wasActive = ActiveSubsystems.APPSEC_ACTIVE;
    ActiveSubsystems.APPSEC_ACTIVE = true;
    try {
      assertions.run();
    } finally {
      ActiveSubsystems.APPSEC_ACTIVE = wasActive;
    }
  }

  /**
   * Runs {@code traceCount} traces through a tracer using {@code sampler} and returns the priority
   * each one was written with.
   *
   * <p>The sampler is left to vote from the publish path ({@code
   * TraceCollector.setSamplingPriorityIfNecessary}) rather than being called directly: with APM
   * tracing disabled, {@code SamplingMechanism.APPSEC} is exempt from the sampling priority lock,
   * so an extra direct call would both consume a slot of the one-per-minute trickle and overwrite
   * the priority the publish path went on to assign.
   */
  private static List<Integer> samplingPrioritiesOfTraces(Sampler sampler, int traceCount) {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = CoreTracer.builder().writer(writer).sampler(sampler).build();
    try {
      for (int i = 0; i < traceCount; i++) {
        tracer.buildSpan("datadog", "test").start().finish();
      }
      writer.waitForTraces(traceCount);

      List<Integer> priorities = new ArrayList<>();
      for (List<DDSpan> trace : writer) {
        priorities.add(trace.get(0).getSamplingPriority());
      }
      return priorities;
    } catch (InterruptedException | TimeoutException e) {
      throw new AssertionError("the traces were never written", e);
    } finally {
      tracer.close();
    }
  }

  /**
   * Runs a single trace through a tracer using {@code sampler}, applying {@code tagger} to its root
   * span before it is finished, and returns the priority it was written with.
   */
  private static int samplingPriorityOfTrace(Sampler sampler, Consumer<DDSpan> tagger) {
    ListWriter writer = new ListWriter();
    CoreTracer tracer = CoreTracer.builder().writer(writer).sampler(sampler).build();
    try {
      DDSpan span = (DDSpan) tracer.buildSpan("datadog", "test").start();
      tagger.accept(span);
      span.finish();
      writer.waitForTraces(1);

      return writer.firstTrace().get(0).getSamplingPriority();
    } catch (InterruptedException | TimeoutException e) {
      throw new AssertionError("the trace was never written", e);
    } finally {
      tracer.close();
    }
  }

  /**
   * Asserts the trace is marked dropped but still written. Products that ride the tracer and ship
   * their spans elsewhere — LLM Observability sends them to its own intake — depend on the spans
   * still reaching the writers, so dropping APM traces must mean a drop priority, not a discarded
   * span.
   */
  private static void assertApmTracesDropped() {
    Sampler sampler = Sampler.Builder.forConfig(Config.get(), null);

    assertInstanceOf(ApmTracingDisabledSampler.class, sampler);

    ListWriter writer = new ListWriter();
    CoreTracer tracer = CoreTracer.builder().writer(writer).sampler(sampler).build();
    try {
      DDSpan span = (DDSpan) tracer.buildSpan("datadog", "test").start();
      ((PrioritySampler) sampler).setSamplingPriority(span);

      assertEquals(SAMPLER_DROP, (int) span.getSamplingPriority());
      assertTrue(sampler.sample(span));

      span.finish();
      writer.waitForTraces(1);
      assertEquals(singletonList(span), writer.firstTrace());
    } catch (InterruptedException | TimeoutException e) {
      throw new AssertionError("the dropped trace was never written", e);
    } finally {
      tracer.close();
    }
  }
}
