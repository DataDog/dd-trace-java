package datadog.trace.common.sampling

import datadog.trace.api.DDTags
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.common.writer.ListWriter
import datadog.trace.common.writer.LoggingWriter
import datadog.trace.common.writer.ddagent.DDAgentApi
import datadog.trace.core.DDSpan
import datadog.trace.core.DDSpanContext
import datadog.trace.core.test.DDCoreSpecification

class RateByServiceTraceSamplerTest extends DDCoreSpecification {
  static serializer = DDAgentApi.RESPONSE_ADAPTER

  def "invalid rate -> 1"() {
    setup:
    RateByServiceTraceSampler serviceSampler = new RateByServiceTraceSampler()
    String response = '{"rate_by_service": {"service:,env:":' + rate + '}}'
    serviceSampler.onResponse("traces", serializer.fromJson(response))

    expect:
    serviceSampler.serviceRates.getSampler(RateByServiceTraceSampler.EnvAndService.FALLBACK).sampleRate == expectedRate
    serviceSampler.serviceRates.getSampler("not", "found").sampleRate == expectedRate

    where:
    // these values are all precisely represented in floating point
    rate | expectedRate
    null | 1
    1    | 1
    0    | 0.0
    -5   | 1
    5    | 1
    0.5  | 0.5
  }

  def "rate selection"() {
    setup:
    RateByServiceTraceSampler serviceSampler = new RateByServiceTraceSampler()
    String response = '{"rate_by_service": {"service:foo,env:bar":0.8, "service:,env:":0.20}}'
    serviceSampler.onResponse("traces", serializer.fromJson(response))

    when:
    def sampler = serviceSampler.serviceRates.getSampler(env, service)

    then:
    sampler.sampleRate > expectedRate - 0.01
    sampler.sampleRate < expectedRate + 0.01

    where:
    service | env     | expectedRate
    "foo"   | "bar"   | 0.8
    "Foo"   | "BAR"   | 0.8
    "FOO"   | "BAR"   | 0.8
    "not"   | "found" | 0.2
    "foo"   | "baz"   | 0.2
    "fu"    | "bar"   | 0.2
  }

  def "rate partial & full collisions"() {
    // case insensitive equivalence -- undefined behavior, first one wins
    setup:
    RateByServiceTraceSampler serviceSampler = new RateByServiceTraceSampler()
    String response = '{"rate_by_service": {"service:foo,env:bar":0.8, "service:FOO,env:BAR":0.2, "service:FOO,env:BAZ": 0.3, "service:quux,env:BAZ": 0.4}}'
    serviceSampler.onResponse("traces", serializer.fromJson(response))

    when:
    def sampler = serviceSampler.serviceRates.getSampler(env, service)

    then:
    sampler.sampleRate > expectedRate - 0.01
    sampler.sampleRate < expectedRate + 0.01

    where:
    service | env     | expectedRate
    "foo"   | "bar"   | 0.8
    "foo"   | "Bar"   | 0.8
    "Foo"   | "BAR"   | 0.8
    "FOO"   | "BAR"   | 0.8
    "foo"   | "baz"   | 0.3
    "FOO"   | "BAZ"   | 0.3
    "quux"  | "baz"   | 0.4
  }

  def "rate by service name"() {
    setup:
    RateByServiceTraceSampler serviceSampler = new RateByServiceTraceSampler()
    def tracer = tracerBuilder().writer(new ListWriter()).build()

    when:
    String response = '{"rate_by_service": {"service:spock,env:test":0.0}}'
    serviceSampler.onResponse("traces", serializer.fromJson(response))
    DDSpan span1 = tracer.buildSpan("fakeOperation")
      .withServiceName("foo")
      .withTag("env", "bar")
      .ignoreActiveSpan().start()

    serviceSampler.setSamplingPriority(span1)

    then:
    span1.getSamplingPriority() == PrioritySampling.SAMPLER_KEEP
    serviceSampler.sample(span1)

    when:
    // case-insensitive equivalence - undefined in spec, but implemented as first one wins
    response = '{"rate_by_service": {"service:spock,env:test":1.0, "service:SPOCK,env:Test": 0.0}}'
    serviceSampler.onResponse("traces", serializer.fromJson(response))

    DDSpan span2 = tracer.buildSpan("fakeOperation")
      .withServiceName("spock")
      .withTag("env", "test")
      .ignoreActiveSpan().start()
    serviceSampler.setSamplingPriority(span2)

    then:
    span2.getSamplingPriority() == PrioritySampling.SAMPLER_KEEP
    serviceSampler.sample(span2)

    cleanup:
    tracer.close()
  }

