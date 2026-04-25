package datadog.trace.core;

import static datadog.trace.api.config.TracerConfig.TRACE_RATE_LIMIT;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLE_RATE;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_RULES;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_SERVICE_RULES;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.common.sampling.PrioritySampler;
import datadog.trace.common.sampling.RateByServiceTraceSampler;
import datadog.trace.common.sampling.Sampler;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.propagation.PropagationTags;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

public class KnuthSamplingRateTest extends DDCoreJavaSpecification {

  @TableTest({
    "scenario                         | rate            | expected",
    "1.0                              | 1.0             | 1       ",
    "0.5                              | 0.5             | 0.5     ",
    "0.1                              | 0.1             | 0.1     ",
    "0.0                              | 0.0             | 0       ",
    "0.765432                         | 0.765432        | 0.765432",
    "0.7654321 rounds to 6 decimal    | 0.7654321       | 0.765432",
    "0.123456                         | 0.123456        | 0.123456",
    "0.100000 trailing zeros          | 0.100000        | 0.1     ",
    "0.250 trailing zero              | 0.250           | 0.25    ",
    "0.05                             | 0.05            | 0.05    ",
    "0.0123456789 rounds at 6dp       | 0.0123456789    | 0.012346",
    "0.001                            | 0.001           | 0.001   ",
    "0.00500 trailing zeros           | 0.00500         | 0.005   ",
    "0.00123456789 rounds at 6dp      | 0.00123456789   | 0.001235",
    "0.0001                           | 0.0001          | 0.0001  ",
    "0.000500 trailing zeros          | 0.000500        | 0.0005  ",
    "0.000123456789 rounds at 6dp     | 0.000123456789  | 0.000123",
    "0.9999995 rounds up to 1         | 0.9999995       | 1       ",
    "0.00001                          | 0.00001         | 0.00001 ",
    "0.000050 trailing zeros          | 0.000050        | 0.00005 ",
    "1.23456789e-5 rounds at 6dp      | 0.0000123456789 | 0.000012",
    "1e-7 below precision rounds to 0 | 0.0000001       | 0       ",
    "5.5e-10 below precision          | 0.00000000055   | 0       ",
    "0.000001 six decimal boundary    | 0.000001        | 0.000001",
    "0.00000051 rounds up             | 0.00000051      | 0.000001"
  })
  void updateKnuthSamplingRateFormatsRateCorrectly(String scenario, double rate, String expected) {
    PropagationTags pTags = PropagationTags.factory().empty();
    pTags.updateKnuthSamplingRate(rate);
    Map<String, String> tagMap = pTags.createTagMap();
    assertEquals(expected, tagMap.get("_dd.p.ksr"));
  }

  @TableTest({
    "scenario | rate | expectedKsr",
    "rate 1.0 | 1.0  | 1          ",
    "rate 0.5 | 0.5  | 0.5        ",
    "rate 0.0 | 0.0  | 0          "
  })
  void agentRateSamplerSetsKsrPropagatedTag(String scenario, double rate, String expectedKsr) {
    RateByServiceTraceSampler serviceSampler = new RateByServiceTraceSampler();
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    Map<String, Number> rates = new HashMap<>();
    rates.put("service:,env:", rate);
    Map<String, Map<String, Number>> response = new HashMap<>();
    response.put("rate_by_service", rates);
    serviceSampler.onResponse("traces", response);

    DDSpan span =
        (DDSpan)
            tracer
                .buildSpan("fakeOperation")
                .withServiceName("spock")
                .withTag("env", "test")
                .ignoreActiveSpan()
                .start();
    serviceSampler.setSamplingPriority(span);

    Map<String, String> propagationMap = span.context().getPropagationTags().createTagMap();
    String ksr = propagationMap.get("_dd.p.ksr");

    assertEquals(expectedKsr, ksr);
    tracer.close();
  }

