package datadog.trace.common.sampling

import datadog.trace.api.Config
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.DDSpan
import datadog.trace.core.test.DDCoreSpecification

import static datadog.trace.api.config.TracerConfig.SPAN_SAMPLING_RULES
import static datadog.trace.api.config.TracerConfig.SPAN_SAMPLING_RULES_FILE
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLE_RATE
import static datadog.trace.api.sampling.SamplingMechanism.DEFAULT
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP
import static datadog.trace.api.sampling.SamplingMechanism.SPAN_SAMPLING_RATE

class SingleSpanSamplerTest extends DDCoreSpecification {

  def "Single Span Sampler is not created when no rules provided"() {
    given:
    Properties properties = new Properties()
    if (rules != null) {
      properties.setProperty(SPAN_SAMPLING_RULES, rules)
    }

    when:
    SingleSpanSampler sampler = SingleSpanSampler.Builder.forConfig(Config.get(properties))

    then:
    sampler == null

    where:
    rules << [
      // no rules provided
      null,
      "[]",
      // invalid sample_rate must be between 0.0 and 1.0
      """[ { "service": "*", "name": "*", "sample_rate": 10.0 } ]""",
      """[ { "service": "*", "name": "*", "sample_rate": "all" } ]""",
      // invalid max_per_second value
      """[ { "service": "*", "name": "*", "sample_rate": 1.0, "max_per_second": "N/A" } ]"""
    ]
  }

  def "Single Span Sampler set sampling priority"() {
    given:
    Properties properties = new Properties()
    if (rules != null) {
      properties.setProperty(SPAN_SAMPLING_RULES, rules)
    }
    def tracer = tracerBuilder().writer(new ListWriter()).build()

    when:
    SingleSpanSampler sampler = SingleSpanSampler.Builder.forConfig(Config.get(properties))

    DDSpan span = tracer.buildSpan("operation")
      .withServiceName("service")
      .withTag("env", "bar")
      .ignoreActiveSpan().start() as DDSpan

    then:
    sampler.setSamplingPriority(span) == isFirstSampled

    span.getTag("_dd.span_sampling.mechanism") == expectedMechanism
    span.getTag("_dd.span_sampling.rule_rate") == expectedRate
    span.getTag("_dd.span_sampling.max_per_second") == expectedLimit

    where:
    rules                                                                                             | isFirstSampled | expectedMechanism  | expectedRate | expectedLimit
    """[ { "service": "*", "name": "*", "sample_rate": 1.0 } ]"""                                     | true           | SPAN_SAMPLING_RATE | 1.0          | null
    """[ { "service": "*", "name": "*", "sample_rate": 1.0, "max_per_second": 10 } ]"""               | true           | SPAN_SAMPLING_RATE | 1.0          | 10
    """[ { "service": "ser*", "name": "oper*", "sample_rate": 1.0, "max_per_second": 15 } ]"""        | true           | SPAN_SAMPLING_RATE | 1.0          | 15
    """[ { "service": "?ervice", "name": "operati?n", "sample_rate": 1.0, "max_per_second": 10 } ]""" | true           | SPAN_SAMPLING_RATE | 1.0          | 10
    """[ { "service": "service", "name": "operation", "sample_rate": 1.0, "max_per_second": 5 } ]"""  | true           | SPAN_SAMPLING_RATE | 1.0          | 5
    """[ { "service": "service-b", "name": "*", "sample_rate": 1.0, "max_per_second": 10 } ]"""       | false          | null               | null         | null
    """[ { "service": "*", "name": "*", "sample_rate": 0.0 } ]"""                                     | false          | null               | null         | null
    """[ { "service": "*", "name": "operation-b", "sample_rate": 0.5 } ]"""                           | false          | null               | null         | null
  }

  def "Parent/child scenarios when the trace is dropped but individual spans are kept by the single span sampler"() {
    given:
    Properties properties = new Properties()
    if (rules != null) {
      properties.setProperty(SPAN_SAMPLING_RULES, rules)
      properties.setProperty(TRACE_SAMPLE_RATE, "0")
    }
    def tracer = tracerBuilder().writer(new ListWriter()).build()

    when:
    SingleSpanSampler sampler = SingleSpanSampler.Builder.forConfig(Config.get(properties))

    DDSpan rootSpan = tracer.buildSpan("web.request")
      .withServiceName("webserver")
      .ignoreActiveSpan().start() as DDSpan

    DDSpan childSpan = tracer.buildSpan("web.handler")
      .withServiceName("webserver")
      .asChildOf(rootSpan)
      .ignoreActiveSpan().start() as DDSpan

    then:
    // set trace sampling priority to drop the trace
    rootSpan.setSamplingPriority(SAMPLER_DROP, DEFAULT)

    // set spans sampling priority
    sampler.setSamplingPriority(rootSpan) == sampleRoot
    sampler.setSamplingPriority(childSpan) == sampleChild

    expect:
    rootSpan.getTag("_dd.span_sampling.mechanism") == rootMechanism
    childSpan.getTag("_dd.span_sampling.mechanism") == childMechanism

    where:
    rules                                                   | sampleRoot | sampleChild | rootMechanism      | childMechanism
    """[{"service": "webserver", "name": "web.request"}]""" | true       | false       | SPAN_SAMPLING_RATE | null
    """[{"service": "webserver", "name": "web.handler"}]""" | false      | true        | null               | SPAN_SAMPLING_RATE
    """[{"service": "webserver", "name": "web.*"}]"""       | true       | true        | SPAN_SAMPLING_RATE | SPAN_SAMPLING_RATE
    """[{"service": "other-server"}]"""                     | false      | false       | null               | null
  }