  def "rate by service name - case-insensitive"() {
    setup:
    RateByServiceTraceSampler serviceSampler = new RateByServiceTraceSampler()
    def tracer = tracerBuilder().writer(new ListWriter()).build()

    when:
    def response = '{"rate_by_service": {"service:spock,env:test":1.0}}'
    serviceSampler.onResponse("traces", serializer.fromJson(response))

    DDSpan span = tracer.buildSpan("fakeOperation")
      .withServiceName("SPOCK")
      .withTag("env", "Test")
      .ignoreActiveSpan().start()
    serviceSampler.setSamplingPriority(span)

    then:
    span.getSamplingPriority() == PrioritySampling.SAMPLER_KEEP
    serviceSampler.sample(span)

    cleanup:
    tracer.close()
  }

  def "sampling priority set on context"() {
    setup:
    RateByServiceTraceSampler serviceSampler = new RateByServiceTraceSampler()
    def tracer = tracerBuilder().writer(new ListWriter()).build()
    String response = '{"rate_by_service": {"service:,env:":1.0}}'
    serviceSampler.onResponse("traces", serializer.fromJson(response))

    when:
    DDSpan span = tracer.buildSpan("fakeOperation")
      .withServiceName("spock")
      .withTag("env", "test")
      .ignoreActiveSpan().start()
    serviceSampler.setSamplingPriority(span)

    then:
    // sets correctly on root span
    span.getSamplingPriority() == PrioritySampling.SAMPLER_KEEP
    // RateByServiceSampler must not set the sample rate
    span.getTag(DDSpanContext.SAMPLE_RATE_KEY) == null

    cleanup:
    tracer.close()
  }

  def "sampling priority set when service later"() {
    def sampler = new RateByServiceTraceSampler()
    def writer = new ListWriter()
    def tracer = tracerBuilder().writer(writer).sampler(sampler).build()

    sampler.onResponse("test", serializer
      .fromJson('{"rate_by_service":{"service:,env:":1.0,"service:spock,env:":0.0}}'))

    when:
    def span = tracer.buildSpan("test").start()

    then:
    span.getSamplingPriority() == null

    when:
    span.setTag(DDTags.SERVICE_NAME, "spock")

    then:
    span.finish()
    writer.waitForTraces(1)
    span.getSamplingPriority() == PrioritySampling.SAMPLER_DROP

    when:
    span = tracer.buildSpan("test").withTag(DDTags.SERVICE_NAME, "spock").start()
    span.finish()
    writer.waitForTraces(2)

    then:
    span.getSamplingPriority() == PrioritySampling.SAMPLER_DROP

    cleanup:
    tracer.close()
  }

  def "setting forced tracing via tag"() {
    when:
    def sampler = new RateByServiceTraceSampler()
    def tracer = tracerBuilder().writer(new LoggingWriter()).sampler(sampler).build()
    def span = tracer.buildSpan("root").start()
    if (tagName) {
      span.setTag(tagName, tagValue)
    }
    span.finish()

    then:
    span.getSamplingPriority() == expectedPriority

    cleanup:
    tracer.close()

    where:
    tagName       | tagValue | expectedPriority
    'manual.drop' | true     | PrioritySampling.USER_DROP
    'manual.keep' | true     | PrioritySampling.USER_KEEP
  }

  def "shouldCap returns false when rate decreases or stays same"() {
    expect:
    !RateByServiceTraceSampler.shouldCap(0.8, 0.4)
    !RateByServiceTraceSampler.shouldCap(0.5, 0.5)
    !RateByServiceTraceSampler.shouldCap(0.5, 1.0) // 1.0 <= 0.5 * 2, no cap needed
  }

  def "shouldCap returns false when old rate is zero"() {
    expect:
    !RateByServiceTraceSampler.shouldCap(0.0, 0.5)
    !RateByServiceTraceSampler.shouldCap(0.0, 1.0)
  }

