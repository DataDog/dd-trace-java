package datadog.trace.common.sampling

import datadog.trace.common.writer.ListWriter
import datadog.trace.core.DDSpan
import datadog.trace.core.test.DDCoreSpecification

import static datadog.trace.api.config.TracerConfig.TRACE_RATE_LIMIT
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLE_RATE
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_RULES
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_OPERATION_RULES
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_SERVICE_RULES
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP

class RuleBasedSamplingTest extends DDCoreSpecification {
  def "Rule Based Sampler is not created when properties not set"() {
    when:
    Sampler sampler = Sampler.Builder.forConfig(new Properties())

    then:
    !(sampler instanceof RuleBasedTraceSampler)
  }

  def "Rule Based Sampler is not created when just rate limit set"() {
    when:
    Properties properties = new Properties()
    properties.setProperty(TRACE_RATE_LIMIT, "50")
    Sampler sampler = Sampler.Builder.forConfig(properties)

    then:
    !(sampler instanceof RuleBasedTraceSampler)
  }

  def "sampling config combinations"() {
    given:
    Properties properties = new Properties()
    if (serviceRules != null) {
      properties.setProperty(TRACE_SAMPLING_SERVICE_RULES, serviceRules)
    }

    if (operationRules != null) {
      properties.setProperty(TRACE_SAMPLING_OPERATION_RULES, operationRules)
    }

    if (defaultRate != null) {
      properties.setProperty(TRACE_SAMPLE_RATE, defaultRate)
    }

    properties.setProperty(TRACE_RATE_LIMIT, "50")
    def tracer = tracerBuilder().writer(new ListWriter()).build()

    when:
    Sampler sampler = Sampler.Builder.forConfig(properties)

    then:
    sampler instanceof PrioritySampler

    when:
    DDSpan span = tracer.buildSpan("operation")
      .withServiceName("service")
      .withTag("env", "bar")
      .ignoreActiveSpan().start()
    ((PrioritySampler) sampler).setSamplingPriority(span)

    then:
    span.getTag(RuleBasedTraceSampler.SAMPLING_RULE_RATE) == expectedRuleRate
    span.getTag(RuleBasedTraceSampler.SAMPLING_LIMIT_RATE) == expectedRateLimit
    span.getTag(RateByServiceTraceSampler.SAMPLING_AGENT_RATE) == expectedAgentRate
    span.getSamplingPriority() == expectedPriority

    cleanup:
    tracer.close()

    where:
    serviceRules      | operationRules      | defaultRate | expectedRuleRate | expectedRateLimit | expectedAgentRate | expectedPriority
    // Matching neither passes through to rate based sampler
    "xx:1"            | null                | null        | null             | null              | 1.0               | SAMPLER_KEEP
    null              | "xx:1"              | null        | null             | null              | 1.0               | SAMPLER_KEEP

    // Matching neither with default rate
    null              | null                | "1"         | 1.0              | 50                | null              | USER_KEEP
    null              | null                | "0"         | 0                | null              | null              | USER_DROP
    "xx:1"            | null                | "1"         | 1.0              | 50                | null              | USER_KEEP
    null              | "xx:1"              | "1"         | 1.0              | 50                | null              | USER_KEEP
    "xx:1"            | null                | "0"         | 0                | null              | null              | USER_DROP
    null              | "xx:1"              | "0"         | 0                | null              | null              | USER_DROP

    // Matching service: keep
    "service:1"       | null                | null        | 1.0              | 50                | null              | USER_KEEP
    "s.*:1"           | null                | null        | 1.0              | 50                | null              | USER_KEEP
    ".*e:1"           | null                | null        | 1.0              | 50                | null              | USER_KEEP
    "[a-z]+:1"        | null                | null        | 1.0              | 50                | null              | USER_KEEP

    // Matching service: drop
    "service:0"       | null                | null        | 0                | null              | null              | USER_DROP
    "s.*:0"           | null                | null        | 0                | null              | null              | USER_DROP
    ".*e:0"           | null                | null        | 0                | null              | null              | USER_DROP
    "[a-z]+:0"        | null                | null        | 0                | null              | null              | USER_DROP

    // Matching service overrides default rate
    "service:1"       | null                | "0"         | 1.0              | 50                | null              | USER_KEEP
    "service:0"       | null                | "1"         | 0                | null              | null              | USER_DROP

    // multiple services
    "xxx:0,service:1" | null                | null        | 1.0              | 50                | null              | USER_KEEP
    "xxx:1,service:0" | null                | null        | 0                | null              | null              | USER_DROP

    // Matching operation : keep
    null              | "operation:1"       | null        | 1.0              | 50                | null              | USER_KEEP
    null              | "o.*:1"             | null        | 1.0              | 50                | null              | USER_KEEP
    null              | ".*n:1"             | null        | 1.0              | 50                | null              | USER_KEEP
    null              | "[a-z]+:1"          | null        | 1.0              | 50                | null              | USER_KEEP

    // Matching operation: drop
    null              | "operation:0"       | null        | 0                | null              | null              | USER_DROP
    null              | "o.*:0"             | null        | 0                | null              | null              | USER_DROP
    null              | ".*n:0"             | null        | 0                | null              | null              | USER_DROP
    null              | "[a-z]+:0"          | null        | 0                | null              | null              | USER_DROP

    // Matching operation overrides default rate
    null              | "operation:1"       | "0"         | 1.0              | 50                | null              | USER_KEEP
    null              | "operation:0"       | "1"         | 0                | null              | null              | USER_DROP

    // multiple operation combinations
    null              | "xxx:0,operation:1" | null        | 1.0              | 50                | null              | USER_KEEP
    null              | "xxx:1,operation:0" | null        | 0                | null              | null              | USER_DROP

    // Service and operation name combinations
    "service:1"       | "operation:0"       | null        | 1.0              | 50                | null              | USER_KEEP
    "service:1"       | "xxx:0"             | null        | 1.0              | 50                | null              | USER_KEEP
    "service:0"       | "operation:1"       | null        | 0                | null              | null              | USER_DROP
    "service:0"       | "xxx:1"             | null        | 0                | null              | null              | USER_DROP
    "xxx:0"           | "operation:1"       | null        | 1.0              | 50                | null              | USER_KEEP
    "xxx:1"           | "operation:0"       | null        | 0                | null              | null              | USER_DROP

    // There are no tests for ordering within service or operation rules because the rule order in that case is unspecified
  }

