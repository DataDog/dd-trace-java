package datadog.trace.core;

import static datadog.trace.api.config.TracerConfig.TRACE_RATE_LIMIT;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLE_RATE;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_RULES;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.api.sampling.SamplingMechanism.LOCAL_USER_RULE;
import static datadog.trace.common.sampling.RuleBasedTraceSampler.SAMPLING_RULE_RATE;
import static datadog.trace.core.propagation.PropagationTags.HeaderType.W3C;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
  private static final String INSTRUMENTATION_NAME = "datadog";
  private static final String OPERATION_NAME = "operation";
  private static final String SERVICE_NAME = "service";
  private static final String OTEL_MEMBER = "ot=";
  private static final String OTEL_RANDOM_VALUE_PREFIX = "ot=rv:";
  private static final String HALF_THRESHOLD = ";th:8";
  private static final double SAMPLE_RATE_0_5 = 0.5;
  private static final String SAMPLE_RATE_0_5_RULE = "[{\"sample_rate\": 0.5}]";
  private static final String FULL_SAMPLE_RATE_RULE = "[{\"sample_rate\": 1}]";
  // Keeps configured-rule vectors out of the limiter path.
  private static final String HIGH_RATE_LIMIT = "10000000";
  private static final String ONE_PER_SECOND_RATE_LIMIT = "1";

  @Test
  void initialAgentRateDoesNotEstablishOtelProbabilityState() {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      DDSpan span = newRootSpan(tracer);

      new RateByServiceTraceSampler().setSamplingPriority(span);

      assertFalse(w3cHeader(span).contains(OTEL_MEMBER));
    } finally {
      tracer.close();
    }
  }

  @Test
  void loadedAgentRateEstablishesOtelProbabilityState() {
    RateByServiceTraceSampler sampler = new RateByServiceTraceSampler();
    sampler.onResponse(AGENT_RATE_ENDPOINT, agentRates(SAMPLE_RATE_0_5));
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      DDSpan span = newRootSpan(tracer);

      sampler.setSamplingPriority(span);

      String header = w3cHeader(span);
      assertTrue(header.contains(OTEL_RANDOM_VALUE_PREFIX));
      assertTrue(header.contains(HALF_THRESHOLD));
    } finally {
      tracer.close();
    }
  }

  @Test
  void loadedZeroAgentRateDoesNotEstablishOtelProbabilityState() {
    RateByServiceTraceSampler sampler = new RateByServiceTraceSampler();
    sampler.onResponse(AGENT_RATE_ENDPOINT, agentRates(0));
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      DDSpan span = newRootSpan(tracer);

      sampler.setSamplingPriority(span);

      assertFalse(w3cHeader(span).contains(OTEL_MEMBER));
    } finally {
      tracer.close();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void configuredRulesEstablishOtelProbabilityState(boolean traceRule) {
    Properties properties = new Properties();
    if (traceRule) {
      properties.setProperty(TRACE_SAMPLING_RULES, SAMPLE_RATE_0_5_RULE);
    } else {
      properties.setProperty(TRACE_SAMPLE_RATE, String.valueOf(SAMPLE_RATE_0_5));
    }
    properties.setProperty(TRACE_RATE_LIMIT, HIGH_RATE_LIMIT);
    PrioritySampler sampler = (PrioritySampler) Sampler.Builder.forConfig(properties);
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      DDSpan span = newRootSpan(tracer);

      sampler.setSamplingPriority(span);

      String header = w3cHeader(span);
      assertTrue(header.contains(OTEL_RANDOM_VALUE_PREFIX));
      assertTrue(header.contains(HALF_THRESHOLD));
    } finally {
      tracer.close();
    }
  }

  @Test
  void limiterRejectionDoesNotTurnProbabilityKeepIntoOtelDrop() {
    Properties properties = new Properties();
    properties.setProperty(TRACE_SAMPLING_RULES, FULL_SAMPLE_RATE_RULE);
    properties.setProperty(TRACE_RATE_LIMIT, ONE_PER_SECOND_RATE_LIMIT);
    PrioritySampler sampler = (PrioritySampler) Sampler.Builder.forConfig(properties);
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      DDSpan allowed = newRootSpan(tracer);
      DDSpan rejected = newRootSpan(tracer);

      sampler.setSamplingPriority(allowed);
      sampler.setSamplingPriority(rejected);

      assertTrue(w3cHeader(allowed).contains(OTEL_RANDOM_VALUE_PREFIX));
      assertEquals(USER_DROP, rejected.samplingPriority());
      assertFalse(w3cHeader(rejected).contains(OTEL_MEMBER));
    } finally {
      tracer.close();
    }
  }

  @Test
  void manualOverrideRemovesLocallyGeneratedProbabilityState() {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      DDSpan span = newRootSpan(tracer);
      span.setSamplingPriority(
          USER_KEEP, SAMPLING_RULE_RATE, SAMPLE_RATE_0_5, LOCAL_USER_RULE, true);
      assertTrue(w3cHeader(span).contains(OTEL_RANDOM_VALUE_PREFIX));

      span.spanContext().forceKeep();

      assertFalse(w3cHeader(span).contains(OTEL_MEMBER));
    } finally {
      tracer.close();
    }
  }

  private static DDSpan newRootSpan(CoreTracer tracer) {
    return (DDSpan)
        tracer
            .buildSpan(INSTRUMENTATION_NAME, OPERATION_NAME)
            .withServiceName(SERVICE_NAME)
            .ignoreActiveSpan()
            .start();
  }

  private static String w3cHeader(DDSpan span) {
    String header = span.spanContext().getPropagationTags().headerValue(W3C);
    assertNotNull(header);
    return header;
  }

  private static Map<String, Map<String, Number>> agentRates(double rate) {
    Map<String, Number> rates = new HashMap<>();
    rates.put("service:,env:", rate);
    Map<String, Map<String, Number>> response = new HashMap<>();
    response.put("rate_by_service", rates);
    return response;
  }
}
