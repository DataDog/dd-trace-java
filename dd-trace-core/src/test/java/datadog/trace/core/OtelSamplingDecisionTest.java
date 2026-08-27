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

  @Test
  void initialAgentRateDoesNotEstablishOtelProbabilityState() {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      DDSpan span = newRootSpan(tracer);

      new RateByServiceTraceSampler().setSamplingPriority(span);

      assertFalse(w3cHeader(span).contains("ot="));
    } finally {
      tracer.close();
    }
  }

  @Test
  void loadedAgentRateEstablishesOtelProbabilityState() {
    RateByServiceTraceSampler sampler = new RateByServiceTraceSampler();
    sampler.onResponse("traces", agentRates(0.5));
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      DDSpan span = newRootSpan(tracer);

      sampler.setSamplingPriority(span);

      String header = w3cHeader(span);
      assertTrue(header.contains("ot=rv:"));
      assertTrue(header.contains(";th:8"));
    } finally {
      tracer.close();
    }
  }

  @Test
  void loadedZeroAgentRateDoesNotEstablishOtelProbabilityState() {
    RateByServiceTraceSampler sampler = new RateByServiceTraceSampler();
    sampler.onResponse("traces", agentRates(0));
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      DDSpan span = newRootSpan(tracer);

      sampler.setSamplingPriority(span);

      assertFalse(w3cHeader(span).contains("ot="));
    } finally {
      tracer.close();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void configuredRulesEstablishOtelProbabilityState(boolean traceRule) {
    Properties properties = new Properties();
    if (traceRule) {
      properties.setProperty(TRACE_SAMPLING_RULES, "[{\"sample_rate\": 0.5}]");
    } else {
      properties.setProperty(TRACE_SAMPLE_RATE, "0.5");
    }
    properties.setProperty(TRACE_RATE_LIMIT, "10000000");
    PrioritySampler sampler = (PrioritySampler) Sampler.Builder.forConfig(properties);
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      DDSpan span = newRootSpan(tracer);

      sampler.setSamplingPriority(span);

      String header = w3cHeader(span);
      assertTrue(header.contains("ot=rv:"));
      assertTrue(header.contains(";th:8"));
    } finally {
      tracer.close();
    }
  }

  @Test
  void limiterRejectionDoesNotTurnProbabilityKeepIntoOtelDrop() {
    Properties properties = new Properties();
    properties.setProperty(TRACE_SAMPLING_RULES, "[{\"sample_rate\": 1}]");
    properties.setProperty(TRACE_RATE_LIMIT, "1");
    PrioritySampler sampler = (PrioritySampler) Sampler.Builder.forConfig(properties);
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      DDSpan allowed = newRootSpan(tracer);
      DDSpan rejected = newRootSpan(tracer);

      sampler.setSamplingPriority(allowed);
      sampler.setSamplingPriority(rejected);

      assertTrue(w3cHeader(allowed).contains("ot=rv:"));
      assertEquals(USER_DROP, rejected.samplingPriority());
      assertFalse(w3cHeader(rejected).contains("ot="));
    } finally {
      tracer.close();
    }
  }

  @Test
  void manualOverrideRemovesLocallyGeneratedProbabilityState() {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      DDSpan span = newRootSpan(tracer);
      span.setSamplingPriority(USER_KEEP, SAMPLING_RULE_RATE, 0.5, LOCAL_USER_RULE, true);
      assertTrue(w3cHeader(span).contains("ot=rv:"));

      span.spanContext().forceKeep();

      assertFalse(w3cHeader(span).contains("ot="));
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