  def "sampling config JSON rules combinations"() {
    given:
    Properties properties = new Properties()
    properties.setProperty(TRACE_SAMPLING_RULES, jsonRules)

    if (defaultRate != null) {
      properties.setProperty(TRACE_SAMPLE_RATE, defaultRate)
    }

    properties.setProperty(TRACE_RATE_LIMIT, "50")
    def tracer = tracerBuilder().writer(new ListWriter()).build()

    when:
    Sampler sampler = Sampler.Builder.forConfig(properties)

    then:
    sampler instanceof PrioritySampler

    when:
    DDSpan span = tracer.buildSpan("operation")
      .withServiceName("service")
      .withTag("env", "bar")
      .withTag("tag", "foo")
      .withResourceName("resource")
      .ignoreActiveSpan()
      .start()
    ((PrioritySampler) sampler).setSamplingPriority(span)

    then:
    span.getTag(RuleBasedTraceSampler.SAMPLING_RULE_RATE) == expectedRuleRate
    span.getTag(RuleBasedTraceSampler.SAMPLING_LIMIT_RATE) == expectedRateLimit
    span.getTag(RateByServiceTraceSampler.SAMPLING_AGENT_RATE) == expectedAgentRate
    span.getSamplingPriority() == expectedPriority

    cleanup:
    tracer.close()

    where:
    jsonRules                                                                                                                                            | defaultRate | expectedRuleRate | expectedRateLimit | expectedAgentRate | expectedPriority
    // Matching neither passes through to rate based sampler
    "[{\"service\": \"xx\", \"sample_rate\": 1}]"                                                                                                        | null        | null             | null              | 1.0               | SAMPLER_KEEP
    "[{\"name\": \"xx\", \"sample_rate\": 1}]"                                                                                                           | null        | null             | null              | 1.0               | SAMPLER_KEEP

    // Matching neither with default rate
    "[{\"sample_rate\": 1}]"                                                                                                                             | "1"         | 1.0              | 50                | null              | USER_KEEP
    "[{\"sample_rate\": 0}]"                                                                                                                             | "0"         | 0                | null              | null              | USER_DROP
    "[]"                                                                                                                                                 | "0"         | 0                | null              | null              | USER_DROP
    "[{\"service\": \"xx\", \"sample_rate\": 1}]"                                                                                                        | "1"         | 1.0              | 50                | null              | USER_KEEP
    "[{\"name\": \"xx\", \"sample_rate\": 1}]"                                                                                                           | "1"         | 1.0              | 50                | null              | USER_KEEP
    "[{\"service\": \"xx\", \"sample_rate\": 1}]"                                                                                                        | "0"         | 0                | null              | null              | USER_DROP
    "[{\"name\": \"xx\", \"sample_rate\": 1}]"                                                                                                           | "0"         | 0                | null              | null              | USER_DROP

    // Matching service: keep
    "[{\"service\": \"service\", \"sample_rate\": 1}]"                                                                                                   | null        | 1.0              | 50                | null              | USER_KEEP

    // Matching service: drop
    "[{\"service\": \"service\", \"sample_rate\": 0}]"                                                                                                   | null        | 0                | null              | null              | USER_DROP

    // Matching service overrides default rate
    "[{\"service\": \"service\", \"sample_rate\": 1}]"                                                                                                   | "0"         | 1.0              | 50                | null              | USER_KEEP
    "[{\"service\": \"service\", \"sample_rate\": 0}]"                                                                                                   | "1"         | 0                | null              | null              | USER_DROP

    // multiple services
    "[{\"service\": \"xxx\", \"sample_rate\": 0}, {\"service\": \"service\", \"sample_rate\": 1}]"                                                       | null        | 1.0              | 50                | null              | USER_KEEP
    "[{\"service\": \"xxx\", \"sample_rate\": 1}, {\"service\": \"service\", \"sample_rate\": 0}]"                                                       | null        | 0                | null              | null              | USER_DROP

    // Matching operation : keep
    "[{\"name\": \"operation\", \"sample_rate\": 1}]"                                                                                                    | null        | 1.0              | 50                | null              | USER_KEEP

    // Matching operation: drop
    "[{\"name\": \"operation\", \"sample_rate\": 0}]"                                                                                                    | null        | 0                | null              | null              | USER_DROP

    // Matching operation overrides default rate
    "[{\"name\": \"operation\", \"sample_rate\": 1}]"                                                                                                    | "0"         | 1.0              | 50                | null              | USER_KEEP
    "[{\"name\": \"operation\", \"sample_rate\": 0}]"                                                                                                    | "1"         | 0                | null              | null              | USER_DROP

    // multiple operation combinations
    "[{\"name\": \"xxx\", \"sample_rate\": 0}, {\"name\": \"operation\", \"sample_rate\": 1}]"                                                           | null        | 1.0              | 50                | null              | USER_KEEP
    "[{\"name\": \"xxx\", \"sample_rate\": 1}, {\"name\": \"operation\", \"sample_rate\": 0}]"                                                           | null        | 0                | null              | null              | USER_DROP

    // Matching resource : keep
    "[{\"resource\": \"resource\", \"sample_rate\": 1}]"                                                                                                 | null        | 1.0              | 50                | null              | USER_KEEP

    // Matching resource: drop
    "[{\"resource\": \"resource\", \"sample_rate\": 0}]"                                                                                                 | null        | 0                | null              | null              | USER_DROP

    // Matching resource overrides default rate
    "[{\"resource\": \"resource\", \"sample_rate\": 1}]"                                                                                                 | "0"         | 1.0              | 50                | null              | USER_KEEP
    "[{\"resource\": \"resource\", \"sample_rate\": 0}]"                                                                                                 | "1"         | 0                | null              | null              | USER_DROP

    // Multiple resource combinations
    "[{\"resource\": \"xxx\", \"sample_rate\": 0}, {\"resource\": \"resource\", \"sample_rate\": 1}]"                                                    | null        | 1.0              | 50                | null              | USER_KEEP
    "[{\"resource\": \"xxx\", \"sample_rate\": 1}, {\"resource\": \"resource\", \"sample_rate\": 0}]"                                                    | null        | 0                | null              | null              | USER_DROP

    // Select matching service + operation rules
    "[{\"service\": \"service\", \"sample_rate\": 1}, {\"name\": \"operation\", \"sample_rate\": 0}]"                                                    | null        | 1.0              | 50                | null              | USER_KEEP
    "[{\"service\": \"service\", \"sample_rate\": 1}, {\"name\": \"xxx\", \"sample_rate\": 0}]"                                                          | null        | 1.0              | 50                | null              | USER_KEEP
    "[{\"service\": \"service\", \"sample_rate\": 0}, {\"name\": \"operation\", \"sample_rate\": 1}]"                                                    | null        | 0                | null              | null              | USER_DROP
    "[{\"service\": \"service\", \"sample_rate\": 0}, {\"name\": \"xxx\", \"sample_rate\": 1}]"                                                          | null        | 0                | null              | null              | USER_DROP
    "[{\"service\": \"xxx\", \"sample_rate\": 0}, {\"name\": \"operation\", \"sample_rate\": 1}]"                                                        | null        | 1.0              | 50                | null              | USER_KEEP
    "[{\"service\": \"xxx\", \"sample_rate\": 1}, {\"name\": \"operation\", \"sample_rate\": 0}]"                                                        | null        | 0                | null              | null              | USER_DROP

    // Select matching service + operation rules
    "[{\"service\": \"service\", \"name\": \"operation\", \"sample_rate\": 1}]"                                                                          | null        | 1.0              | 50                | null              | USER_KEEP
    "[{\"service\": \"service\", \"name\": \"xxx\", \"sample_rate\": 0}, {\"service\": \"service\", \"name\": \"operation\", \"sample_rate\": 1}]"       | null        | 1.0              | 50                | null              | USER_KEEP
    "[{\"service\": \"service\", \"name\": \"xxx\", \"sample_rate\": 0}, {\"service\": \"service\", \"sample_rate\": 1}]"                                | null        | 1.0              | 50                | null              | USER_KEEP
    "[{\"service\": \"service\", \"name\": \"xxx\", \"sample_rate\": 0}, {\"name\": \"operation\", \"sample_rate\": 1}]"                                 | null        | 1.0              | 50                | null              | USER_KEEP
    "[{\"service\": \"service\", \"resource\": \"xxx\", \"sample_rate\": 0}, {\"resource\": \"resource\", \"sample_rate\": 1}]"                          | null        | 1.0              | 50                | null              | USER_KEEP
    "[{\"service\": \"service\", \"name\": \"operation\", \"sample_rate\": 0}, {\"service\": \"service\", \"name\": \"operation\", \"sample_rate\": 1}]" | null        | 0                | null              | null              | USER_DROP
    "[{\"service\": \"service\", \"name\": \"operation\", \"sample_rate\": 0}]"                                                                          | null        | 0                | null              | null              | USER_DROP

    // Select matching service + resource
    "[{\"service\": \"service\", \"resource\": \"xxx\", \"sample_rate\": 0}, {\"service\": \"service\", \"resource\": \"resource\", \"sample_rate\": 1}]"                          | null        | 1.0              | 50                | null              | USER_KEEP

    // Select matching service + resource + operation rules
    "[{\"service\": \"service\", \"resource\": \"xxx\", \"sample_rate\": 0}, {\"service\": \"service\", \"resource\": \"resource\", \"name\": \"operation\", \"sample_rate\": 1}]"                          | null        | 1.0              | 50                | null              | USER_KEEP

    // Select matching single tag rules
    "[{\"tags\": {\"env\": \"xxx\"}, \"sample_rate\": 0}, {\"tags\": {\"env\": \"bar\"}, \"sample_rate\": 1}]"                                           | null        | 1.0              | 50                | null              | USER_KEEP
    "[{\"tags\": {\"env\": \"*x\"}, \"sample_rate\": 0}, {\"tags\": {\"env\": \"*\"}, \"sample_rate\": 1}]"                                              | null        | 1.0              | 50                | null              | USER_KEEP
    "[{\"tags\": {\"env\": \"x??\"}, \"sample_rate\": 0}, {\"tags\": {\"env\": \"b?r\"}, \"sample_rate\": 1}]"                                           | null        | 1.0              | 50                | null              | USER_KEEP
    "[{\"tags\": {\"env\": \"x??\"}, \"sample_rate\": 1}, {\"tags\": {\"env\": \"b?r\"}, \"sample_rate\": 0}]"                                           | null        | 0                | null              | null              | USER_DROP

    // Select matching two tags rules
    "[{\"tags\": {\"env\": \"xxx\"}, \"sample_rate\": 0}, {\"tags\": {\"env\": \"bar\", \"tag\": \"foo\"}, \"sample_rate\": 1}]"                         | null        | 1.0              | 50                | null              | USER_KEEP
    "[{\"tags\": {\"env\": \"*x\"}, \"sample_rate\": 0}, {\"tags\": {\"env\": \"*\", \"tag\": \"*\"}, \"sample_rate\": 1}]"                              | null        | 1.0              | 50                | null              | USER_KEEP
    "[{\"tags\": {\"env\": \"x??\"}, \"sample_rate\": 0}, {\"tags\": {\"env\": \"b?r\", \"tag\": \"f??\"}, \"sample_rate\": 1}]"                         | null        | 1.0              | 50                | null              | USER_KEEP
    "[{\"tags\": {\"env\": \"x??\"}, \"sample_rate\": 1}, {\"tags\": {\"env\": \"b?r\", \"tag\": \"f??\"}, \"sample_rate\": 0}]"                         | null        | 0                | null              | null              | USER_DROP

    // Select matching service + resource + operation + tag rules
    "[{\"service\": \"service\", \"resource\": \"xxx\", \"tags\": {\"env\": \"x??\"}, \"sample_rate\": 0}, {\"service\": \"service\", \"resource\": \"resource\", \"name\": \"operation\", \"tags\": {\"env\": \"b?r\", \"tag\": \"f??\"}, \"sample_rate\": 1}]"                          | null        | 1.0              | 50                | null              | USER_KEEP

  }