  @TableTest({
    "scenario         | jsonRules                                            | expectedKsr",
    "rate 1 matches   | '[{\"service\": \"service\", \"sample_rate\": 1}]'   | 1          ",
    "rate 0.5 matches | '[{\"service\": \"service\", \"sample_rate\": 0.5}]' | 0.5        ",
    "rate 0 matches   | '[{\"service\": \"service\", \"sample_rate\": 0}]'   | 0          "
  })
  void ruleBasedSamplerSetsKsrPropagatedTagWhenRuleMatches(
      String scenario, String jsonRules, String expectedKsr) {
    Properties properties = new Properties();
    properties.setProperty(TRACE_SAMPLING_RULES, jsonRules);
    properties.setProperty(TRACE_RATE_LIMIT, "50");
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    Sampler sampler = Sampler.Builder.forConfig(properties);
    DDSpan span =
        (DDSpan)
            tracer
                .buildSpan("operation")
                .withServiceName("service")
                .withTag("env", "bar")
                .ignoreActiveSpan()
                .start();
    ((PrioritySampler) sampler).setSamplingPriority(span);

    Map<String, String> propagationMap = span.context().getPropagationTags().createTagMap();
    String ksr = propagationMap.get("_dd.p.ksr");

    assertEquals(expectedKsr, ksr);
    tracer.close();
  }

  @Test
  void ruleBasedSamplerFallbackToAgentSamplerSetsKsr() {
    Properties properties = new Properties();
    properties.setProperty(
        TRACE_SAMPLING_RULES, "[{\"service\": \"nomatch\", \"sample_rate\": 0.5}]");
    properties.setProperty(TRACE_RATE_LIMIT, "50");
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    Sampler sampler = Sampler.Builder.forConfig(properties);
    DDSpan span =
        (DDSpan)
            tracer
                .buildSpan("operation")
                .withServiceName("service")
                .withTag("env", "bar")
                .ignoreActiveSpan()
                .start();
    ((PrioritySampler) sampler).setSamplingPriority(span);

    Map<String, String> propagationMap = span.context().getPropagationTags().createTagMap();
    String ksr = propagationMap.get("_dd.p.ksr");

    assertEquals("1", ksr);
    assertEquals(SAMPLER_KEEP, (int) span.getSamplingPriority());
    tracer.close();
  }

  @Test
  void serviceRuleSamplerSetsKsrPropagatedTag() {
    Properties properties = new Properties();
    properties.setProperty(TRACE_SAMPLING_SERVICE_RULES, "service:0.75");
    properties.setProperty(TRACE_RATE_LIMIT, "50");
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    Sampler sampler = Sampler.Builder.forConfig(properties);
    DDSpan span =
        (DDSpan)
            tracer
                .buildSpan("operation")
                .withServiceName("service")
                .withTag("env", "bar")
                .ignoreActiveSpan()
                .start();
    ((PrioritySampler) sampler).setSamplingPriority(span);

    Map<String, String> propagationMap = span.context().getPropagationTags().createTagMap();
    String ksr = propagationMap.get("_dd.p.ksr");

    assertEquals("0.75", ksr);
    tracer.close();
  }

  @Test
  void defaultRateSamplerSetsKsrPropagatedTag() {
    Properties properties = new Properties();
    properties.setProperty(TRACE_SAMPLE_RATE, "0.25");
    properties.setProperty(TRACE_RATE_LIMIT, "50");
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    Sampler sampler = Sampler.Builder.forConfig(properties);
    DDSpan span =
        (DDSpan)
            tracer
                .buildSpan("operation")
                .withServiceName("service")
                .withTag("env", "bar")
                .ignoreActiveSpan()
                .start();
    ((PrioritySampler) sampler).setSamplingPriority(span);

    Map<String, String> propagationMap = span.context().getPropagationTags().createTagMap();
    String ksr = propagationMap.get("_dd.p.ksr");

    assertEquals("0.25", ksr);
    tracer.close();
  }

  @Test
  void ksrIsPropagatedViaXDatadogTagsHeader() {
    RateByServiceTraceSampler serviceSampler = new RateByServiceTraceSampler();
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    Map<String, Number> rates = new HashMap<>();
    rates.put("service:,env:", 0.5);
    Map<String, Map<String, Number>> response = new HashMap<>();
    response.put("rate_by_service", rates);
    serviceSampler.onResponse("traces", response);

    DDSpan span =
        (DDSpan)
            tracer
                .buildSpan("fakeOperation")
                .withServiceName("spock")
                .withTag("env", "test")
                .ignoreActiveSpan()
                .start();
    serviceSampler.setSamplingPriority(span);

    String headerValue =
        span.context().getPropagationTags().headerValue(PropagationTags.HeaderType.DATADOG);

    assertNotNull(headerValue);
    assertTrue(headerValue.contains("_dd.p.ksr=0.5"));
    tracer.close();
  }
}
