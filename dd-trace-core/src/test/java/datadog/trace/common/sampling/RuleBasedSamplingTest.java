package datadog.trace.common.sampling;

import static datadog.trace.api.config.TracerConfig.TRACE_RATE_LIMIT;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLE_RATE;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_OPERATION_RULES;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_RULES;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_SERVICE_RULES;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.converter.PrioritySamplingConverter;
import datadog.trace.test.junit.utils.converter.SamplingMechanismConverter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.converter.ConvertWith;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tabletest.junit.TableTest;

class RuleBasedSamplingTest extends DDCoreJavaSpecification {

  @Test
  void ruleBasedSamplerIsNotCreatedWhenPropertiesNotSet() {
    Sampler sampler = Sampler.Builder.forConfig(new Properties());

    assertFalse(sampler instanceof RuleBasedTraceSampler);
  }

  @Test
  void ruleBasedSamplerIsNotCreatedWhenJustRateLimitSet() {
    Properties properties = new Properties();
    properties.setProperty(TRACE_RATE_LIMIT, "50");
    Sampler sampler = Sampler.Builder.forConfig(properties);

    assertFalse(sampler instanceof RuleBasedTraceSampler);
  }

  // NOTE: Using a defaultRate is still considered to be a "rule"
  // decisionMaker is LOCAL_USER_RULE and expectedRuleRate is non-null
  // When trace is dropped, decisionMaker isn't tracked -- e.g. null
  @TableTest({
    "scenario            | serviceRules    | operationRules    | defaultRate | expectedDecisionMaker             | expectedPriority              | expectedRuleRate | expectedRateLimit | expectedAgentRate",
    "svc xx no match     | xx:1            |                   |             | SamplingMechanism.AGENT_RATE      | PrioritySampling.SAMPLER_KEEP |                  |                   | 1.0              ",
    "op xx no match      |                 | xx:1              |             | SamplingMechanism.AGENT_RATE      | PrioritySampling.SAMPLER_KEEP |                  |                   | 1.0              ",
    "no rules default 1  |                 |                   | 1           | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "no rules default 0  |                 |                   | 0           |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    "svc xx default 1    | xx:1            |                   | 1           | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "op xx default 1     |                 | xx:1              | 1           | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "svc xx default 0    | xx:1            |                   | 0           |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    "op xx default 0     |                 | xx:1              | 0           |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    "service:1           | service:1       |                   |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "s.*:1               | s.*:1           |                   |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    ".*e:1               | .*e:1           |                   |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "service:0           | service:0       |                   |             |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    "s.*:0               | s.*:0           |                   |             |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    ".*e:0               | .*e:0           |                   |             |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    "service:1 default 0 | service:1       |                   | 0           | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "service:0 default 1 | service:0       |                   | 1           |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    "xxx:0 service:1     | xxx:0,service:1 |                   |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "xxx:1 service:0     | xxx:1,service:0 |                   |             |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    "operation:1         |                 | operation:1       |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "o.*:1               |                 | o.*:1             |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    ".*n:1               |                 | .*n:1             |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "operation:0         |                 | operation:0       |             |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    "o.*:0               |                 | o.*:0             |             |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    ".*n:0               |                 | .*n:0             |             |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    "op:1 default 0      |                 | operation:1       | 0           | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "op:0 default 1      |                 | operation:0       | 1           |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    "xxx:0 op:1          |                 | xxx:0,operation:1 |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "xxx:1 op:0          |                 | xxx:1,operation:0 |             |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    "svc:1 op:0          | service:1       | operation:0       |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "svc:1 xxx:0         | service:1       | xxx:0             |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "svc:0 op:1          | service:0       | operation:1       |             |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    "svc:0 xxx:1         | service:0       | xxx:1             |             |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    "xxx:0 op:1 combo    | xxx:0           | operation:1       |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "xxx:1 op:0 combo    | xxx:1           | operation:0       |             |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  "
  })
  void samplingConfigCombinations(
      String serviceRules,
      String operationRules,
      String defaultRate,
      @ConvertWith(SamplingMechanismConverter.class) Byte expectedDecisionMaker,
      @ConvertWith(PrioritySamplingConverter.class) byte expectedPriority,
      Double expectedRuleRate,
      Integer expectedRateLimit,
      Double expectedAgentRate) {
    Properties properties = new Properties();
    if (serviceRules != null) {
      properties.setProperty(TRACE_SAMPLING_SERVICE_RULES, serviceRules);
    }
    if (operationRules != null) {
      properties.setProperty(TRACE_SAMPLING_OPERATION_RULES, operationRules);
    }
    if (defaultRate != null) {
      properties.setProperty(TRACE_SAMPLE_RATE, defaultRate);
    }
    properties.setProperty(TRACE_RATE_LIMIT, "50");
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      Sampler sampler = Sampler.Builder.forConfig(properties);
      assertInstanceOf(PrioritySampler.class, sampler);

      DDSpan span =
          (DDSpan)
              tracer
                  .buildSpan("datadog", "operation")
                  .withServiceName("service")
                  .withTag("env", "bar")
                  .ignoreActiveSpan()
                  .start();
      ((PrioritySampler) sampler).setSamplingPriority(span);

      Map<String, String> propagationMap = span.spanContext().getPropagationTags().createTagMap();
      String decisionMaker = propagationMap.get("_dd.p.dm");
      String expectedDmStr =
          expectedDecisionMaker == null ? null : "-" + (int) expectedDecisionMaker;

      assertTagEquals(expectedRuleRate, span.getTag(RuleBasedTraceSampler.SAMPLING_RULE_RATE));
      assertTagEquals(expectedRateLimit, span.getTag(RuleBasedTraceSampler.SAMPLING_LIMIT_RATE));
      assertTagEquals(
          expectedAgentRate, span.getTag(RateByServiceTraceSampler.SAMPLING_AGENT_RATE));
      assertEquals(expectedPriority, (int) span.getSamplingPriority());
      assertEquals(expectedDmStr, decisionMaker);
    } finally {
      tracer.close();
    }
  }

  // NOTE: Using a defaultRate is still considered to be a "rule"
  // decisionMaker is LOCAL_USER_RULE and expectedRuleRate is non-null
  // When trace is dropped, decisionMaker isn't tracked -- e.g. null
  @TableTest({
    "scenario            | jsonRules                                                                                                                                                                                                                                                    | defaultRate | expectedDecisionMaker             | expectedPriority              | expectedRuleRate | expectedRateLimit | expectedAgentRate",
    "svc xx no match     | '[{\"service\": \"xx\", \"sample_rate\": 1}]'                                                                                                                                                                                                                |             | SamplingMechanism.AGENT_RATE      | PrioritySampling.SAMPLER_KEEP |                  |                   | 1.0              ",
    "name xx no match    | '[{\"name\": \"xx\", \"sample_rate\": 1}]'                                                                                                                                                                                                                   |             | SamplingMechanism.AGENT_RATE      | PrioritySampling.SAMPLER_KEEP |                  |                   | 1.0              ",
    "sample_rate 1 def 1 | '[{\"sample_rate\": 1}]'                                                                                                                                                                                                                                     | 1           | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "sample_rate 0 def 0 | '[{\"sample_rate\": 0}]'                                                                                                                                                                                                                                     | 0           |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    "empty rules def 0   | '[]'                                                                                                                                                                                                                                                         | 0           |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    "svc xx def 1        | '[{\"service\": \"xx\", \"sample_rate\": 1}]'                                                                                                                                                                                                                | 1           | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "name xx def 1       | '[{\"name\": \"xx\", \"sample_rate\": 1}]'                                                                                                                                                                                                                   | 1           | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "svc xx def 0        | '[{\"service\": \"xx\", \"sample_rate\": 1}]'                                                                                                                                                                                                                | 0           |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    "name xx def 0       | '[{\"name\": \"xx\", \"sample_rate\": 1}]'                                                                                                                                                                                                                   | 0           |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    "svc match keep      | '[{\"service\": \"service\", \"sample_rate\": 1}]'                                                                                                                                                                                                           |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "svc match drop      | '[{\"service\": \"service\", \"sample_rate\": 0}]'                                                                                                                                                                                                           |             |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    "svc keep def 0      | '[{\"service\": \"service\", \"sample_rate\": 1}]'                                                                                                                                                                                                           | 0           | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "svc drop def 1      | '[{\"service\": \"service\", \"sample_rate\": 0}]'                                                                                                                                                                                                           | 1           |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    "xxx:0 svc:1         | '[{\"service\": \"xxx\", \"sample_rate\": 0}, {\"service\": \"service\", \"sample_rate\": 1}]'                                                                                                                                                               |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "xxx:1 svc:0         | '[{\"service\": \"xxx\", \"sample_rate\": 1}, {\"service\": \"service\", \"sample_rate\": 0}]'                                                                                                                                                               |             |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    "op match keep       | '[{\"name\": \"operation\", \"sample_rate\": 1}]'                                                                                                                                                                                                            |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "op match drop       | '[{\"name\": \"operation\", \"sample_rate\": 0}]'                                                                                                                                                                                                            |             |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    "op keep def 0       | '[{\"name\": \"operation\", \"sample_rate\": 1}]'                                                                                                                                                                                                            | 0           | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "op drop def 1       | '[{\"name\": \"operation\", \"sample_rate\": 0}]'                                                                                                                                                                                                            | 1           |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    "xxx:0 op:1          | '[{\"name\": \"xxx\", \"sample_rate\": 0}, {\"name\": \"operation\", \"sample_rate\": 1}]'                                                                                                                                                                   |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "xxx:1 op:0          | '[{\"name\": \"xxx\", \"sample_rate\": 1}, {\"name\": \"operation\", \"sample_rate\": 0}]'                                                                                                                                                                   |             |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    "res match keep      | '[{\"resource\": \"resource\", \"sample_rate\": 1}]'                                                                                                                                                                                                         |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "res match drop      | '[{\"resource\": \"resource\", \"sample_rate\": 0}]'                                                                                                                                                                                                         |             |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    "res keep def 0      | '[{\"resource\": \"resource\", \"sample_rate\": 1}]'                                                                                                                                                                                                         | 0           | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "res drop def 1      | '[{\"resource\": \"resource\", \"sample_rate\": 0}]'                                                                                                                                                                                                         | 1           |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    "xxx res:1           | '[{\"resource\": \"xxx\", \"sample_rate\": 0}, {\"resource\": \"resource\", \"sample_rate\": 1}]'                                                                                                                                                            |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "xxx res:0           | '[{\"resource\": \"xxx\", \"sample_rate\": 1}, {\"resource\": \"resource\", \"sample_rate\": 0}]'                                                                                                                                                            |             |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    "svc:1 op:0          | '[{\"service\": \"service\", \"sample_rate\": 1}, {\"name\": \"operation\", \"sample_rate\": 0}]'                                                                                                                                                            |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "svc:1 xxx:0         | '[{\"service\": \"service\", \"sample_rate\": 1}, {\"name\": \"xxx\", \"sample_rate\": 0}]'                                                                                                                                                                  |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "svc:0 op:1          | '[{\"service\": \"service\", \"sample_rate\": 0}, {\"name\": \"operation\", \"sample_rate\": 1}]'                                                                                                                                                            |             |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    "svc:0 xxx:1         | '[{\"service\": \"service\", \"sample_rate\": 0}, {\"name\": \"xxx\", \"sample_rate\": 1}]'                                                                                                                                                                  |             |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    "xxx:0 op:1          | '[{\"service\": \"xxx\", \"sample_rate\": 0}, {\"name\": \"operation\", \"sample_rate\": 1}]'                                                                                                                                                                |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "xxx:1 op:0          | '[{\"service\": \"xxx\", \"sample_rate\": 1}, {\"name\": \"operation\", \"sample_rate\": 0}]'                                                                                                                                                                |             |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    "svc+op keep         | '[{\"service\": \"service\", \"name\": \"operation\", \"sample_rate\": 1}]'                                                                                                                                                                                  |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "svc+xxx then svc+op | '[{\"service\": \"service\", \"name\": \"xxx\", \"sample_rate\": 0}, {\"service\": \"service\", \"name\": \"operation\", \"sample_rate\": 1}]'                                                                                                               |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "svc+xxx then svc    | '[{\"service\": \"service\", \"name\": \"xxx\", \"sample_rate\": 0}, {\"service\": \"service\", \"sample_rate\": 1}]'                                                                                                                                        |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "svc+xxx then op     | '[{\"service\": \"service\", \"name\": \"xxx\", \"sample_rate\": 0}, {\"name\": \"operation\", \"sample_rate\": 1}]'                                                                                                                                         |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "svc+res:xxx res     | '[{\"service\": \"service\", \"resource\": \"xxx\", \"sample_rate\": 0}, {\"resource\": \"resource\", \"sample_rate\": 1}]'                                                                                                                                  |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "svc+op drop first   | '[{\"service\": \"service\", \"name\": \"operation\", \"sample_rate\": 0}, {\"service\": \"service\", \"name\": \"operation\", \"sample_rate\": 1}]'                                                                                                         |             |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    "svc+op drop only    | '[{\"service\": \"service\", \"name\": \"operation\", \"sample_rate\": 0}]'                                                                                                                                                                                  |             |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    "svc+res match       | '[{\"service\": \"service\", \"resource\": \"xxx\", \"sample_rate\": 0}, {\"service\": \"service\", \"resource\": \"resource\", \"sample_rate\": 1}]'                                                                                                        |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "svc+res+op match    | '[{\"service\": \"service\", \"resource\": \"xxx\", \"sample_rate\": 0}, {\"service\": \"service\", \"resource\": \"resource\", \"name\": \"operation\", \"sample_rate\": 1}]'                                                                               |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "tag env:bar keep    | '[{\"tags\": {\"env\": \"xxx\"}, \"sample_rate\": 0}, {\"tags\": {\"env\": \"bar\"}, \"sample_rate\": 1}]'                                                                                                                                                   |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "tag env:*x then *   | '[{\"tags\": {\"env\": \"*x\"}, \"sample_rate\": 0}, {\"tags\": {\"env\": \"*\"}, \"sample_rate\": 1}]'                                                                                                                                                      |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "tag env:b?r keep    | '[{\"tags\": {\"env\": \"x??\"}, \"sample_rate\": 0}, {\"tags\": {\"env\": \"b?r\"}, \"sample_rate\": 1}]'                                                                                                                                                   |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "tag env:b?r drop    | '[{\"tags\": {\"env\": \"x??\"}, \"sample_rate\": 1}, {\"tags\": {\"env\": \"b?r\"}, \"sample_rate\": 0}]'                                                                                                                                                   |             |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    "2tags env+tag keep  | '[{\"tags\": {\"env\": \"xxx\"}, \"sample_rate\": 0}, {\"tags\": {\"env\": \"bar\", \"tag\": \"foo\"}, \"sample_rate\": 1}]'                                                                                                                                 |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "2tags * keep        | '[{\"tags\": {\"env\": \"*x\"}, \"sample_rate\": 0}, {\"tags\": {\"env\": \"*\", \"tag\": \"*\"}, \"sample_rate\": 1}]'                                                                                                                                      |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "2tags b?r+f?? keep  | '[{\"tags\": {\"env\": \"x??\"}, \"sample_rate\": 0}, {\"tags\": {\"env\": \"b?r\", \"tag\": \"f??\"}, \"sample_rate\": 1}]'                                                                                                                                 |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  ",
    "2tags b?r+f?? drop  | '[{\"tags\": {\"env\": \"x??\"}, \"sample_rate\": 1}, {\"tags\": {\"env\": \"b?r\", \"tag\": \"f??\"}, \"sample_rate\": 0}]'                                                                                                                                 |             |                                   | PrioritySampling.USER_DROP    | 0.0              |                   |                  ",
    "all combined        | '[{\"service\": \"service\", \"resource\": \"xxx\", \"tags\": {\"env\": \"x??\"}, \"sample_rate\": 0}, {\"service\": \"service\", \"resource\": \"resource\", \"name\": \"operation\", \"tags\": {\"env\": \"b?r\", \"tag\": \"f??\"}, \"sample_rate\": 1}]' |             | SamplingMechanism.LOCAL_USER_RULE | PrioritySampling.USER_KEEP    | 1.0              | 50                |                  "
  })
  void samplingConfigJsonRulesCombinations(
      String jsonRules,
      String defaultRate,
      @ConvertWith(SamplingMechanismConverter.class) Byte expectedDecisionMaker,
      @ConvertWith(PrioritySamplingConverter.class) byte expectedPriority,
      Double expectedRuleRate,
      Integer expectedRateLimit,
      Double expectedAgentRate) {
    Properties properties = new Properties();
    properties.setProperty(TRACE_SAMPLING_RULES, jsonRules);
    if (defaultRate != null) {
      properties.setProperty(TRACE_SAMPLE_RATE, defaultRate);
    }
    properties.setProperty(TRACE_RATE_LIMIT, "50");
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      Sampler sampler = Sampler.Builder.forConfig(properties);
      assertInstanceOf(PrioritySampler.class, sampler);

      DDSpan span =
          (DDSpan)
              tracer
                  .buildSpan("datadog", "operation")
                  .withServiceName("service")
                  .withTag("env", "bar")
                  .withTag("tag", "foo")
                  .withResourceName("resource")
                  .ignoreActiveSpan()
                  .start();
      ((PrioritySampler) sampler).setSamplingPriority(span);

      Map<String, String> propagationMap = span.spanContext().getPropagationTags().createTagMap();
      String decisionMaker = propagationMap.get("_dd.p.dm");
      String expectedDmStr =
          expectedDecisionMaker == null ? null : "-" + (int) expectedDecisionMaker;

      assertTagEquals(expectedRuleRate, span.getTag(RuleBasedTraceSampler.SAMPLING_RULE_RATE));
      assertTagEquals(expectedRateLimit, span.getTag(RuleBasedTraceSampler.SAMPLING_LIMIT_RATE));
      assertTagEquals(
          expectedAgentRate, span.getTag(RateByServiceTraceSampler.SAMPLING_AGENT_RATE));
      assertEquals(expectedPriority, (int) span.getSamplingPriority());
      assertEquals(expectedDmStr, decisionMaker);
    } finally {
      tracer.close();
    }
  }

  @SuppressWarnings("unused")
  @ParameterizedTest(name = "{0}")
  @MethodSource("tagTypesTestArguments")
  void tagTypesTest(String scenario, String tagPattern, Object tagValue, boolean expectedMatch) {
    String json = "[{\"tags\": {\"testTag\": \"" + tagPattern + "\"}, \"sample_rate\": 1}]";
    Properties properties = new Properties();
    properties.setProperty(TRACE_SAMPLING_RULES, json);
    properties.setProperty(TRACE_SAMPLE_RATE, "0");

    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      PrioritySampler sampler = (PrioritySampler) Sampler.Builder.forConfig(properties);

      DDSpan span =
          (DDSpan)
              tracer
                  .buildSpan("datadog", "operation")
                  .withServiceName("service")
                  .withResourceName("resource")
                  .withTag("env", "bar")
                  .ignoreActiveSpan()
                  .start();
      span.setTag("testTag", tagValue);
      sampler.setSamplingPriority(span);

      assertEquals(expectedMatch ? USER_KEEP : (int) USER_DROP, (int) span.getSamplingPriority());
    } finally {
      tracer.close();
    }
  }

  static Stream<Arguments> tagTypesTestArguments() {
    return Stream.of(
        arguments("* anything", "*", "anything...", true),
        arguments("* null", "*", null, false),
        arguments("* StringBuilder", "*", new StringBuilder("foo"), true),
        arguments("* object", "*", new Object(), true),
        arguments("** object", "**", new Object(), true),
        arguments("? object", "?", new Object(), false),
        arguments("* foo", "*", "foo", true),
        arguments("** foo", "**", "foo", true),
        arguments("** true", "**", true, true),
        arguments("** false", "**", false, true),
        arguments("** 20", "**", 20, true),
        arguments("** 20L", "**", 20L, true),
        arguments("** 20.1F", "**", 20.1F, true),
        arguments("** 20.1D", "**", 20.1D, true),
        arguments("** bigInt 20", "**", new BigInteger("20"), true),
        arguments("** bigDec 20.1", "**", new BigDecimal("20.1"), true),
        arguments("foo match", "foo", "foo", true),
        arguments("foo StringBuilder", "foo", new StringBuilder("foo"), true),
        arguments("foo not-foo", "foo", "not-foo", false),
        arguments("ba? bar", "ba?", "bar", true),
        arguments("20 == 20", "20", 20, true),
        arguments("20 == Int 20", "20", 20, true),
        arguments("20 == 20L", "20", 20L, true),
        arguments("20 == Long 20", "20", 20L, true),
        arguments("20 == 20F", "20", 20F, true),
        arguments("20 != 20.1F", "20", 20.1F, false),
        arguments("20.* != 20.1F", "20.*", 20.1F, false),
        arguments("20.1 != 20.1D", "20.1", 20.1D, false),
        arguments("* == 20.1D", "*", 20.1D, true),
        arguments("20 == bigInt 20", "20", new BigInteger("20"), true),
        arguments("20 == bigDec 20", "20", new BigDecimal("20"), true),
        arguments("* == bigDec 20.1", "*", new BigDecimal("20.1"), true));
  }

  @Test
  void preferJsonRulesOverOtherDeprecatedOnes() {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      Properties properties = new Properties();
      properties.setProperty(TRACE_SAMPLING_SERVICE_RULES, "service:0");
      properties.setProperty(TRACE_SAMPLING_OPERATION_RULES, "operation:0");
      properties.setProperty(
          TRACE_SAMPLING_RULES,
          "[{\"service\": \"service\", \"name\": \"operation\", \"sample_rate\": 1.0}]");
      properties.setProperty(TRACE_RATE_LIMIT, "1");
      Sampler sampler = Sampler.Builder.forConfig(properties);

      DDSpan span1 =
          (DDSpan)
              tracer
                  .buildSpan("datadog", "operation")
                  .withServiceName("service")
                  .withTag("env", "bar")
                  .ignoreActiveSpan()
                  .start();
      DDSpan span2 =
          (DDSpan)
              tracer
                  .buildSpan("datadog", "operation")
                  .withServiceName("service")
                  .withTag("env", "bar")
                  .ignoreActiveSpan()
                  .start();

      ((PrioritySampler) sampler).setSamplingPriority(span1);
      // Span 2 should be rate limited if there isn't a >1 sec delay between these 2 lines
      ((PrioritySampler) sampler).setSamplingPriority(span2);

      assertTagEquals(1.0, span1.getTag(RuleBasedTraceSampler.SAMPLING_RULE_RATE));
      assertTagEquals(1.0, span1.getTag(RuleBasedTraceSampler.SAMPLING_LIMIT_RATE));
      assertNull(span1.getTag(RateByServiceTraceSampler.SAMPLING_AGENT_RATE));
      assertEquals(USER_KEEP, (int) span1.getSamplingPriority());

      assertTagEquals(1.0, span2.getTag(RuleBasedTraceSampler.SAMPLING_RULE_RATE));
      assertTagEquals(1.0, span2.getTag(RuleBasedTraceSampler.SAMPLING_LIMIT_RATE));
      assertNull(span2.getTag(RateByServiceTraceSampler.SAMPLING_AGENT_RATE));
      assertEquals(USER_DROP, (int) span2.getSamplingPriority());
    } finally {
      tracer.close();
    }
  }

  @Test
  void rateLimitIsSetForRateLimitedSpans() {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      Properties properties = new Properties();
      properties.setProperty(TRACE_SAMPLING_SERVICE_RULES, "service:1");
      properties.setProperty(TRACE_RATE_LIMIT, "1");
      Sampler sampler = Sampler.Builder.forConfig(properties);

      DDSpan span1 =
          (DDSpan)
              tracer
                  .buildSpan("datadog", "operation")
                  .withServiceName("service")
                  .withTag("env", "bar")
                  .ignoreActiveSpan()
                  .start();
      DDSpan span2 =
          (DDSpan)
              tracer
                  .buildSpan("datadog", "operation")
                  .withServiceName("service")
                  .withTag("env", "bar")
                  .ignoreActiveSpan()
                  .start();

      ((PrioritySampler) sampler).setSamplingPriority(span1);
      // Span 2 should be rate limited if there isn't a >1 sec delay between these 2 lines
      ((PrioritySampler) sampler).setSamplingPriority(span2);

      assertTagEquals(1.0, span1.getTag(RuleBasedTraceSampler.SAMPLING_RULE_RATE));
      assertTagEquals(1.0, span1.getTag(RuleBasedTraceSampler.SAMPLING_LIMIT_RATE));
      assertNull(span1.getTag(RateByServiceTraceSampler.SAMPLING_AGENT_RATE));
      assertEquals(USER_KEEP, (int) span1.getSamplingPriority());

      assertTagEquals(1.0, span2.getTag(RuleBasedTraceSampler.SAMPLING_RULE_RATE));
      assertTagEquals(1.0, span2.getTag(RuleBasedTraceSampler.SAMPLING_LIMIT_RATE));
      assertNull(span2.getTag(RateByServiceTraceSampler.SAMPLING_AGENT_RATE));
      assertEquals(USER_DROP, (int) span2.getSamplingPriority());
    } finally {
      tracer.close();
    }
  }

  @Test
  void rateLimitIsSetForRateLimitedSpansMatchedOnDifferentRules() {
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      Properties properties = new Properties();
      properties.setProperty(TRACE_SAMPLING_SERVICE_RULES, "service:1,foo:1");
      properties.setProperty(TRACE_RATE_LIMIT, "1");
      Sampler sampler = Sampler.Builder.forConfig(properties);

      DDSpan span1 =
          (DDSpan)
              tracer
                  .buildSpan("datadog", "operation")
                  .withServiceName("service")
                  .withTag("env", "bar")
                  .ignoreActiveSpan()
                  .start();
      DDSpan span2 =
          (DDSpan)
              tracer
                  .buildSpan("datadog", "operation")
                  .withServiceName("foo")
                  .withTag("env", "bar")
                  .ignoreActiveSpan()
                  .start();

      ((PrioritySampler) sampler).setSamplingPriority(span1);
      // Span 2 should be rate limited if there isn't a >1 sec delay between these 2 lines
      ((PrioritySampler) sampler).setSamplingPriority(span2);

      assertTagEquals(1.0, span1.getTag(RuleBasedTraceSampler.SAMPLING_RULE_RATE));
      assertTagEquals(1.0, span1.getTag(RuleBasedTraceSampler.SAMPLING_LIMIT_RATE));
      assertNull(span1.getTag(RateByServiceTraceSampler.SAMPLING_AGENT_RATE));
      assertEquals(USER_KEEP, (int) span1.getSamplingPriority());

      assertTagEquals(1.0, span2.getTag(RuleBasedTraceSampler.SAMPLING_RULE_RATE));
      assertTagEquals(1.0, span2.getTag(RuleBasedTraceSampler.SAMPLING_LIMIT_RATE));
      assertNull(span2.getTag(RateByServiceTraceSampler.SAMPLING_AGENT_RATE));
      assertEquals(USER_DROP, (int) span2.getSamplingPriority());
    } finally {
      tracer.close();
    }
  }

  private static void assertTagEquals(Number expected, Object actual) {
    if (expected == null) {
      assertNull(actual, "Expected tag to be null");
    } else {
      assertNotNull(actual, "Expected tag to be non-null");
      assertEquals(expected.doubleValue(), ((Number) actual).doubleValue(), 1e-9);
    }
  }
}