  def "Prefer JSON rules over other deprecated ones"() {
    setup:
    def tracer = tracerBuilder().writer(new ListWriter()).build()

    when:
    Properties properties = new Properties()
    properties.setProperty(TRACE_SAMPLING_SERVICE_RULES, "service:0")
    properties.setProperty(TRACE_SAMPLING_OPERATION_RULES, "operation:0")
    properties.setProperty(TRACE_SAMPLING_RULES, "[{\"service\": \"service\", \"name\": \"operation\", \"sample_rate\": 1.0}]")
    properties.setProperty(TRACE_RATE_LIMIT, "1")
    Sampler sampler = Sampler.Builder.forConfig(properties)

    DDSpan span1 = tracer.buildSpan("operation")
      .withServiceName("service")
      .withTag("env", "bar")
      .ignoreActiveSpan().start()

    DDSpan span2 = tracer.buildSpan("operation")
      .withServiceName("service")
      .withTag("env", "bar")
      .ignoreActiveSpan().start()

    ((PrioritySampler) sampler).setSamplingPriority(span1)
    // Span 2 should be rate limited if there isn't a >1 sec delay between these 2 lines
    ((PrioritySampler) sampler).setSamplingPriority(span2)

    then:
    span1.getTag(RuleBasedTraceSampler.SAMPLING_RULE_RATE) == 1.0
    span1.getTag(RuleBasedTraceSampler.SAMPLING_LIMIT_RATE) == 1.0
    span1.getTag(RateByServiceTraceSampler.SAMPLING_AGENT_RATE) == null
    span1.getSamplingPriority() == USER_KEEP

    span2.getTag(RuleBasedTraceSampler.SAMPLING_RULE_RATE) == 1.0
    span2.getTag(RuleBasedTraceSampler.SAMPLING_LIMIT_RATE) == 1.0
    span2.getTag(RateByServiceTraceSampler.SAMPLING_AGENT_RATE) == null
    span2.getSamplingPriority() == USER_DROP

    cleanup:
    tracer.close()
  }