  def "shouldCap returns true when new rate exceeds 2x old rate"() {
    expect:
    RateByServiceTraceSampler.shouldCap(0.1, 1.0)
    RateByServiceTraceSampler.shouldCap(0.2, 0.8)
    RateByServiceTraceSampler.shouldCap(0.1, 0.3)
  }

  def "cappedRate returns 2x old rate"() {
    expect:
    RateByServiceTraceSampler.cappedRate(0.1) == 0.2
    RateByServiceTraceSampler.cappedRate(0.2) == 0.4
    RateByServiceTraceSampler.cappedRate(0.4) == 0.8
  }

  def "ramp-up caps rate increases at 2x per interval"() {
    setup:
    RateByServiceTraceSampler serviceSampler = new RateByServiceTraceSampler()
    long currentTime = 1_000_000_000L
    serviceSampler.nanoTimeSupplier = { -> currentTime }
    def tolerance = 0.01

    // Set initial rate to 0.1
    String response = '{"rate_by_service": {"service:foo,env:bar":0.1, "service:,env:":0.1}}'
    serviceSampler.onResponse("traces", serializer.fromJson(response))

    expect:
    Math.abs(serviceSampler.serviceRates.getSampler("bar", "foo").sampleRate - 0.1) < tolerance

    when: "agent restart sends rate 1.0, first interval"
    currentTime += RateByServiceTraceSampler.RAMP_UP_INTERVAL_NANOS
    response = '{"rate_by_service": {"service:foo,env:bar":1.0, "service:,env:":1.0}}'
    serviceSampler.onResponse("traces", serializer.fromJson(response))

    then: "rate is capped at 2x = 0.2"
    Math.abs(serviceSampler.serviceRates.getSampler("bar", "foo").sampleRate - 0.2) < tolerance
    Math.abs(serviceSampler.serviceRates.getFallbackSampler().sampleRate - 0.2) < tolerance

    when: "second interval"
    currentTime += RateByServiceTraceSampler.RAMP_UP_INTERVAL_NANOS
    serviceSampler.onResponse("traces", serializer.fromJson(response))

    then: "rate doubles to 0.4"
    Math.abs(serviceSampler.serviceRates.getSampler("bar", "foo").sampleRate - 0.4) < tolerance
    Math.abs(serviceSampler.serviceRates.getFallbackSampler().sampleRate - 0.4) < tolerance

    when: "third interval"
    currentTime += RateByServiceTraceSampler.RAMP_UP_INTERVAL_NANOS
    serviceSampler.onResponse("traces", serializer.fromJson(response))

    then: "rate doubles to 0.8"
    Math.abs(serviceSampler.serviceRates.getSampler("bar", "foo").sampleRate - 0.8) < tolerance
    Math.abs(serviceSampler.serviceRates.getFallbackSampler().sampleRate - 0.8) < tolerance

    when: "fourth interval"
    currentTime += RateByServiceTraceSampler.RAMP_UP_INTERVAL_NANOS
    serviceSampler.onResponse("traces", serializer.fromJson(response))

    then: "rate reaches target 1.0 (2x=1.6 > 1.0)"
    Math.abs(serviceSampler.serviceRates.getSampler("bar", "foo").sampleRate - 1.0) < tolerance
    Math.abs(serviceSampler.serviceRates.getFallbackSampler().sampleRate - 1.0) < tolerance
  }

  def "ramp-down applies immediately"() {
    setup:
    RateByServiceTraceSampler serviceSampler = new RateByServiceTraceSampler()
    long currentTime = 1_000_000_000L
    serviceSampler.nanoTimeSupplier = { -> currentTime }
    def tolerance = 0.01

    // Set initial rate to 0.8
    String response = '{"rate_by_service": {"service:foo,env:bar":0.8, "service:,env:":0.8}}'
    serviceSampler.onResponse("traces", serializer.fromJson(response))

    when: "rate decreases to 0.2"
    response = '{"rate_by_service": {"service:foo,env:bar":0.2, "service:,env:":0.2}}'
    serviceSampler.onResponse("traces", serializer.fromJson(response))

    then: "decrease is applied immediately"
    Math.abs(serviceSampler.serviceRates.getSampler("bar", "foo").sampleRate - 0.2) < tolerance
    Math.abs(serviceSampler.serviceRates.getFallbackSampler().sampleRate - 0.2) < tolerance
  }

