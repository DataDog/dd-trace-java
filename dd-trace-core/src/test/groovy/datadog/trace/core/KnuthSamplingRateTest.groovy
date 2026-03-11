package datadog.trace.core

import datadog.trace.common.sampling.PrioritySampler
import datadog.trace.common.sampling.RateByServiceTraceSampler
import datadog.trace.common.sampling.Sampler
import datadog.trace.common.writer.ListWriter
import datadog.trace.common.writer.ddagent.DDAgentApi
import datadog.trace.core.test.DDCoreSpecification

import static datadog.trace.api.config.TracerConfig.TRACE_RATE_LIMIT
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLE_RATE
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_RULES
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_SERVICE_RULES
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP

class KnuthSamplingRateTest extends DDCoreSpecification {
  static serializer = DDAgentApi.RESPONSE_ADAPTER

  def "formatKnuthSamplingRate produces correct format"() {
    expect:
    DDSpan.formatKnuthSamplingRate(rate) == expected

    where:
    rate       | expected
    1.0d       | "1"
    0.5d       | "0.5"
    0.1d       | "0.1"
    0.0d       | "0"
    0.765432d  | "0.765432"
    0.7654321d | "0.765432"
    0.123456d  | "0.123456"
    0.100000d  | "0.1"
    0.250d     | "0.25"
  }

  def "agent rate sampler sets ksr propagated tag"() {
    setup:
    def serviceSampler = new RateByServiceTraceSampler()
    def tracer = tracerBuilder().writer(new ListWriter()).build()
    String response = '{"rate_by_service": {"service:,env:":' + rate + '}}'
    serviceSampler.onResponse("traces", serializer.fromJson(response))

    when:
    DDSpan span = tracer.buildSpan("fakeOperation")
      .withServiceName("spock")
      .withTag("env", "test")
      .ignoreActiveSpan().start()
    serviceSampler.setSamplingPriority(span)

    def propagationMap = span.context.propagationTags.createTagMap()
    def ksr = propagationMap.get('_dd.p.ksr')

    then:
    ksr == expectedKsr

    cleanup:
    tracer.close()

    where:
    rate | expectedKsr
    1.0  | "1"
    0.5  | "0.5"
    0.0  | "0"
  }

  def "rule-based sampler sets ksr propagated tag when rule matches"() {
    setup:
    Properties properties = new Properties()
    properties.setProperty(TRACE_SAMPLING_RULES, jsonRules)
    properties.setProperty(TRACE_RATE_LIMIT, "50")
    def tracer = tracerBuilder().writer(new ListWriter()).build()

    when:
    Sampler sampler = Sampler.Builder.forConfig(properties)
    DDSpan span = tracer.buildSpan("operation")
      .withServiceName("service")
      .withTag("env", "bar")
      .ignoreActiveSpan().start()
    ((PrioritySampler) sampler).setSamplingPriority(span)

    def propagationMap = span.context.propagationTags.createTagMap()
    def ksr = propagationMap.get('_dd.p.ksr')

    then:
    ksr == expectedKsr

    cleanup:
    tracer.close()

    where:
    jsonRules                                                          | expectedKsr
    // Matching rule with rate 1 -> ksr is "1"
    '[{"service": "service", "sample_rate": 1}]'                       | "1"
    // Matching rule with rate 0.5 -> ksr is "0.5"
    '[{"service": "service", "sample_rate": 0.5}]'                     | "0.5"
    // Matching rule with rate 0 -> ksr is "0" (drop, but ksr still set)
    '[{"service": "service", "sample_rate": 0}]'                       | "0"
  }

  def "rule-based sampler fallback to agent sampler sets ksr"() {
    setup:
    Properties properties = new Properties()
    // Rule that does NOT match "service"
    properties.setProperty(TRACE_SAMPLING_RULES, '[{"service": "nomatch", "sample_rate": 0.5}]')
    properties.setProperty(TRACE_RATE_LIMIT, "50")
    def tracer = tracerBuilder().writer(new ListWriter()).build()

    when:
    Sampler sampler = Sampler.Builder.forConfig(properties)
    DDSpan span = tracer.buildSpan("operation")
      .withServiceName("service")
      .withTag("env", "bar")
      .ignoreActiveSpan().start()
    ((PrioritySampler) sampler).setSamplingPriority(span)

    def propagationMap = span.context.propagationTags.createTagMap()
    def ksr = propagationMap.get('_dd.p.ksr')

    then:
    // When falling back to agent sampler, ksr should still be set (agent rate = 1.0 by default)
    ksr == "1"
    span.getSamplingPriority() == SAMPLER_KEEP

    cleanup:
    tracer.close()
  }

  def "service rule sampler sets ksr propagated tag"() {
    setup:
    Properties properties = new Properties()
    properties.setProperty(TRACE_SAMPLING_SERVICE_RULES, "service:0.75")
    properties.setProperty(TRACE_RATE_LIMIT, "50")
    def tracer = tracerBuilder().writer(new ListWriter()).build()

    when:
    Sampler sampler = Sampler.Builder.forConfig(properties)
    DDSpan span = tracer.buildSpan("operation")
      .withServiceName("service")
      .withTag("env", "bar")
      .ignoreActiveSpan().start()
    ((PrioritySampler) sampler).setSamplingPriority(span)

    def propagationMap = span.context.propagationTags.createTagMap()
    def ksr = propagationMap.get('_dd.p.ksr')

    then:
    ksr == "0.75"

    cleanup:
    tracer.close()
  }

  def "default rate sampler sets ksr propagated tag"() {
    setup:
    Properties properties = new Properties()
    properties.setProperty(TRACE_SAMPLE_RATE, "0.25")
    properties.setProperty(TRACE_RATE_LIMIT, "50")
    def tracer = tracerBuilder().writer(new ListWriter()).build()

    when:
    Sampler sampler = Sampler.Builder.forConfig(properties)
    DDSpan span = tracer.buildSpan("operation")
      .withServiceName("service")
      .withTag("env", "bar")
      .ignoreActiveSpan().start()
    ((PrioritySampler) sampler).setSamplingPriority(span)

    def propagationMap = span.context.propagationTags.createTagMap()
    def ksr = propagationMap.get('_dd.p.ksr')

    then:
    ksr == "0.25"

    cleanup:
    tracer.close()
  }

  def "ksr is propagated via x-datadog-tags header"() {
    setup:
    def serviceSampler = new RateByServiceTraceSampler()
    def tracer = tracerBuilder().writer(new ListWriter()).build()
    String response = '{"rate_by_service": {"service:,env:":0.5}}'
    serviceSampler.onResponse("traces", serializer.fromJson(response))

    when:
    DDSpan span = tracer.buildSpan("fakeOperation")
      .withServiceName("spock")
      .withTag("env", "test")
      .ignoreActiveSpan().start()
    serviceSampler.setSamplingPriority(span)

    def headerValue = span.context.propagationTags.headerValue(
      datadog.trace.core.propagation.PropagationTags.HeaderType.DATADOG)

    then:
    headerValue != null
    headerValue.contains("_dd.p.ksr=0.5")

    cleanup:
    tracer.close()
  }
}