  def "Rate limit is set for rate limited spans"() {
    setup:
    def tracer = tracerBuilder().writer(new ListWriter()).build()

    when:
    Properties properties = new Properties()
    properties.setProperty(TRACE_SAMPLING_SERVICE_RULES, "service:1")
    properties.setProperty(TRACE_RATE_LIMIT, "1")
    Sampler sampler = Sampler.Builder.forConfig(properties)

    DDSpan span1 = tracer.buildSpan("operation")
      .withServiceName("service")
      .withTag("env", "bar")
      .ignoreActiveSpan().start()

    DDSpan span2 = tracer.buildSpan("operation")
      .withServiceName("service")
      .withTag("env", "bar")
      .ignoreActiveSpan().start()

    ((PrioritySampler) sampler).setSamplingPriority(span1)
    // Span 2 should be rate limited if there isn't a >1 sec delay between these 2 lines
    ((PrioritySampler) sampler).setSamplingPriority(span2)

    then:
    span1.getTag(RuleBasedTraceSampler.SAMPLING_RULE_RATE) == 1.0
    span1.getTag(RuleBasedTraceSampler.SAMPLING_LIMIT_RATE) == 1.0
    span1.getTag(RateByServiceTraceSampler.SAMPLING_AGENT_RATE) == null
    span1.getSamplingPriority() == USER_KEEP

    span2.getTag(RuleBasedTraceSampler.SAMPLING_RULE_RATE) == 1.0
    span2.getTag(RuleBasedTraceSampler.SAMPLING_LIMIT_RATE) == 1.0
    span2.getTag(RateByServiceTraceSampler.SAMPLING_AGENT_RATE) == null
    span2.getSamplingPriority() == USER_DROP

    cleanup:
    tracer.close()
  }

