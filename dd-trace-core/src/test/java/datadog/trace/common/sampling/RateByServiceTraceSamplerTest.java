package datadog.trace.common.sampling;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.DDTags;
import datadog.trace.api.time.ControllableTimeSource;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.common.writer.LoggingWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import datadog.trace.core.DDSpanContext;
import datadog.trace.test.junit.utils.converter.PrioritySamplingConverter;
import datadog.trace.test.junit.utils.tabletest.BoxedValueConverter;
import datadog.trace.test.junit.utils.tabletest.TableTestTypeConverters;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;
import org.tabletest.junit.TypeConverterSources;

@TypeConverterSources(TableTestTypeConverters.class)
class RateByServiceTraceSamplerTest extends DDCoreJavaSpecification {

  /** Build a rate_by_service response map from a single entry (the fallback key). */
  private static Map<String, Map<String, Number>> rateResponse(String key, Number rate) {
    Map<String, Number> byService = new HashMap<>();
    byService.put(key, rate);
    Map<String, Map<String, Number>> response = new HashMap<>();
    response.put("rate_by_service", byService);
    return response;
  }

  /** Build a rate_by_service response map from multiple entries preserving insertion order. */
  private static Map<String, Map<String, Number>> rateResponse(String[][] entries) {
    Map<String, Number> byService = new LinkedHashMap<>();
    for (String[] entry : entries) {
      byService.put(entry[0], entry[1] == null ? null : Double.parseDouble(entry[1]));
    }
    Map<String, Map<String, Number>> response = new HashMap<>();
    response.put("rate_by_service", byService);
    return response;
  }

  // these values are all precisely represented in floating point
  @TableTest({
    "scenario  | rate | expectedRate",
    "null rate |      | 1.0         ",
    "rate 1    | 1    | 1.0         ",
    "rate 0    | 0    | 0.0         ",
    "rate -5   | -5   | 1.0         ",
    "rate 5    | 5    | 1.0         ",
    "rate 0.5  | 0.5  | 0.5         "
  })
  void invalidRateTo1(Number rate, double expectedRate) {
    RateByServiceTraceSampler serviceSampler = new RateByServiceTraceSampler();
    Map<String, Number> byService = new HashMap<>();
    byService.put("service:,env:", rate);
    Map<String, Map<String, Number>> response = new HashMap<>();
    response.put("rate_by_service", byService);
    serviceSampler.onResponse("traces", response);

    assertEquals(expectedRate, serviceSampler.fallbackSampleRate(), 1e-6);
    assertEquals(expectedRate, serviceSampler.sampleRateFor("not", "found"), 1e-6);
  }

  @TableTest({
    "scenario            | service | env   | expectedRate",
    "foo/bar             | foo     | bar   | 0.8         ",
    "Foo/BAR case insens | Foo     | BAR   | 0.8         ",
    "FOO/BAR case insens | FOO     | BAR   | 0.8         ",
    "not found           | not     | found | 0.2         ",
    "foo/baz fallback    | foo     | baz   | 0.2         ",
    "fu/bar no match     | fu      | bar   | 0.2         "
  })
  void rateSelection(String service, String env, double expectedRate) {
    RateByServiceTraceSampler serviceSampler = new RateByServiceTraceSampler();
    serviceSampler.onResponse(
        "traces",
        rateResponse(
            new String[][] {
              {"service:foo,env:bar", "0.8"},
              {"service:,env:", "0.20"}
            }));

    double sampleRate = serviceSampler.sampleRateFor(env, service);
    assertTrue(sampleRate > expectedRate - 0.01);
    assertTrue(sampleRate < expectedRate + 0.01);
  }

  // case insensitive equivalence -- undefined behavior, first one wins
  @TableTest({
    "scenario | service | env | expectedRate",
    "foo/bar  | foo     | bar | 0.8         ",
    "foo/Bar  | foo     | Bar | 0.8         ",
    "Foo/BAR  | Foo     | BAR | 0.8         ",
    "FOO/BAR  | FOO     | BAR | 0.8         ",
    "foo/baz  | foo     | baz | 0.3         ",
    "FOO/BAZ  | FOO     | BAZ | 0.3         ",
    "quux/baz | quux    | baz | 0.4         "
  })
  void ratePartialAndFullCollisions(String service, String env, double expectedRate) {
    RateByServiceTraceSampler serviceSampler = new RateByServiceTraceSampler();
    serviceSampler.onResponse(
        "traces",
        rateResponse(
            new String[][] {
              {"service:foo,env:bar", "0.8"},
              {"service:FOO,env:BAR", "0.2"},
              {"service:FOO,env:BAZ", "0.3"},
              {"service:quux,env:BAZ", "0.4"}
            }));

    double sampleRate = serviceSampler.sampleRateFor(env, service);
    assertTrue(sampleRate > expectedRate - 0.01);
    assertTrue(sampleRate < expectedRate + 0.01);
  }

