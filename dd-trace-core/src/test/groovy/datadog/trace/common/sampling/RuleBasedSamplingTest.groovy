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

import static datadog.trace.api.sampling.SamplingMechanism.AGENT_RATE
import static datadog.trace.api.sampling.SamplingMechanism.LOCAL_USER_RULE


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

    def propagationMap = span.context.propagationTags.createTagMap()
    def decisionMaker = propagationMap.get('_dd.p.dm')

    def expectedDmStr = (expectedDecisionMaker == null) ? null : "-" + expectedDecisionMaker

    then:
    span.getTag(RuleBasedTraceSampler.SAMPLING_RULE_RATE) == expectedRuleRate
    span.getTag(RuleBasedTraceSampler.SAMPLING_LIMIT_RATE) == expectedRateLimit
    span.getTag(RateByServiceTraceSampler.SAMPLING_AGENT_RATE) == expectedAgentRate
    span.getSamplingPriority() == expectedPriority
    decisionMaker == expectedDmStr

    cleanup:
    tracer.close()

    where:
    // NOTE: Using a defaultRate is still considered to be a "rule"
    // decisionMaker is LOCAL_USER_RULE and expectedRuleRate is non-null
    // When trace is dropped, decisionMaker isn't tracked -- e.g. null

    serviceRules      | operationRules      | defaultRate | expectedDecisionMaker | expectedPriority | expectedRuleRate | expectedRateLimit | expectedAgentRate

    // Matching neither passes through to rate based sampler
    "xx:1"            | null                | null        | AGENT_RATE            | SAMPLER_KEEP     | null             | null              | 1.0
    null              | "xx:1"              | null        | AGENT_RATE            | SAMPLER_KEEP     | null             | null              | 1.0

    // Matching neither with default rate - per spec, use of defaultRate is considered a "rule"
    null              | null                | "1"         | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    null              | null                | "0"         | null                  | USER_DROP        | 0                | null              | null
    "xx:1"            | null                | "1"         | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    null              | "xx:1"              | "1"         | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    "xx:1"            | null                | "0"         | null                  | USER_DROP        | 0                | null              | null
    null              | "xx:1"              | "0"         | null                  | USER_DROP        | 0                | null              | null

    // Matching service: keep
    "service:1"       | null                | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    "s.*:1"           | null                | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    ".*e:1"           | null                | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null

    // Matching service: drop
    "service:0"       | null                | null        | null                  | USER_DROP        | 0                | null              | null
    "s.*:0"           | null                | null        | null                  | USER_DROP        | 0                | null              | null
    ".*e:0"           | null                | null        | null                  | USER_DROP        | 0                | null              | null

    // Matching service overrides default rate
    "service:1"       | null                | "0"         | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    "service:0"       | null                | "1"         | null                  | USER_DROP        | 0                | null              | null

    // multiple services
    "xxx:0,service:1" | null                | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    "xxx:1,service:0" | null                | null        | null                  | USER_DROP        | 0                | null              | null

    // Matching operation : keep
    null              | "operation:1"       | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    null              | "o.*:1"             | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    null              | ".*n:1"             | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null

    // Matching operation: drop
    null              | "operation:0"       | null        | null                  | USER_DROP        | 0                | null              | null
    null              | "o.*:0"             | null        | null                  | USER_DROP        | 0                | null              | null
    null              | ".*n:0"             | null        | null                  | USER_DROP        | 0                | null              | null

    // Matching operation overrides default rate
    null              | "operation:1"       | "0"         | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    null              | "operation:0"       | "1"         | null                  | USER_DROP        | 0                | null              | null

    // multiple operation combinations
    null              | "xxx:0,operation:1" | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    null              | "xxx:1,operation:0" | null        | null                  | USER_DROP        | 0                | null              | null

    // Service and operation name combinations
    "service:1"       | "operation:0"       | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    "service:1"       | "xxx:0"             | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    "service:0"       | "operation:1"       | null        | null                  | USER_DROP        | 0                | null              | null
    "service:0"       | "xxx:1"             | null        | null                  | USER_DROP        | 0                | null              | null
    "xxx:0"           | "operation:1"       | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    "xxx:1"           | "operation:0"       | null        | null                  | USER_DROP        | 0                | null              | null

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

    def propagationMap = span.context.propagationTags.createTagMap()
    def decisionMaker = propagationMap.get('_dd.p.dm')

    def expectedDmStr = (expectedDecisionMaker == null) ? null : "-" + expectedDecisionMaker

    then:
    span.getTag(RuleBasedTraceSampler.SAMPLING_RULE_RATE) == expectedRuleRate
    span.getTag(RuleBasedTraceSampler.SAMPLING_LIMIT_RATE) == expectedRateLimit
    span.getTag(RateByServiceTraceSampler.SAMPLING_AGENT_RATE) == expectedAgentRate
    span.getSamplingPriority() == expectedPriority
    decisionMaker == expectedDmStr

    cleanup:
    tracer.close()

    where:
    // NOTE: Using a defaultRate is still considered to be a "rule"
    // decisionMaker is LOCAL_USER_RULE and expectedRuleRate is non-null
    // When trace is dropped, decisionMaker isn't tracked -- e.g. null

    jsonRules                                                                                                                                                                      | defaultRate | expectedDecisionMaker | expectedPriority | expectedRuleRate | expectedRateLimit | expectedAgentRate
    // Matching neither passes through to rate based sampler
    "[{\"service\": \"xx\", \"sample_rate\": 1}]"                                                                                                        						   | null        | AGENT_RATE            | SAMPLER_KEEP     | null             | null              | 1.0
    "[{\"name\": \"xx\", \"sample_rate\": 1}]"                                                                                                                                     | null        | AGENT_RATE            | SAMPLER_KEEP     | null             | null              | 1.0

    // Matching neither with default rate
    "[{\"sample_rate\": 1}]"                                                                                                                                                       | "1"         | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    "[{\"sample_rate\": 0}]"                                                                                                                                                       | "0"         | null                  | USER_DROP        | 0                | null              | null
    "[]"                                                                                                                                                                           | "0"         | null                  | USER_DROP        | 0                | null              | null
    "[{\"service\": \"xx\", \"sample_rate\": 1}]"                                                                                                                                  | "1"         | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    "[{\"name\": \"xx\", \"sample_rate\": 1}]"                                                                                                                                     | "1"         | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    "[{\"service\": \"xx\", \"sample_rate\": 1}]"                                                                                                                                  | "0"         | null                  | USER_DROP        | 0                | null              | null
    "[{\"name\": \"xx\", \"sample_rate\": 1}]"                                                                                                                                     | "0"         | null                  | USER_DROP        | 0                | null              | null

    // Matching service: keep
    "[{\"service\": \"service\", \"sample_rate\": 1}]"                                                                                                                             | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null

    // Matching service: drop
    "[{\"service\": \"service\", \"sample_rate\": 0}]"                                                                                                   						   | null        | null                  | USER_DROP        | 0                | null              | null

    // Matching service overrides default rate
    "[{\"service\": \"service\", \"sample_rate\": 1}]"                                                                                                   						   | "0"         | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    "[{\"service\": \"service\", \"sample_rate\": 0}]"                                                                                                   						   | "1"         | null                  | USER_DROP        | 0                | null              | null

    // multiple services
    "[{\"service\": \"xxx\", \"sample_rate\": 0}, {\"service\": \"service\", \"sample_rate\": 1}]"                                                       						   | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    "[{\"service\": \"xxx\", \"sample_rate\": 1}, {\"service\": \"service\", \"sample_rate\": 0}]"                                                       						   | null        | null                  | USER_DROP        | 0                | null              | null

    // Matching operation : keep
    "[{\"name\": \"operation\", \"sample_rate\": 1}]"                                                                                                    						   | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null

    // Matching operation: drop
    "[{\"name\": \"operation\", \"sample_rate\": 0}]"                                                                                                    						   | null        | null                  | USER_DROP        | 0                | null              | null

    // Matching operation overrides default rate
    "[{\"name\": \"operation\", \"sample_rate\": 1}]"                                                                                                    						   | "0"         | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    "[{\"name\": \"operation\", \"sample_rate\": 0}]"                                                                                                    						   | "1"         | null                  | USER_DROP        | 0                | null              | null

    // multiple operation combinations
    "[{\"name\": \"xxx\", \"sample_rate\": 0}, {\"name\": \"operation\", \"sample_rate\": 1}]"                                                           						   | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    "[{\"name\": \"xxx\", \"sample_rate\": 1}, {\"name\": \"operation\", \"sample_rate\": 0}]"                                                           					       | null        | null                  | USER_DROP        | 0                | null              | null

    // Matching resource : keep
    "[{\"resource\": \"resource\", \"sample_rate\": 1}]"                                                                                                 						   | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null

    // Matching resource: drop
    "[{\"resource\": \"resource\", \"sample_rate\": 0}]"                                                                                                 						   | null        | null                  | USER_DROP        | 0                | null              | null

    // Matching resource overrides default rate
    "[{\"resource\": \"resource\", \"sample_rate\": 1}]"                                                                                                 						   | "0"         | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    "[{\"resource\": \"resource\", \"sample_rate\": 0}]"                                                                                                 					       | "1"         | null                  | USER_DROP        | 0                | null              | null

    // Multiple resource combinations
    "[{\"resource\": \"xxx\", \"sample_rate\": 0}, {\"resource\": \"resource\", \"sample_rate\": 1}]"                                                    						   | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    "[{\"resource\": \"xxx\", \"sample_rate\": 1}, {\"resource\": \"resource\", \"sample_rate\": 0}]"                                                    						   | null        | null                  | USER_DROP        | 0                | null              | null

    // Select matching service + operation rules
    "[{\"service\": \"service\", \"sample_rate\": 1}, {\"name\": \"operation\", \"sample_rate\": 0}]"                                                    						   | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    "[{\"service\": \"service\", \"sample_rate\": 1}, {\"name\": \"xxx\", \"sample_rate\": 0}]"                                                          						   | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    "[{\"service\": \"service\", \"sample_rate\": 0}, {\"name\": \"operation\", \"sample_rate\": 1}]"                                                    						   | null        | null                  | USER_DROP        | 0                | null              | null
    "[{\"service\": \"service\", \"sample_rate\": 0}, {\"name\": \"xxx\", \"sample_rate\": 1}]"                                                          						   | null        | null                  | USER_DROP        | 0                | null              | null
    "[{\"service\": \"xxx\", \"sample_rate\": 0}, {\"name\": \"operation\", \"sample_rate\": 1}]"                                                        						   | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    "[{\"service\": \"xxx\", \"sample_rate\": 1}, {\"name\": \"operation\", \"sample_rate\": 0}]"                                                        					       | null        | null                  | USER_DROP        | 0                | null              | null

    // Select matching service + operation rules
    "[{\"service\": \"service\", \"name\": \"operation\", \"sample_rate\": 1}]"                                                                          						   | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    "[{\"service\": \"service\", \"name\": \"xxx\", \"sample_rate\": 0}, {\"service\": \"service\", \"name\": \"operation\", \"sample_rate\": 1}]"       						   | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    "[{\"service\": \"service\", \"name\": \"xxx\", \"sample_rate\": 0}, {\"service\": \"service\", \"sample_rate\": 1}]"                                						   | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    "[{\"service\": \"service\", \"name\": \"xxx\", \"sample_rate\": 0}, {\"name\": \"operation\", \"sample_rate\": 1}]"                                 						   | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    "[{\"service\": \"service\", \"resource\": \"xxx\", \"sample_rate\": 0}, {\"resource\": \"resource\", \"sample_rate\": 1}]"                          						   | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    "[{\"service\": \"service\", \"name\": \"operation\", \"sample_rate\": 0}, {\"service\": \"service\", \"name\": \"operation\", \"sample_rate\": 1}]" 						   | null        | null                  | USER_DROP        | 0                | null              | null
    "[{\"service\": \"service\", \"name\": \"operation\", \"sample_rate\": 0}]"                                                                          						   | null        | null                  | USER_DROP        | 0                | null              | null

    // Select matching service + resource
    "[{\"service\": \"service\", \"resource\": \"xxx\", \"sample_rate\": 0}, {\"service\": \"service\", \"resource\": \"resource\", \"sample_rate\": 1}]"                          | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null

    // Select matching service + resource + operation rules
    "[{\"service\": \"service\", \"resource\": \"xxx\", \"sample_rate\": 0}, {\"service\": \"service\", \"resource\": \"resource\", \"name\": \"operation\", \"sample_rate\": 1}]" | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null

    // Select matching single tag rules
    "[{\"tags\": {\"env\": \"xxx\"}, \"sample_rate\": 0}, {\"tags\": {\"env\": \"bar\"}, \"sample_rate\": 1}]"                                           						   | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    "[{\"tags\": {\"env\": \"*x\"}, \"sample_rate\": 0}, {\"tags\": {\"env\": \"*\"}, \"sample_rate\": 1}]"                                              						   | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    "[{\"tags\": {\"env\": \"x??\"}, \"sample_rate\": 0}, {\"tags\": {\"env\": \"b?r\"}, \"sample_rate\": 1}]"                                           						   | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    "[{\"tags\": {\"env\": \"x??\"}, \"sample_rate\": 1}, {\"tags\": {\"env\": \"b?r\"}, \"sample_rate\": 0}]"                                           						   | null        | null                  | USER_DROP        | 0                | null              | null

    // Select matching two tags rules
    "[{\"tags\": {\"env\": \"xxx\"}, \"sample_rate\": 0}, {\"tags\": {\"env\": \"bar\", \"tag\": \"foo\"}, \"sample_rate\": 1}]"                         						   | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    "[{\"tags\": {\"env\": \"*x\"}, \"sample_rate\": 0}, {\"tags\": {\"env\": \"*\", \"tag\": \"*\"}, \"sample_rate\": 1}]"                         						       | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    "[{\"tags\": {\"env\": \"x??\"}, \"sample_rate\": 0}, {\"tags\": {\"env\": \"b?r\", \"tag\": \"f??\"}, \"sample_rate\": 1}]"                         						   | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
    "[{\"tags\": {\"env\": \"x??\"}, \"sample_rate\": 1}, {\"tags\": {\"env\": \"b?r\", \"tag\": \"f??\"}, \"sample_rate\": 0}]"                         						   | null        | null                  | USER_DROP        | 0                | null              | null

    // Select matching service + resource + operation + tag rules
    "[{\"service\": \"service\", \"resource\": \"xxx\", \"tags\": {\"env\": \"x??\"}, \"sample_rate\": 0}, {\"service\": \"service\", \"resource\": \"resource\", \"name\": \"operation\", \"tags\": {\"env\": \"b?r\", \"tag\": \"f??\"}, \"sample_rate\": 1}]"    | null        | LOCAL_USER_RULE       | USER_KEEP        | 1.0              | 50                | null
  }

  def "tag types test"() {
    given:
    def json = """[{
      "tags": {"testTag": "${tagPattern}"}, 
      "sample_rate": 1
    }]"""
    Properties properties = new Properties()
    properties.setProperty(TRACE_SAMPLING_RULES, json)
    properties.setProperty(TRACE_SAMPLE_RATE, "0")

    def tracer = tracerBuilder().writer(new ListWriter()).build()
    PrioritySampler sampler = (PrioritySampler)Sampler.Builder.forConfig(properties)

    when:
    DDSpan span = tracer.buildSpan("operation")
      .withServiceName("service")
      .withResourceName("resource")
      .withTag("env", "bar")
      .ignoreActiveSpan()
      .start()

    span.setTag("testTag", tagValue)

    sampler.setSamplingPriority(span)

    then:
    span.getSamplingPriority() == (expectedMatch ? USER_KEEP : USER_DROP)

    cleanup:
    tracer.close()

    where:
    tagPattern  | tagValue                  | expectedMatch
    "*"         | "anything..."             | true
    "*"         | null                      | false
    "*"         | new StringBuilder("foo")  | true
    "*"         | object()                  | true
    "**"        | object()                  | true
    "?"         | object()                  | false
    "*"         | "foo"                     | true
    "**"        | "foo"                     | true
    "**"        | true                      | true
    "**"        | false                     | true
    "**"        | 20                        | true
    "**"        | 20L                       | true
    "**"        | 20.1F                     | true
    "**"        | 20.1D                     | true
    "**"        | bigInteger("20")          | true
    "**"        | bigDecimal("20.1")        | true
    "foo"       | "foo"                     | true
    "foo"       | new StringBuilder("foo")  | true
    "foo"       | "not-foo"                 | false
    "ba?"       | "bar"                     | true
    "20"        | 20                        | true
    "20"        | Integer.valueOf(20)       | true
    "20"        | 20L                       | true
    "20"        | Long.valueOf(20)          | true
    "20"        | 20F                       | true
    "20"        | 20.1F                     | false
    "20.*"      | 20.1F                     | false
    "20.1"      | 20.1D                     | false
    "*"         | 20.1D                     | true
    "20"        | bigInteger("20")          | true
    "20"        | bigDecimal("20")          | true
    "*"         | bigDecimal("20.1")        | true
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

  // helper functions - to subvert codenarc
  static bigInteger(str) {
    return new BigInteger(str)
  }

  static bigDecimal(str) {
    return new BigDecimal(str)
  }

  static object() {
    return new Object() {
        @Override
        String toString() {
          return 'object'
        }
      }
  }
}
