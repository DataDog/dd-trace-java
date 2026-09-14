package datadog.trace.core;

import static datadog.trace.api.config.TracerConfig.TRACE_RATE_LIMIT;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLE_RATE;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_RULES;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.api.sampling.SamplingMechanism.LOCAL_USER_RULE;
import static datadog.trace.common.sampling.RuleBasedTraceSampler.SAMPLING_RULE_RATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.common.sampling.PrioritySampler;
import datadog.trace.common.sampling.RateByServiceTraceSampler;
import datadog.trace.common.sampling.Sampler;
import datadog.trace.common.writer.ListWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OtelSamplingDecisionTest extends DDCoreJavaSpecification {
  private static final String AGENT_RATE_ENDPOINT = "traces";
  private static final String OTEL_RANDOM_VALUE_PREFIX = "rv:";
  private static final String HALF_THRESHOLD = ";th:8";
  private static final String MAX_THRESHOLD = ";th:ffffffffffffff";
  private static final double HALF_RATE = 0.5;
  private static final String HALF_RATE_RULE = "[{\"sample_rate\": 0.5}]";
  private static final String FULL_RATE_RULE = "[{\"sample_rate\": 1}]";

  @Test
  void initialAgentRateEstablishesDefaultProbabilityState() {
    RateByServiceTraceSampler sampler = new RateByServiceTraceSampler();
    withRootSpan(
        span -> {
          sampler.setSamplingPriority(span);

          String state = otelTraceState(span);
          assertTrue(state.contains(OTEL_RANDOM_VALUE_PREFIX));
          assertTrue(state.contains(";th:0"));
        });
  }

  @Test
  void loadedAgentRateEstablishesProbabilityState() {
    RateByServiceTraceSampler sampler = new RateByServiceTraceSampler();
    sampler.onResponse(AGENT_RATE_ENDPOINT, agentRates(HALF_RATE));
    withRootSpan(
        span -> {
          sampler.setSamplingPriority(span);

          String state = otelTraceState(span);
          assertTrue(state.contains(OTEL_RANDOM_VALUE_PREFIX));
          assertTrue(state.contains(HALF_THRESHOLD));
        });
  }

  @Test
  void zeroAgentRateUsesDropConsistentMaximumThreshold() {
    RateByServiceTraceSampler sampler = new RateByServiceTraceSampler();
    sampler.onResponse(AGENT_RATE_ENDPOINT, agentRates(0));
    withRootSpan(
        span -> {
          sampler.setSamplingPriority(span);

          String state = otelTraceState(span);
          assertTrue(state.contains(OTEL_RANDOM_VALUE_PREFIX));
          assertFalse(state.contains("rv:ffffffffffffff"));
          assertTrue(state.contains(MAX_THRESHOLD));
        });
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void configuredRulesEstablishProbabilityState(boolean traceRule) {
    Properties properties = new Properties();
    properties.setProperty(
        traceRule ? TRACE_SAMPLING_RULES : TRACE_SAMPLE_RATE,
        traceRule ? HALF_RATE_RULE : String.valueOf(HALF_RATE));
    properties.setProperty(TRACE_RATE_LIMIT, "10000000");
    PrioritySampler sampler = (PrioritySampler) Sampler.Builder.forConfig(properties);
    withRootSpan(
        span -> {
          sampler.setSamplingPriority(span);

          String state = otelTraceState(span);
          assertTrue(state.contains(OTEL_RANDOM_VALUE_PREFIX));
          assertTrue(state.contains(HALF_THRESHOLD));
        });
  }

  @Test
  void limiterRejectionDoesNotFabricateProbabilityState() {
    Properties properties = new Properties();
    properties.setProperty(TRACE_SAMPLING_RULES, FULL_RATE_RULE);
    properties.setProperty(TRACE_RATE_LIMIT, "1");
    PrioritySampler sampler = (PrioritySampler) Sampler.Builder.forConfig(properties);
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      DDSpan allowed = newRootSpan(tracer);
      DDSpan rejected = newRootSpan(tracer);

      sampler.setSamplingPriority(allowed);
      sampler.setSamplingPriority(rejected);

      assertTrue(otelTraceState(allowed).contains(OTEL_RANDOM_VALUE_PREFIX));
      assertEquals(USER_DROP, rejected.samplingPriority());
      assertNull(otelTraceState(rejected));
    } finally {
      tracer.close();
    }
  }

  @Test
  void manualOverrideRemovesLocallyGeneratedProbabilityState() {
    withRootSpan(
        span -> {
          span.setSamplingPriority(USER_KEEP, SAMPLING_RULE_RATE, HALF_RATE, true, LOCAL_USER_RULE);
          assertTrue(otelTraceState(span).contains(OTEL_RANDOM_VALUE_PREFIX));

          span.spanContext().forceKeep();

          assertNull(otelTraceState(span));
        });
  }

  private void withRootSpan(java.util.function.Consumer<DDSpan> test) {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      test.accept(newRootSpan(tracer));
    } finally {
      tracer.close();
    }
  }

  private static DDSpan newRootSpan(CoreTracer tracer) {
    return (DDSpan)
        tracer
            .buildSpan("datadog", "operation")
            .withServiceName("service")
            .ignoreActiveSpan()
            .start();
  }

  private static String otelTraceState(DDSpan span) {
    CharSequence state =
        span.spanContext().getPropagationTags().samplingState().getOtelTraceState();
    return state == null ? null : state.toString();
  }

  private static Map<String, Map<String, Number>> agentRates(double rate) {
    Map<String, Number> rates = new HashMap<>();
    rates.put("service:,env:", rate);
    Map<String, Map<String, Number>> response = new HashMap<>();
    response.put("rate_by_service", rates);
    return response;
  }
}