  @Test
  void rateByServiceName() {
    RateByServiceTraceSampler serviceSampler = new RateByServiceTraceSampler();
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    try {
      serviceSampler.onResponse("traces", rateResponse("service:spock,env:test", 0.0));
      DDSpan span1 =
          (DDSpan)
              tracer
                  .buildSpan("datadog", "fakeOperation")
                  .withServiceName("foo")
                  .withTag("env", "bar")
                  .ignoreActiveSpan()
                  .start();
      serviceSampler.setSamplingPriority(span1);

      assertEquals(SAMPLER_KEEP, span1.getSamplingPriority());
      assertTrue(serviceSampler.sample(span1));

      // case-insensitive equivalence - undefined in spec, but implemented as first one wins
      serviceSampler.onResponse(
          "traces",
          rateResponse(
              new String[][] {
                {"service:spock,env:test", "1.0"},
                {"service:SPOCK,env:Test", "0.0"}
              }));
      DDSpan span2 =
          (DDSpan)
              tracer
                  .buildSpan("datadog", "fakeOperation")
                  .withServiceName("spock")
                  .withTag("env", "test")
                  .ignoreActiveSpan()
                  .start();
      serviceSampler.setSamplingPriority(span2);

      assertEquals(SAMPLER_KEEP, span2.getSamplingPriority());
      assertTrue(serviceSampler.sample(span2));
    } finally {
      tracer.close();
    }
  }

