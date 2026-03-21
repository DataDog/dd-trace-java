package datadog.trace.common.sampling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.api.DDTags;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.time.ControllableTimeSource;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.common.writer.LoggingWriter;
import datadog.trace.core.DDSpan;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.test.DDCoreSpecification;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RateByServiceTraceSamplerTest extends DDCoreSpecification {

  static JsonAdapter<Map<String, Map<String, Number>>> serializer =
      new Moshi.Builder()
          .build()
          .adapter(
              Types.newParameterizedType(
                  Map.class,
                  String.class,
                  Types.newParameterizedType(Map.class, String.class, Double.class)));

  static Stream<Arguments> invalidRateArguments() {
    return Stream.of(
        Arguments.of("null", 1.0),
        Arguments.of("1", 1.0),
        Arguments.of("0", 0.0),
        Arguments.of("-5", 1.0),
        Arguments.of("5", 1.0),
        Arguments.of("0.5", 0.5));
  }

  @ParameterizedTest
  @MethodSource("invalidRateArguments")
  void invalidRate(String rateStr, double expectedRate) throws Exception {
    RateByServiceTraceSampler serviceSampler = new RateByServiceTraceSampler();
    String rateValue = "null".equals(rateStr) ? "null" : rateStr;
    String response = "{\"rate_by_service\": {\"service:,env:\":" + rateValue + "}}";
    serviceSampler.onResponse("traces", serializer.fromJson(response));

    assertEquals(
        expectedRate,
        serviceSampler
            .serviceRates
            .getSampler(RateByServiceTraceSampler.EnvAndService.FALLBACK)
            .getSampleRate(),
        0.001);
    assertEquals(
        expectedRate,
        serviceSampler.serviceRates.getSampler("not", "found").getSampleRate(),
        0.001);
  }

  static Stream<Arguments> rateSelectionArguments() {
    return Stream.of(
        Arguments.of("foo", "bar", 0.8),
        Arguments.of("Foo", "BAR", 0.8),
        Arguments.of("FOO", "BAR", 0.8),
        Arguments.of("not", "found", 0.2),
        Arguments.of("foo", "baz", 0.2),
        Arguments.of("fu", "bar", 0.2));
  }

  @ParameterizedTest
  @MethodSource("rateSelectionArguments")
  void rateSelection(String service, String env, double expectedRate) throws Exception {
    RateByServiceTraceSampler serviceSampler = new RateByServiceTraceSampler();
    String response =
        "{\"rate_by_service\": {\"service:foo,env:bar\":0.8, \"service:,env:\":0.20}}";
    serviceSampler.onResponse("traces", serializer.fromJson(response));

    double sampleRate = serviceSampler.serviceRates.getSampler(env, service).getSampleRate();
    assertTrue(sampleRate > expectedRate - 0.01);
    assertTrue(sampleRate < expectedRate + 0.01);
  }

  static Stream<Arguments> ratePartialAndFullCollisionsArguments() {
    return Stream.of(
        Arguments.of("foo", "bar", 0.8),
        Arguments.of("foo", "Bar", 0.8),
        Arguments.of("Foo", "BAR", 0.8),
        Arguments.of("FOO", "BAR", 0.8),
        Arguments.of("foo", "baz", 0.3),
        Arguments.of("FOO", "BAZ", 0.3),
        Arguments.of("quux", "baz", 0.4));
  }

  @ParameterizedTest
  @MethodSource("ratePartialAndFullCollisionsArguments")
  void ratePartialAndFullCollisions(String service, String env, double expectedRate)
      throws Exception {
    RateByServiceTraceSampler serviceSampler = new RateByServiceTraceSampler();
    String response =
        "{\"rate_by_service\": {\"service:foo,env:bar\":0.8, \"service:FOO,env:BAR\":0.2, "
            + "\"service:FOO,env:BAZ\": 0.3, \"service:quux,env:BAZ\": 0.4}}";
    serviceSampler.onResponse("traces", serializer.fromJson(response));

    double sampleRate = serviceSampler.serviceRates.getSampler(env, service).getSampleRate();
    assertTrue(sampleRate > expectedRate - 0.01);
    assertTrue(sampleRate < expectedRate + 0.01);
  }

  @Test
  void rateByServiceName() throws Exception {
    RateByServiceTraceSampler serviceSampler = new RateByServiceTraceSampler();
    datadog.trace.core.CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    String response = "{\"rate_by_service\": {\"service:spock,env:test\":0.0}}";
    serviceSampler.onResponse("traces", serializer.fromJson(response));
    DDSpan span1 =
        (DDSpan)
            tracer
                .buildSpan("fakeOperation")
                .withServiceName("foo")
                .withTag("env", "bar")
                .ignoreActiveSpan()
                .start();
    serviceSampler.setSamplingPriority(span1);

    assertEquals((int) PrioritySampling.SAMPLER_KEEP, span1.getSamplingPriority().intValue());
    assertTrue(serviceSampler.sample(span1));

    response =
        "{\"rate_by_service\": {\"service:spock,env:test\":1.0, \"service:SPOCK,env:Test\": 0.0}}";
    serviceSampler.onResponse("traces", serializer.fromJson(response));

    DDSpan span2 =
        (DDSpan)
            tracer
                .buildSpan("fakeOperation")
                .withServiceName("spock")
                .withTag("env", "test")
                .ignoreActiveSpan()
                .start();
    serviceSampler.setSamplingPriority(span2);

    assertEquals((int) PrioritySampling.SAMPLER_KEEP, span2.getSamplingPriority().intValue());
    assertTrue(serviceSampler.sample(span2));

    tracer.close();
  }

  @Test
  void rateByServiceNameCaseInsensitive() throws Exception {
    RateByServiceTraceSampler serviceSampler = new RateByServiceTraceSampler();
    datadog.trace.core.CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    String response = "{\"rate_by_service\": {\"service:spock,env:test\":1.0}}";
    serviceSampler.onResponse("traces", serializer.fromJson(response));

    DDSpan span =
        (DDSpan)
            tracer
                .buildSpan("fakeOperation")
                .withServiceName("SPOCK")
                .withTag("env", "Test")
                .ignoreActiveSpan()
                .start();
    serviceSampler.setSamplingPriority(span);

    assertEquals((int) PrioritySampling.SAMPLER_KEEP, span.getSamplingPriority().intValue());
    assertTrue(serviceSampler.sample(span));

    tracer.close();
  }

  @Test
  void samplingPrioritySetOnContext() throws Exception {
    RateByServiceTraceSampler serviceSampler = new RateByServiceTraceSampler();
    datadog.trace.core.CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    String response = "{\"rate_by_service\": {\"service:,env:\":1.0}}";
    serviceSampler.onResponse("traces", serializer.fromJson(response));

    DDSpan span =
        (DDSpan)
            tracer
                .buildSpan("fakeOperation")
                .withServiceName("spock")
                .withTag("env", "test")
                .ignoreActiveSpan()
                .start();
    serviceSampler.setSamplingPriority(span);

    assertEquals((int) PrioritySampling.SAMPLER_KEEP, span.getSamplingPriority().intValue());
    assertNull(span.getTag(DDSpanContext.SAMPLE_RATE_KEY));

    tracer.close();
  }

  @Test
  void samplingPrioritySetWhenServiceLater() throws Exception {
    RateByServiceTraceSampler sampler = new RateByServiceTraceSampler();
    ListWriter writer = new ListWriter();
    datadog.trace.core.CoreTracer tracer = tracerBuilder().writer(writer).sampler(sampler).build();

    sampler.onResponse(
        "test",
        serializer.fromJson(
            "{\"rate_by_service\":{\"service:,env:\":1.0,\"service:spock,env:\":0.0}}"));

    datadog.trace.bootstrap.instrumentation.api.AgentSpan span = tracer.buildSpan("test").start();
    assertNull(span.getSamplingPriority());

    span.setTag(DDTags.SERVICE_NAME, "spock");
    span.finish();
    writer.waitForTraces(1);
    assertEquals((int) PrioritySampling.SAMPLER_DROP, span.getSamplingPriority().intValue());

    span = tracer.buildSpan("test").withTag(DDTags.SERVICE_NAME, "spock").start();
    span.finish();
    writer.waitForTraces(2);
    assertEquals((int) PrioritySampling.SAMPLER_DROP, span.getSamplingPriority().intValue());

    tracer.close();
  }

  static Stream<Arguments> settingForcedTracingViaTagArguments() {
    return Stream.of(
        Arguments.of("manual.drop", true, (int) PrioritySampling.USER_DROP),
        Arguments.of("manual.keep", true, (int) PrioritySampling.USER_KEEP));
  }

  @ParameterizedTest
  @MethodSource("settingForcedTracingViaTagArguments")
  void settingForcedTracingViaTag(String tagName, Object tagValue, int expectedPriority)
      throws Exception {
    RateByServiceTraceSampler sampler = new RateByServiceTraceSampler();
    datadog.trace.core.CoreTracer tracer =
        tracerBuilder().writer(new LoggingWriter()).sampler(sampler).build();
    datadog.trace.bootstrap.instrumentation.api.AgentSpan span = tracer.buildSpan("root").start();
    span.setTag(tagName, tagValue);
    span.finish();

    assertEquals(expectedPriority, span.getSamplingPriority().intValue());
    tracer.close();
  }

  @Test
  void shouldCapReturnsFalseWhenRateDecreasesOrStaysSame() {
    assertFalse(RateByServiceTraceSampler.shouldCap(0.8, 0.4));
    assertFalse(RateByServiceTraceSampler.shouldCap(0.5, 0.5));
    assertFalse(RateByServiceTraceSampler.shouldCap(0.5, 1.0));
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
    assertEquals(0.2, RateByServiceTraceSampler.cappedRate(0.1), 0.001);
    assertEquals(0.4, RateByServiceTraceSampler.cappedRate(0.2), 0.001);
    assertEquals(0.8, RateByServiceTraceSampler.cappedRate(0.4), 0.001);
  }

  @Test
  void rampUpCapsRateIncreasesAt2xPerInterval() throws Exception {
    ControllableTimeSource time = new ControllableTimeSource();
    time.set(1_000_000_000L);
    RateByServiceTraceSampler serviceSampler = new RateByServiceTraceSampler(time);
    double tolerance = 0.01;

    String response = "{\"rate_by_service\": {\"service:foo,env:bar\":0.1, \"service:,env:\":0.1}}";
    serviceSampler.onResponse("traces", serializer.fromJson(response));

    assertEquals(
        0.1, serviceSampler.serviceRates.getSampler("bar", "foo").getSampleRate(), tolerance);

    time.advance(RateByServiceTraceSampler.RAMP_UP_INTERVAL_NANOS);
    response = "{\"rate_by_service\": {\"service:foo,env:bar\":1.0, \"service:,env:\":1.0}}";
    serviceSampler.onResponse("traces", serializer.fromJson(response));

    assertEquals(
        0.2, serviceSampler.serviceRates.getSampler("bar", "foo").getSampleRate(), tolerance);
    assertEquals(0.2, serviceSampler.serviceRates.getFallbackSampler().getSampleRate(), tolerance);

    time.advance(RateByServiceTraceSampler.RAMP_UP_INTERVAL_NANOS);
    serviceSampler.onResponse("traces", serializer.fromJson(response));

    assertEquals(
        0.4, serviceSampler.serviceRates.getSampler("bar", "foo").getSampleRate(), tolerance);
    assertEquals(0.4, serviceSampler.serviceRates.getFallbackSampler().getSampleRate(), tolerance);

    time.advance(RateByServiceTraceSampler.RAMP_UP_INTERVAL_NANOS);
    serviceSampler.onResponse("traces", serializer.fromJson(response));

    assertEquals(
        0.8, serviceSampler.serviceRates.getSampler("bar", "foo").getSampleRate(), tolerance);
    assertEquals(0.8, serviceSampler.serviceRates.getFallbackSampler().getSampleRate(), tolerance);

    time.advance(RateByServiceTraceSampler.RAMP_UP_INTERVAL_NANOS);
    serviceSampler.onResponse("traces", serializer.fromJson(response));

    assertEquals(
        1.0, serviceSampler.serviceRates.getSampler("bar", "foo").getSampleRate(), tolerance);
    assertEquals(1.0, serviceSampler.serviceRates.getFallbackSampler().getSampleRate(), tolerance);
  }

  @Test
  void rampDownAppliesImmediately() throws Exception {
    ControllableTimeSource time = new ControllableTimeSource();
    time.set(1_000_000_000L);
    RateByServiceTraceSampler serviceSampler = new RateByServiceTraceSampler(time);
    double tolerance = 0.01;

    String response = "{\"rate_by_service\": {\"service:foo,env:bar\":0.8, \"service:,env:\":0.8}}";
    serviceSampler.onResponse("traces", serializer.fromJson(response));

    response = "{\"rate_by_service\": {\"service:foo,env:bar\":0.2, \"service:,env:\":0.2}}";
    serviceSampler.onResponse("traces", serializer.fromJson(response));

    assertEquals(
        0.2, serviceSampler.serviceRates.getSampler("bar", "foo").getSampleRate(), tolerance);
    assertEquals(0.2, serviceSampler.serviceRates.getFallbackSampler().getSampleRate(), tolerance);
  }

  @Test
  void rateIncreaseBlockedDuringCooldown() throws Exception {
    ControllableTimeSource time = new ControllableTimeSource();
    time.set(1_000_000_000L);
    RateByServiceTraceSampler serviceSampler = new RateByServiceTraceSampler(time);
    double tolerance = 0.01;

    String response = "{\"rate_by_service\": {\"service:foo,env:bar\":0.1}}";
    serviceSampler.onResponse("traces", serializer.fromJson(response));

    time.advance(RateByServiceTraceSampler.RAMP_UP_INTERVAL_NANOS);
    response = "{\"rate_by_service\": {\"service:foo,env:bar\":1.0}}";
    serviceSampler.onResponse("traces", serializer.fromJson(response));

    assertEquals(
        0.2, serviceSampler.serviceRates.getSampler("bar", "foo").getSampleRate(), tolerance);

    serviceSampler.onResponse("traces", serializer.fromJson(response));
    assertEquals(
        0.2, serviceSampler.serviceRates.getSampler("bar", "foo").getSampleRate(), tolerance);

    time.advance(RateByServiceTraceSampler.RAMP_UP_INTERVAL_NANOS);
    serviceSampler.onResponse("traces", serializer.fromJson(response));
    assertEquals(
        0.4, serviceSampler.serviceRates.getSampler("bar", "foo").getSampleRate(), tolerance);
  }

  @Test
  void cooldownNotResetByBlockedIncrease() throws Exception {
    ControllableTimeSource time = new ControllableTimeSource();
    time.set(1_000_000_000L);
    RateByServiceTraceSampler serviceSampler = new RateByServiceTraceSampler(time);
    double tolerance = 0.01;

    String response = "{\"rate_by_service\": {\"service:foo,env:bar\":0.01}}";
    serviceSampler.onResponse("traces", serializer.fromJson(response));

    assertEquals(
        0.01, serviceSampler.serviceRates.getSampler("bar", "foo").getSampleRate(), tolerance);

    time.advance(RateByServiceTraceSampler.RAMP_UP_INTERVAL_NANOS);
    response = "{\"rate_by_service\": {\"service:foo,env:bar\":1.0}}";
    serviceSampler.onResponse("traces", serializer.fromJson(response));

    assertEquals(
        0.02, serviceSampler.serviceRates.getSampler("bar", "foo").getSampleRate(), tolerance);

    time.advance(RateByServiceTraceSampler.RAMP_UP_INTERVAL_NANOS / 2);
    serviceSampler.onResponse("traces", serializer.fromJson(response));

    assertEquals(
        0.02, serviceSampler.serviceRates.getSampler("bar", "foo").getSampleRate(), tolerance);

    time.advance(RateByServiceTraceSampler.RAMP_UP_INTERVAL_NANOS / 2);
    serviceSampler.onResponse("traces", serializer.fromJson(response));

    assertEquals(
        0.04, serviceSampler.serviceRates.getSampler("bar", "foo").getSampleRate(), tolerance);
  }

  static Stream<Arguments> notSettingForcedTracingViaTagArguments() {
    return Stream.of(
        Arguments.of(null, null),
        Arguments.of("manual.drop", false),
        Arguments.of("manual.keep", false),
        Arguments.of("manual.drop", 1),
        Arguments.of("manual.keep", 1));
  }

  @ParameterizedTest
  @MethodSource("notSettingForcedTracingViaTagArguments")
  void notSettingForcedTracingViaTagOrSettingItWrongValueNotCausingException(
      String tagName, Object tagValue) throws Exception {
    RateByServiceTraceSampler sampler = new RateByServiceTraceSampler();
    datadog.trace.core.CoreTracer tracer =
        tracerBuilder().writer(new LoggingWriter()).sampler(sampler).build();
    datadog.trace.bootstrap.instrumentation.api.AgentSpan span = tracer.buildSpan("root").start();
    if (tagName != null) {
      span.setTag(tagName, tagValue);
    }

    assertNull(span.getSamplingPriority());

    span.finish();
    tracer.close();
  }
}