  def "Rate limit is set for rate limited spans (matched on different rules)"() {
    setup:
    def tracer = tracerBuilder().writer(new ListWriter()).build()

    when:
    Properties properties = new Properties()
    properties.setProperty(TRACE_SAMPLING_SERVICE_RULES, "service:1,foo:1")
    properties.setProperty(TRACE_RATE_LIMIT, "1")
    Sampler sampler = Sampler.Builder.forConfig(properties)

    DDSpan span1 = tracer.buildSpan("operation")
      .withServiceName("service")
      .withTag("env", "bar")
      .ignoreActiveSpan().start()
    DDSpan span2 = tracer.buildSpan("operation")
      .withServiceName("foo")
      .withTag("env", "bar")
      .ignoreActiveSpan().start()

    ((PrioritySampler) sampler).setSamplingPriority(span1)
    // Span 2 should be rate limited if there isn't a >1 sec delay between these 2 lines
    ((PrioritySampler) sampler).setSamplingPriority(span2)

    then:
    span1.getTag(RuleBasedTraceSampler.SAMPLING_RULE_RATE) == 1.0
    span1.getTag(RuleBasedTraceSampler.SAMPLING_LIMIT_RATE) == 1.0
    span1.getTag(RateByServiceTraceSampler.SAMPLING_AGENT_RATE) == null
    span1.getSamplingPriority() == USER_KEEP

    span2.getTag(RuleBasedTraceSampler.SAMPLING_RULE_RATE) == 1.0
    span2.getTag(RuleBasedTraceSampler.SAMPLING_LIMIT_RATE) == 1.0
    span2.getTag(RateByServiceTraceSampler.SAMPLING_AGENT_RATE) == null
    span2.getSamplingPriority() == USER_DROP

    cleanup:
    tracer.close()
  }
}