  @Test
  void rateByServiceNameCaseInsensitive() {
    RateByServiceTraceSampler serviceSampler = new RateByServiceTraceSampler();
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      serviceSampler.onResponse("traces", rateResponse("service:spock,env:test", 1.0));
      DDSpan span =
          (DDSpan)
              tracer
                  .buildSpan("datadog", "fakeOperation")
                  .withServiceName("SPOCK")
                  .withTag("env", "Test")
                  .ignoreActiveSpan()
                  .start();
      serviceSampler.setSamplingPriority(span);

      assertEquals(SAMPLER_KEEP, span.getSamplingPriority());
      assertTrue(serviceSampler.sample(span));
    } finally {
      tracer.close();
    }
  }

  @Test
  void samplingPrioritySetOnContext() {
    RateByServiceTraceSampler serviceSampler = new RateByServiceTraceSampler();
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    serviceSampler.onResponse("traces", rateResponse("service:,env:", 1.0));
    try {
      DDSpan span =
          (DDSpan)
              tracer
                  .buildSpan("datadog", "fakeOperation")
                  .withServiceName("spock")
                  .withTag("env", "test")
                  .ignoreActiveSpan()
                  .start();
      serviceSampler.setSamplingPriority(span);

      // sets correctly on root span
      assertEquals(SAMPLER_KEEP, span.getSamplingPriority());
      // RateByServiceSampler must not set the sample rate
      assertNull(span.getTag(DDSpanContext.SAMPLE_RATE_KEY));
    } finally {
      tracer.close();
    }
  }

  @Test
  void samplingPrioritySetWhenServiceLater() throws Exception {
    RateByServiceTraceSampler sampler = new RateByServiceTraceSampler();
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).sampler(sampler).build();
    try {
      sampler.onResponse(
          "test",
          rateResponse(
              new String[][] {
                {"service:,env:", "1.0"},
                {"service:spock,env:", "0.0"}
              }));

      DDSpan span = (DDSpan) tracer.buildSpan("datadog", "test").start();

      assertNull(span.getSamplingPriority());

      span.setTag(DDTags.SERVICE_NAME, "spock");

      span.finish();
      writer.waitForTraces(1);
      assertEquals(SAMPLER_DROP, span.getSamplingPriority());

      span =
          (DDSpan)
              tracer.buildSpan("datadog", "test").withTag(DDTags.SERVICE_NAME, "spock").start();
      span.finish();
      writer.waitForTraces(2);

      assertEquals(SAMPLER_DROP, span.getSamplingPriority());
    } finally {
      tracer.close();
    }
  }

  @TableTest({
    "scenario         | tagName     | tagValue | expectedPriority          ",
    "manual.drop true | manual.drop | true     | PrioritySampling.USER_DROP",
    "manual.keep true | manual.keep | true     | PrioritySampling.USER_KEEP"
  })
  void settingForcedTracingViaTag(
      String tagName,
      boolean tagValue,
      @ConvertWith(PrioritySamplingConverter.class) int expectedPriority) {
    RateByServiceTraceSampler sampler = new RateByServiceTraceSampler();
    CoreTracer tracer = tracerBuilder().writer(new LoggingWriter()).sampler(sampler).build();
    try {
      DDSpan span = (DDSpan) tracer.buildSpan("datadog", "root").start();
      span.setTag(tagName, tagValue);
      span.finish();

      assertEquals(expectedPriority, span.getSamplingPriority());
    } finally {
      tracer.close();
    }
  }

  @Test
  void shouldCapReturnsFalseWhenRateDecreasesOrStaysSame() {
    assertFalse(RateByServiceTraceSampler.shouldCap(0.8, 0.4));
    assertFalse(RateByServiceTraceSampler.shouldCap(0.5, 0.5));
    assertFalse(RateByServiceTraceSampler.shouldCap(0.5, 1.0)); // 1.0 <= 0.5 * 2, no cap needed
  }

  @Test
  void shouldCapReturnsFalseWhenOldRateIsZero() {
    assertFalse(RateByServiceTraceSampler.shouldCap(0.0, 0.5));
    assertFalse(RateByServiceTraceSampler.shouldCap(0.0, 1.0));
  }

  @Test
  void shouldCapReturnsTrueWhenNewRateExceeds2xOldRate() {
    assertTrue(RateByServiceTraceSampler.shouldCap(0.1, 1.0));
    assertTrue(RateByServiceTraceSampler.shouldCap(0.2, 0.8));
    assertTrue(RateByServiceTraceSampler.shouldCap(0.1, 0.3));
  }

  @Test
  void cappedRateReturns2xOldRate() {
    assertEquals(0.2, RateByServiceTraceSampler.cappedRate(0.1), 1e-6);
    assertEquals(0.4, RateByServiceTraceSampler.cappedRate(0.2), 1e-6);
    assertEquals(0.8, RateByServiceTraceSampler.cappedRate(0.4), 1e-6);
  }

  @Test
  void rampUpCapsRateIncreasesAt2xPerInterval() {
    ControllableTimeSource time = new ControllableTimeSource();
    time.set(1_000_000_000L);
    RateByServiceTraceSampler serviceSampler = new RateByServiceTraceSampler(time);
    double tolerance = 0.01;

    // Set initial rate to 0.1
    serviceSampler.onResponse(
        "traces",
        rateResponse(new String[][] {{"service:foo,env:bar", "0.1"}, {"service:,env:", "0.1"}}));

    assertTrue(Math.abs(serviceSampler.sampleRateFor("bar", "foo") - 0.1) < tolerance);

    // agent restart sends rate 1.0, first interval
    time.advance(RateByServiceTraceSampler.RAMP_UP_INTERVAL_NANOS);
    Map<String, Map<String, Number>> highRateResponse =
        rateResponse(new String[][] {{"service:foo,env:bar", "1.0"}, {"service:,env:", "1.0"}});
    serviceSampler.onResponse("traces", highRateResponse);

    // rate is capped at 2x = 0.2
    assertTrue(Math.abs(serviceSampler.sampleRateFor("bar", "foo") - 0.2) < tolerance);
    assertTrue(Math.abs(serviceSampler.fallbackSampleRate() - 0.2) < tolerance);

    // second interval
    time.advance(RateByServiceTraceSampler.RAMP_UP_INTERVAL_NANOS);
    serviceSampler.onResponse("traces", highRateResponse);

    // rate doubles to 0.4
    assertTrue(Math.abs(serviceSampler.sampleRateFor("bar", "foo") - 0.4) < tolerance);
    assertTrue(Math.abs(serviceSampler.fallbackSampleRate() - 0.4) < tolerance);

    // third interval
    time.advance(RateByServiceTraceSampler.RAMP_UP_INTERVAL_NANOS);
    serviceSampler.onResponse("traces", highRateResponse);

    // rate doubles to 0.8
    assertTrue(Math.abs(serviceSampler.sampleRateFor("bar", "foo") - 0.8) < tolerance);
    assertTrue(Math.abs(serviceSampler.fallbackSampleRate() - 0.8) < tolerance);

    // fourth interval
    time.advance(RateByServiceTraceSampler.RAMP_UP_INTERVAL_NANOS);
    serviceSampler.onResponse("traces", highRateResponse);

    // rate reaches target 1.0 (2x=1.6 > 1.0)
    assertTrue(Math.abs(serviceSampler.sampleRateFor("bar", "foo") - 1.0) < tolerance);
    assertTrue(Math.abs(serviceSampler.fallbackSampleRate() - 1.0) < tolerance);
  }

  @Test
  void rampDownAppliesImmediately() {
    ControllableTimeSource time = new ControllableTimeSource();
    time.set(1_000_000_000L);
    RateByServiceTraceSampler serviceSampler = new RateByServiceTraceSampler(time);
    double tolerance = 0.01;

    // Set initial rate to 0.8
    serviceSampler.onResponse(
        "traces",
        rateResponse(new String[][] {{"service:foo,env:bar", "0.8"}, {"service:,env:", "0.8"}}));

    // rate decreases to 0.2
    serviceSampler.onResponse(
        "traces",
        rateResponse(new String[][] {{"service:foo,env:bar", "0.2"}, {"service:,env:", "0.2"}}));

    // decrease is applied immediately
    assertTrue(Math.abs(serviceSampler.sampleRateFor("bar", "foo") - 0.2) < tolerance);
    assertTrue(Math.abs(serviceSampler.fallbackSampleRate() - 0.2) < tolerance);
  }

  @Test
  void rateIncreaseBlockedDuringCooldown() {
    ControllableTimeSource time = new ControllableTimeSource();
    time.set(1_000_000_000L);
    RateByServiceTraceSampler serviceSampler = new RateByServiceTraceSampler(time);
    double tolerance = 0.01;
    Map<String, Map<String, Number>> highRateResponse = rateResponse("service:foo,env:bar", 1.0);

    // Set initial rate to 0.1
    serviceSampler.onResponse("traces", rateResponse("service:foo,env:bar", 0.1));

    // rate jumps, first capped increase
    time.advance(RateByServiceTraceSampler.RAMP_UP_INTERVAL_NANOS);
    serviceSampler.onResponse("traces", highRateResponse);

    // capped to 0.2
    assertTrue(Math.abs(serviceSampler.sampleRateFor("bar", "foo") - 0.2) < tolerance);

    // try again immediately (within cooldown)
    serviceSampler.onResponse("traces", highRateResponse);

    // rate stays at 0.2 because cooldown hasn't elapsed
    assertTrue(Math.abs(serviceSampler.sampleRateFor("bar", "foo") - 0.2) < tolerance);

    // after cooldown elapsed
    time.advance(RateByServiceTraceSampler.RAMP_UP_INTERVAL_NANOS);
    serviceSampler.onResponse("traces", highRateResponse);

    // rate doubles to 0.4
    assertTrue(Math.abs(serviceSampler.sampleRateFor("bar", "foo") - 0.4) < tolerance);
  }

  @Test
  void cooldownNotResetByBlockedIncrease() {
    ControllableTimeSource time = new ControllableTimeSource();
    time.set(1_000_000_000L);
    RateByServiceTraceSampler serviceSampler = new RateByServiceTraceSampler(time);
    double tolerance = 0.01;
    Map<String, Map<String, Number>> highRateResponse = rateResponse("service:foo,env:bar", 1.0);

    // Set initial low rate
    serviceSampler.onResponse("traces", rateResponse("service:foo,env:bar", 0.01));

    assertTrue(Math.abs(serviceSampler.sampleRateFor("bar", "foo") - 0.01) < tolerance);

    // wait for cooldown, apply increase: 0.01 -> 0.02
    time.advance(RateByServiceTraceSampler.RAMP_UP_INTERVAL_NANOS);
    serviceSampler.onResponse("traces", highRateResponse);

    // rate is capped at 2x = 0.02
    assertTrue(Math.abs(serviceSampler.sampleRateFor("bar", "foo") - 0.02) < tolerance);

    // before cooldown elapses, send another increase - rate should be held and lastCapped NOT reset
    time.advance(RateByServiceTraceSampler.RAMP_UP_INTERVAL_NANOS / 2);
    serviceSampler.onResponse("traces", highRateResponse);

    // rate stays at 0.02 (cooldown)
    assertTrue(Math.abs(serviceSampler.sampleRateFor("bar", "foo") - 0.02) < tolerance);

    // wait remaining half of cooldown from the original cap - should allow next ramp-up
    time.advance(RateByServiceTraceSampler.RAMP_UP_INTERVAL_NANOS / 2);
    serviceSampler.onResponse("traces", highRateResponse);

    // rate doubles to 0.04 because lastCapped was NOT reset by the blocked increase
    assertTrue(Math.abs(serviceSampler.sampleRateFor("bar", "foo") - 0.04) < tolerance);
  }

  @TableTest({
    "scenario          | tagName     | tagValue",
    "no tag            |             |         ",
    "manual.drop false | manual.drop | false   ",
    "manual.keep false | manual.keep | false   ",
    "manual.drop 1     | manual.drop | 1       ",
    "manual.keep 1     | manual.keep | 1       "
  })
  void notSettingForcedTracingViaTagOrSettingItWrongValueNotCausingException(
      String tagName, @ConvertWith(BoxedValueConverter.class) Object tagValue) {
    RateByServiceTraceSampler sampler = new RateByServiceTraceSampler();
    CoreTracer tracer = tracerBuilder().writer(new LoggingWriter()).sampler(sampler).build();
    try {
      DDSpan span = (DDSpan) tracer.buildSpan("datadog", "root").start();
      if (tagName != null) {
        span.setTag(tagName, tagValue);
      }

      assertNull(span.getSamplingPriority());

      span.finish();
    } finally {
      tracer.close();
    }
  }
}