  def "Single Span Sampler set sampling priority with the max-per-second limit"() {
    given:
    Properties properties = new Properties()
    if (rules != null) {
      properties.setProperty(SPAN_SAMPLING_RULES, rules)
    }
    def tracer = tracerBuilder().writer(new ListWriter()).build()

    when:
    SingleSpanSampler sampler = SingleSpanSampler.Builder.forConfig(Config.get(properties))

    DDSpan span1 = tracer.buildSpan("operation")
      .withServiceName("service")
      .withTag("env", "bar")
      .ignoreActiveSpan().start() as DDSpan

    DDSpan span2 = tracer.buildSpan("operation")
      .withServiceName("service")
      .withTag("env", "bar")
      .ignoreActiveSpan().start() as DDSpan

    then:
    sampler.setSamplingPriority(span1) == isFirstSampled
    sampler.setSamplingPriority(span2) == isSecondSampled

    where:
    rules                                                                                            | isFirstSampled | isSecondSampled
    """[ { "service": "*", "name": "*", "sample_rate": 1.0, "max_per_second": 1 } ]"""               | true           | false
    """[ { "service": "ser*", "name": "oper*", "sample_rate": 1.0, "max_per_second": 1 } ]"""        | true           | false
    """[ { "service": "?ervice", "name": "operati?n", "sample_rate": 1.0, "max_per_second": 1 } ]""" | true           | false
    """[ { "service": "service", "name": "operation", "sample_rate": 1.0, "max_per_second": 1 } ]""" | true           | false
    """[ { "service": "service", "max_per_second": 1 } ]"""                                          | true           | false
    """[ { "name": "operation", "sample_rate": 1.0, "max_per_second": 1 } ]"""                       | true           | false

    """[ { "service": "*", "name": "*", "sample_rate": 1.0, "max_per_second": 2 } ]"""               | true           | true
    """[ { "service": "ser*", "name": "oper*", "max_per_second": 2 } ]"""                            | true           | true
    """[ { "service": "?ervice", "name": "operati?n", "sample_rate": 1.0, "max_per_second": 2 } ]""" | true           | true
  }

  def "Load rules from file"() {
    given:
    Properties properties = new Properties()
    if (rules != null) {
      def rulesFile = SpanSamplingRulesFileTest.createRulesFile(rules)
      properties.setProperty(SPAN_SAMPLING_RULES_FILE, rulesFile)
    }
    def tracer = tracerBuilder().writer(new ListWriter()).build()

    when:
    SingleSpanSampler sampler = SingleSpanSampler.Builder.forConfig(Config.get(properties))

    DDSpan span1 = tracer.buildSpan("operation")
      .withServiceName("service")
      .withTag("env", "bar")
      .ignoreActiveSpan().start() as DDSpan

    then:
    sampler.setSamplingPriority(span1)

    where:
    rules << ["""[ { "service": "*", "name": "op?ration*", "sample_rate": 1.0, "max_per_second": 1 } ]"""]
  }

  def "Prefer rules in env var over rules from file"() {
    given:
    Properties properties = new Properties()
    properties.setProperty(SPAN_SAMPLING_RULES, envVarRules)
    properties.setProperty(SPAN_SAMPLING_RULES_FILE, SpanSamplingRulesFileTest.createRulesFile(fileRules))
    def tracer = tracerBuilder().writer(new ListWriter()).build()

    when:
    SingleSpanSampler sampler = SingleSpanSampler.Builder.forConfig(Config.get(properties))

    DDSpan span1 = tracer.buildSpan("operation")
      .withServiceName("service")
      .withTag("env", "bar")
      .ignoreActiveSpan().start() as DDSpan

    then:
    sampler.setSamplingPriority(span1) == matched

    where:
    envVarRules                    | fileRules                      | matched
    """[ { "sample_rate": 0 } ]""" | """[ { "sample_rate": 1 } ]""" | false
    """[ { "sample_rate": 1 } ]""" | """[ { "sample_rate": 0 } ]""" | true
  }

  def "Throw NPE when passed list of rules is null"() {
    when:
    new SingleSpanSampler.RuleBasedSingleSpanSampler(null)

    then:
    final NullPointerException exception = thrown()
    exception.getMessage() == "SpanSamplingRules can't be null."
  }
}