  def "rate increase blocked during cooldown"() {
    setup:
    RateByServiceTraceSampler serviceSampler = new RateByServiceTraceSampler()
    long currentTime = 1_000_000_000L
    serviceSampler.nanoTimeSupplier = { -> currentTime }
    def tolerance = 0.01

    // Set initial rate to 0.1
    String response = '{"rate_by_service": {"service:foo,env:bar":0.1}}'
    serviceSampler.onResponse("traces", serializer.fromJson(response))

    when: "rate jumps, first capped increase"
    currentTime += RateByServiceTraceSampler.RAMP_UP_INTERVAL_NANOS
    response = '{"rate_by_service": {"service:foo,env:bar":1.0}}'
    serviceSampler.onResponse("traces", serializer.fromJson(response))

    then: "capped to 0.2"
    Math.abs(serviceSampler.serviceRates.getSampler("bar", "foo").sampleRate - 0.2) < tolerance

    when: "try again immediately (within cooldown)"
    serviceSampler.onResponse("traces", serializer.fromJson(response))

    then: "rate stays at 0.2 because cooldown hasn't elapsed"
    Math.abs(serviceSampler.serviceRates.getSampler("bar", "foo").sampleRate - 0.2) < tolerance

    when: "after cooldown elapsed"
    currentTime += RateByServiceTraceSampler.RAMP_UP_INTERVAL_NANOS
    serviceSampler.onResponse("traces", serializer.fromJson(response))

    then: "rate doubles to 0.4"
    Math.abs(serviceSampler.serviceRates.getSampler("bar", "foo").sampleRate - 0.4) < tolerance
  }

  def "cooldown not reset by blocked increase"() {
    setup:
    RateByServiceTraceSampler serviceSampler = new RateByServiceTraceSampler()
    long currentTime = 1_000_000_000L
    serviceSampler.nanoTimeSupplier = { -> currentTime }
    def tolerance = 0.01

    // Set initial low rate
    String response = '{"rate_by_service": {"service:foo,env:bar":0.01}}'
    serviceSampler.onResponse("traces", serializer.fromJson(response))

    expect:
    Math.abs(serviceSampler.serviceRates.getSampler("bar", "foo").sampleRate - 0.01) < tolerance

    when: "wait for cooldown, apply increase: 0.01 -> 0.02"
    currentTime += RateByServiceTraceSampler.RAMP_UP_INTERVAL_NANOS
    response = '{"rate_by_service": {"service:foo,env:bar":1.0}}'
    serviceSampler.onResponse("traces", serializer.fromJson(response))

    then: "rate is capped at 2x = 0.02"
    Math.abs(serviceSampler.serviceRates.getSampler("bar", "foo").sampleRate - 0.02) < tolerance

    when: "before cooldown elapses, send another increase - rate should be held and lastCapped NOT reset"
    currentTime += RateByServiceTraceSampler.RAMP_UP_INTERVAL_NANOS / 2
    serviceSampler.onResponse("traces", serializer.fromJson(response))

    then: "rate stays at 0.02 (cooldown)"
    Math.abs(serviceSampler.serviceRates.getSampler("bar", "foo").sampleRate - 0.02) < tolerance

    when: "wait remaining half of cooldown from the original cap - should allow next ramp-up"
    currentTime += RateByServiceTraceSampler.RAMP_UP_INTERVAL_NANOS / 2
    serviceSampler.onResponse("traces", serializer.fromJson(response))

    then: "rate doubles to 0.04 because lastCapped was NOT reset by the blocked increase"
    Math.abs(serviceSampler.serviceRates.getSampler("bar", "foo").sampleRate - 0.04) < tolerance
  }

  def "not setting forced tracing via tag or setting it wrong value not causing exception"() {
    setup:
    def sampler = new RateByServiceTraceSampler()
    def tracer = tracerBuilder().writer(new LoggingWriter()).sampler(sampler).build()
    def span = tracer.buildSpan("root").start()
    if (tagName) {
      span.setTag(tagName, tagValue)
    }

    expect:
    span.getSamplingPriority() == null

    cleanup:
    span.finish()
    tracer.close()

    where:
    tagName       | tagValue
    // When no tag is set default to
    null          | null
    // Setting to not known value
    'manual.drop' | false
    'manual.keep' | false
    'manual.drop' | 1
    'manual.keep' | 1
  }
}
