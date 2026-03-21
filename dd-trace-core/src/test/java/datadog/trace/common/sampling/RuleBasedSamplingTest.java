package datadog.trace.common.sampling;

import static datadog.trace.api.config.TracerConfig.TRACE_RATE_LIMIT;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLE_RATE;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_OPERATION_RULES;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_RULES;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_SERVICE_RULES;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.api.sampling.SamplingMechanism.AGENT_RATE;
import static datadog.trace.api.sampling.SamplingMechanism.LOCAL_USER_RULE;
import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.test.DDCoreSpecification;
import java.math.BigInteger;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class RuleBasedSamplingTest extends DDCoreSpecification {

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

  @ParameterizedTest
  @MethodSource("samplingConfigCombinationsArguments")
  void samplingConfigCombinations(
      String serviceRules,
      String operationRules,
      String defaultRate,
      Integer expectedDecisionMaker,
      int expectedPriority,
      Object expectedRuleRate,
      Object expectedRateLimit,
      Object expectedAgentRate) {
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
      assertTrue(sampler instanceof PrioritySampler);

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
      String decisionMaker = propagationMap.get("_dd.p.dm");
      String expectedDmStr = (expectedDecisionMaker == null) ? null : "-" + expectedDecisionMaker;

      assertEquals(expectedRuleRate, span.getTag(RuleBasedTraceSampler.SAMPLING_RULE_RATE));
      assertEquals(expectedRateLimit, span.getTag(RuleBasedTraceSampler.SAMPLING_LIMIT_RATE));
      assertEquals(expectedAgentRate, span.getTag(RateByServiceTraceSampler.SAMPLING_AGENT_RATE));
      assertEquals(expectedPriority, span.getSamplingPriority().intValue());
      assertEquals(expectedDmStr, decisionMaker);
    } finally {
      tracer.close();
    }
  }

  static Stream<Arguments> samplingConfigCombinationsArguments() {
    return Stream.of(
        // Matching neither passes through to rate based sampler
        Arguments.of("xx:1", null, null, (int) AGENT_RATE, (int) SAMPLER_KEEP, null, null, 1.0),
        Arguments.of(null, "xx:1", null, (int) AGENT_RATE, (int) SAMPLER_KEEP, null, null, 1.0),
        // Matching neither with default rate
        Arguments.of(null, null, "1", (int) LOCAL_USER_RULE, (int) USER_KEEP, 1.0, 50L, null),
        Arguments.of(null, null, "0", null, (int) USER_DROP, 0.0, null, null),
        Arguments.of("xx:1", null, "1", (int) LOCAL_USER_RULE, (int) USER_KEEP, 1.0, 50L, null),
        Arguments.of(null, "xx:1", "1", (int) LOCAL_USER_RULE, (int) USER_KEEP, 1.0, 50L, null),
        Arguments.of("xx:1", null, "0", null, (int) USER_DROP, 0.0, null, null),
        Arguments.of(null, "xx:1", "0", null, (int) USER_DROP, 0.0, null, null),
        // Matching service: keep
        Arguments.of(
            "service:1", null, null, (int) LOCAL_USER_RULE, (int) USER_KEEP, 1.0, 50L, null),
        Arguments.of("s.*:1", null, null, (int) LOCAL_USER_RULE, (int) USER_KEEP, 1.0, 50L, null),
        Arguments.of(".*e:1", null, null, (int) LOCAL_USER_RULE, (int) USER_KEEP, 1.0, 50L, null),
        // Matching service: drop
        Arguments.of("service:0", null, null, null, (int) USER_DROP, 0.0, null, null),
        Arguments.of("s.*:0", null, null, null, (int) USER_DROP, 0.0, null, null),
        Arguments.of(".*e:0", null, null, null, (int) USER_DROP, 0.0, null, null),
        // Matching service overrides default rate
        Arguments.of(
            "service:1", null, "0", (int) LOCAL_USER_RULE, (int) USER_KEEP, 1.0, 50L, null),
        Arguments.of("service:0", null, "1", null, (int) USER_DROP, 0.0, null, null),
        // multiple services
        Arguments.of(
            "xxx:0,service:1", null, null, (int) LOCAL_USER_RULE, (int) USER_KEEP, 1.0, 50L, null),
        Arguments.of("xxx:1,service:0", null, null, null, (int) USER_DROP, 0.0, null, null),
        // Matching operation: keep
        Arguments.of(
            null, "operation:1", null, (int) LOCAL_USER_RULE, (int) USER_KEEP, 1.0, 50L, null),
        Arguments.of(null, "o.*:1", null, (int) LOCAL_USER_RULE, (int) USER_KEEP, 1.0, 50L, null),
        Arguments.of(null, ".*n:1", null, (int) LOCAL_USER_RULE, (int) USER_KEEP, 1.0, 50L, null),
        // Matching operation: drop
        Arguments.of(null, "operation:0", null, null, (int) USER_DROP, 0.0, null, null),
        Arguments.of(null, "o.*:0", null, null, (int) USER_DROP, 0.0, null, null),
        Arguments.of(null, ".*n:0", null, null, (int) USER_DROP, 0.0, null, null),
        // Matching operation overrides default rate
        Arguments.of(
            null, "operation:1", "0", (int) LOCAL_USER_RULE, (int) USER_KEEP, 1.0, 50L, null),
        Arguments.of(null, "operation:0", "1", null, (int) USER_DROP, 0.0, null, null),
        // multiple operation combinations
        Arguments.of(
            null,
            "xxx:0,operation:1",
            null,
            (int) LOCAL_USER_RULE,
            (int) USER_KEEP,
            1.0,
            50L,
            null),
        Arguments.of(null, "xxx:1,operation:0", null, null, (int) USER_DROP, 0.0, null, null),
        // Service and operation name combinations
        Arguments.of(
            "service:1",
            "operation:0",
            null,
            (int) LOCAL_USER_RULE,
            (int) USER_KEEP,
            1.0,
            50L,
            null),
        Arguments.of(
            "service:1", "xxx:0", null, (int) LOCAL_USER_RULE, (int) USER_KEEP, 1.0, 50L, null),
        Arguments.of("service:0", "operation:1", null, null, (int) USER_DROP, 0.0, null, null),
        Arguments.of("service:0", "xxx:1", null, null, (int) USER_DROP, 0.0, null, null),
        Arguments.of(
            "xxx:0", "operation:1", null, (int) LOCAL_USER_RULE, (int) USER_KEEP, 1.0, 50L, null),
        Arguments.of("xxx:1", "operation:0", null, null, (int) USER_DROP, 0.0, null, null));
  }

  @ParameterizedTest
  @MethodSource("samplingConfigJsonRulesCombinationsArguments")
  void samplingConfigJsonRulesCombinations(
      String jsonRules,
      String defaultRate,
      Integer expectedDecisionMaker,
      int expectedPriority,
      Object expectedRuleRate,
      Object expectedRateLimit,
      Object expectedAgentRate) {
    Properties properties = new Properties();
    properties.setProperty(TRACE_SAMPLING_RULES, jsonRules);
    if (defaultRate != null) {
      properties.setProperty(TRACE_SAMPLE_RATE, defaultRate);
    }
    properties.setProperty(TRACE_RATE_LIMIT, "50");
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      Sampler sampler = Sampler.Builder.forConfig(properties);
      assertTrue(sampler instanceof PrioritySampler);

      DDSpan span =
          (DDSpan)
              tracer
                  .buildSpan("operation")
                  .withServiceName("service")
                  .withTag("env", "bar")
                  .withTag("tag", "foo")
                  .withResourceName("resource")
                  .ignoreActiveSpan()
                  .start();
      ((PrioritySampler) sampler).setSamplingPriority(span);

      Map<String, String> propagationMap = span.context().getPropagationTags().createTagMap();
      String decisionMaker = propagationMap.get("_dd.p.dm");
      String expectedDmStr = (expectedDecisionMaker == null) ? null : "-" + expectedDecisionMaker;

      assertEquals(expectedRuleRate, span.getTag(RuleBasedTraceSampler.SAMPLING_RULE_RATE));
      assertEquals(expectedRateLimit, span.getTag(RuleBasedTraceSampler.SAMPLING_LIMIT_RATE));
      assertEquals(expectedAgentRate, span.getTag(RateByServiceTraceSampler.SAMPLING_AGENT_RATE));
      assertEquals(expectedPriority, span.getSamplingPriority().intValue());
      assertEquals(expectedDmStr, decisionMaker);
    } finally {
      tracer.close();
    }
  }

  static Stream<Arguments> samplingConfigJsonRulesCombinationsArguments() {
    return Stream.of(
        // Matching neither passes through to rate based sampler
        Arguments.of(
            "[{\"service\": \"xx\", \"sample_rate\": 1}]",
            null,
            (int) AGENT_RATE,
            (int) SAMPLER_KEEP,
            null,
            null,
            1.0),
        Arguments.of(
            "[{\"name\": \"xx\", \"sample_rate\": 1}]",
            null,
            (int) AGENT_RATE,
            (int) SAMPLER_KEEP,
            null,
            null,
            1.0),
        // Matching neither with default rate
        Arguments.of(
            "[{\"sample_rate\": 1}]", "1", (int) LOCAL_USER_RULE, (int) USER_KEEP, 1.0, 50L, null),
        Arguments.of("[{\"sample_rate\": 0}]", "0", null, (int) USER_DROP, 0.0, null, null),
        Arguments.of("[]", "0", null, (int) USER_DROP, 0.0, null, null),
        Arguments.of(
            "[{\"service\": \"xx\", \"sample_rate\": 1}]",
            "1",
            (int) LOCAL_USER_RULE,
            (int) USER_KEEP,
            1.0,
            50L,
            null),
        Arguments.of(
            "[{\"name\": \"xx\", \"sample_rate\": 1}]",
            "1",
            (int) LOCAL_USER_RULE,
            (int) USER_KEEP,
            1.0,
            50L,
            null),
        Arguments.of(
            "[{\"service\": \"xx\", \"sample_rate\": 1}]",
            "0",
            null,
            (int) USER_DROP,
            0.0,
            null,
            null),
        Arguments.of(
            "[{\"name\": \"xx\", \"sample_rate\": 1}]",
            "0",
            null,
            (int) USER_DROP,
            0.0,
            null,
            null),
        // Matching service: keep
        Arguments.of(
            "[{\"service\": \"service\", \"sample_rate\": 1}]",
            null,
            (int) LOCAL_USER_RULE,
            (int) USER_KEEP,
            1.0,
            50L,
            null),
        // Matching service: drop
        Arguments.of(
            "[{\"service\": \"service\", \"sample_rate\": 0}]",
            null,
            null,
            (int) USER_DROP,
            0.0,
            null,
            null),
        // Matching service overrides default rate
        Arguments.of(
            "[{\"service\": \"service\", \"sample_rate\": 1}]",
            "0",
            (int) LOCAL_USER_RULE,
            (int) USER_KEEP,
            1.0,
            50L,
            null),
        Arguments.of(
            "[{\"service\": \"service\", \"sample_rate\": 0}]",
            "1",
            null,
            (int) USER_DROP,
            0.0,
            null,
            null),
        // multiple services
        Arguments.of(
            "[{\"service\": \"xxx\", \"sample_rate\": 0}, {\"service\": \"service\", \"sample_rate\": 1}]",
            null,
            (int) LOCAL_USER_RULE,
            (int) USER_KEEP,
            1.0,
            50L,
            null),
        Arguments.of(
            "[{\"service\": \"xxx\", \"sample_rate\": 1}, {\"service\": \"service\", \"sample_rate\": 0}]",
            null,
            null,
            (int) USER_DROP,
            0.0,
            null,
            null),
        // Matching operation: keep
        Arguments.of(
            "[{\"name\": \"operation\", \"sample_rate\": 1}]",
            null,
            (int) LOCAL_USER_RULE,
            (int) USER_KEEP,
            1.0,
            50L,
            null),
        // Matching operation: drop
        Arguments.of(
            "[{\"name\": \"operation\", \"sample_rate\": 0}]",
            null,
            null,
            (int) USER_DROP,
            0.0,
            null,
            null),
        // Matching operation overrides default rate
        Arguments.of(
            "[{\"name\": \"operation\", \"sample_rate\": 1}]",
            "0",
            (int) LOCAL_USER_RULE,
            (int) USER_KEEP,
            1.0,
            50L,
            null),
        Arguments.of(
            "[{\"name\": \"operation\", \"sample_rate\": 0}]",
            "1",
            null,
            (int) USER_DROP,
            0.0,
            null,
            null),
        // multiple operation combinations
        Arguments.of(
            "[{\"name\": \"xxx\", \"sample_rate\": 0}, {\"name\": \"operation\", \"sample_rate\": 1}]",
            null,
            (int) LOCAL_USER_RULE,
            (int) USER_KEEP,
            1.0,
            50L,
            null),
        Arguments.of(
            "[{\"name\": \"xxx\", \"sample_rate\": 1}, {\"name\": \"operation\", \"sample_rate\": 0}]",
            null,
            null,
            (int) USER_DROP,
            0.0,
            null,
            null),
        // Matching resource: keep
        Arguments.of(
            "[{\"resource\": \"resource\", \"sample_rate\": 1}]",
            null,
            (int) LOCAL_USER_RULE,
            (int) USER_KEEP,
            1.0,
            50L,
            null),
        // Matching resource: drop
        Arguments.of(
            "[{\"resource\": \"resource\", \"sample_rate\": 0}]",
            null,
            null,
            (int) USER_DROP,
            0.0,
            null,
            null),
        // Matching resource overrides default rate
        Arguments.of(
            "[{\"resource\": \"resource\", \"sample_rate\": 1}]",
            "0",
            (int) LOCAL_USER_RULE,
            (int) USER_KEEP,
            1.0,
            50L,
            null),
        Arguments.of(
            "[{\"resource\": \"resource\", \"sample_rate\": 0}]",
            "1",
            null,
            (int) USER_DROP,
            0.0,
            null,
            null),
        // Multiple resource combinations
        Arguments.of(
            "[{\"resource\": \"xxx\", \"sample_rate\": 0}, {\"resource\": \"resource\", \"sample_rate\": 1}]",
            null,
            (int) LOCAL_USER_RULE,
            (int) USER_KEEP,
            1.0,
            50L,
            null),
        Arguments.of(
            "[{\"resource\": \"xxx\", \"sample_rate\": 1}, {\"resource\": \"resource\", \"sample_rate\": 0}]",
            null,
            null,
            (int) USER_DROP,
            0.0,
            null,
            null),
        // Select matching service + operation rules
        Arguments.of(
            "[{\"service\": \"service\", \"sample_rate\": 1}, {\"name\": \"operation\", \"sample_rate\": 0}]",
            null,
            (int) LOCAL_USER_RULE,
            (int) USER_KEEP,
            1.0,
            50L,
            null),
        Arguments.of(
            "[{\"service\": \"service\", \"sample_rate\": 1}, {\"name\": \"xxx\", \"sample_rate\": 0}]",
            null,
            (int) LOCAL_USER_RULE,
            (int) USER_KEEP,
            1.0,
            50L,
            null),
        Arguments.of(
            "[{\"service\": \"service\", \"sample_rate\": 0}, {\"name\": \"operation\", \"sample_rate\": 1}]",
            null,
            null,
            (int) USER_DROP,
            0.0,
            null,
            null),
        Arguments.of(
            "[{\"service\": \"service\", \"sample_rate\": 0}, {\"name\": \"xxx\", \"sample_rate\": 1}]",
            null,
            null,
            (int) USER_DROP,
            0.0,
            null,
            null),
        Arguments.of(
            "[{\"service\": \"xxx\", \"sample_rate\": 0}, {\"name\": \"operation\", \"sample_rate\": 1}]",
            null,
            (int) LOCAL_USER_RULE,
            (int) USER_KEEP,
            1.0,
            50L,
            null),
        Arguments.of(
            "[{\"service\": \"xxx\", \"sample_rate\": 1}, {\"name\": \"operation\", \"sample_rate\": 0}]",
            null,
            null,
            (int) USER_DROP,
            0.0,
            null,
            null),
        // Select matching service + operation rules (combined)
        Arguments.of(
            "[{\"service\": \"service\", \"name\": \"operation\", \"sample_rate\": 1}]",
            null,
            (int) LOCAL_USER_RULE,
            (int) USER_KEEP,
            1.0,
            50L,
            null),
        Arguments.of(
            "[{\"service\": \"service\", \"name\": \"xxx\", \"sample_rate\": 0}, {\"service\": \"service\", \"name\": \"operation\", \"sample_rate\": 1}]",
            null,
            (int) LOCAL_USER_RULE,
            (int) USER_KEEP,
            1.0,
            50L,
            null),
        Arguments.of(
            "[{\"service\": \"service\", \"name\": \"xxx\", \"sample_rate\": 0}, {\"service\": \"service\", \"sample_rate\": 1}]",
            null,
            (int) LOCAL_USER_RULE,
            (int) USER_KEEP,
            1.0,
            50L,
            null),
        Arguments.of(
            "[{\"service\": \"service\", \"name\": \"xxx\", \"sample_rate\": 0}, {\"name\": \"operation\", \"sample_rate\": 1}]",
            null,
            (int) LOCAL_USER_RULE,
            (int) USER_KEEP,
            1.0,
            50L,
            null),
        Arguments.of(
            "[{\"service\": \"service\", \"resource\": \"xxx\", \"sample_rate\": 0}, {\"resource\": \"resource\", \"sample_rate\": 1}]",
            null,
            (int) LOCAL_USER_RULE,
            (int) USER_KEEP,
            1.0,
            50L,
            null),
        Arguments.of(
            "[{\"service\": \"service\", \"name\": \"operation\", \"sample_rate\": 0}, {\"service\": \"service\", \"name\": \"operation\", \"sample_rate\": 1}]",
            null,
            null,
            (int) USER_DROP,
            0.0,
            null,
            null),
        Arguments.of(
            "[{\"service\": \"service\", \"name\": \"operation\", \"sample_rate\": 0}]",
            null,
            null,
            (int) USER_DROP,
            0.0,
            null,
            null),
        // Select matching service + resource
        Arguments.of(
            "[{\"service\": \"service\", \"resource\": \"xxx\", \"sample_rate\": 0}, {\"service\": \"service\", \"resource\": \"resource\", \"sample_rate\": 1}]",
            null,
            (int) LOCAL_USER_RULE,
            (int) USER_KEEP,
            1.0,
            50L,
            null),
        // Select matching service + resource + operation rules
        Arguments.of(
            "[{\"service\": \"service\", \"resource\": \"xxx\", \"sample_rate\": 0}, {\"service\": \"service\", \"resource\": \"resource\", \"name\": \"operation\", \"sample_rate\": 1}]",
            null,
            (int) LOCAL_USER_RULE,
            (int) USER_KEEP,
            1.0,
            50L,
            null),
        // Select matching single tag rules
        Arguments.of(
            "[{\"tags\": {\"env\": \"xxx\"}, \"sample_rate\": 0}, {\"tags\": {\"env\": \"bar\"}, \"sample_rate\": 1}]",
            null,
            (int) LOCAL_USER_RULE,
            (int) USER_KEEP,
            1.0,
            50L,
            null),
        Arguments.of(
            "[{\"tags\": {\"env\": \"*x\"}, \"sample_rate\": 0}, {\"tags\": {\"env\": \"*\"}, \"sample_rate\": 1}]",
            null,
            (int) LOCAL_USER_RULE,
            (int) USER_KEEP,
            1.0,
            50L,
            null),
        Arguments.of(
            "[{\"tags\": {\"env\": \"x??\"}, \"sample_rate\": 0}, {\"tags\": {\"env\": \"b?r\"}, \"sample_rate\": 1}]",
            null,
            (int) LOCAL_USER_RULE,
            (int) USER_KEEP,
            1.0,
            50L,
            null),
        Arguments.of(
            "[{\"tags\": {\"env\": \"x??\"}, \"sample_rate\": 1}, {\"tags\": {\"env\": \"b?r\"}, \"sample_rate\": 0}]",
            null,
            null,
            (int) USER_DROP,
            0.0,
            null,
            null),
        // Select matching two tags rules
        Arguments.of(
            "[{\"tags\": {\"env\": \"xxx\"}, \"sample_rate\": 0}, {\"tags\": {\"env\": \"bar\", \"tag\": \"foo\"}, \"sample_rate\": 1}]",
            null,
            (int) LOCAL_USER_RULE,
            (int) USER_KEEP,
            1.0,
            50L,
            null),
        Arguments.of(
            "[{\"tags\": {\"env\": \"*x\"}, \"sample_rate\": 0}, {\"tags\": {\"env\": \"*\", \"tag\": \"*\"}, \"sample_rate\": 1}]",
            null,
            (int) LOCAL_USER_RULE,
            (int) USER_KEEP,
            1.0,
            50L,
            null),
        Arguments.of(
            "[{\"tags\": {\"env\": \"x??\"}, \"sample_rate\": 0}, {\"tags\": {\"env\": \"b?r\", \"tag\": \"f??\"}, \"sample_rate\": 1}]",
            null,
            (int) LOCAL_USER_RULE,
            (int) USER_KEEP,
            1.0,
            50L,
            null),
        Arguments.of(
            "[{\"tags\": {\"env\": \"x??\"}, \"sample_rate\": 1}, {\"tags\": {\"env\": \"b?r\", \"tag\": \"f??\"}, \"sample_rate\": 0}]",
            null,
            null,
            (int) USER_DROP,
            0.0,
            null,
            null),
        // Select matching service + resource + operation + tag rules
        Arguments.of(
            "[{\"service\": \"service\", \"resource\": \"xxx\", \"tags\": {\"env\": \"x??\"}, \"sample_rate\": 0}, {\"service\": \"service\", \"resource\": \"resource\", \"name\": \"operation\", \"tags\": {\"env\": \"b?r\", \"tag\": \"f??\"}, \"sample_rate\": 1}]",
            null,
            (int) LOCAL_USER_RULE,
            (int) USER_KEEP,
            1.0,
            50L,
            null));
  }

  @ParameterizedTest
  @MethodSource("tagTypesTestArguments")
  void tagTypesTest(String tagPattern, Object tagValue, boolean expectedMatch) {
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
                  .buildSpan("operation")
                  .withServiceName("service")
                  .withResourceName("resource")
                  .withTag("env", "bar")
                  .ignoreActiveSpan()
                  .start();

      if (tagValue == null) {
        span.setTag("testTag", (String) null);
      } else if (tagValue instanceof Boolean) {
        span.setTag("testTag", (Boolean) tagValue);
      } else if (tagValue instanceof Number) {
        span.setTag("testTag", (Number) tagValue);
      } else {
        span.setTag("testTag", tagValue.toString());
      }

      sampler.setSamplingPriority(span);

      assertEquals(
          expectedMatch ? (int) USER_KEEP : (int) USER_DROP, span.getSamplingPriority().intValue());
    } finally {
      tracer.close();
    }
  }

  static Stream<Arguments> tagTypesTestArguments() {
    return Stream.of(
        Arguments.of("*", "anything...", true),
        Arguments.of("*", null, false),
        Arguments.of("*", new StringBuilder("foo"), true),
        Arguments.of("*", object(), true),
        Arguments.of("**", object(), true),
        Arguments.of("?", object(), false),
        Arguments.of("*", "foo", true),
        Arguments.of("**", "foo", true),
        Arguments.of("**", true, true),
        Arguments.of("**", false, true),
        Arguments.of("**", 20, true),
        Arguments.of("**", 20L, true),
        Arguments.of("**", 20.1F, true),
        Arguments.of("**", 20.1D, true),
        Arguments.of("**", bigInteger("20"), true),
        Arguments.of("**", bigDecimal("20.1"), true),
        Arguments.of("foo", "foo", true),
        Arguments.of("foo", new StringBuilder("foo"), true),
        Arguments.of("foo", "not-foo", false),
        Arguments.of("ba?", "bar", true),
        Arguments.of("20", 20, true),
        Arguments.of("20", Integer.valueOf(20), true),
        Arguments.of("20", 20L, true),
        Arguments.of("20", Long.valueOf(20), true),
        Arguments.of("20", 20F, true),
        Arguments.of("20", 20.1F, false),
        Arguments.of("20.*", 20.1F, false),
        Arguments.of("20.1", 20.1D, false),
        Arguments.of("*", 20.1D, true),
        Arguments.of("20", bigInteger("20"), true),
        Arguments.of("20", bigDecimal("20"), true),
        Arguments.of("*", bigDecimal("20.1"), true));
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
                  .buildSpan("operation")
                  .withServiceName("service")
                  .withTag("env", "bar")
                  .ignoreActiveSpan()
                  .start();

      DDSpan span2 =
          (DDSpan)
              tracer
                  .buildSpan("operation")
                  .withServiceName("service")
                  .withTag("env", "bar")
                  .ignoreActiveSpan()
                  .start();

      ((PrioritySampler) sampler).setSamplingPriority(span1);
      ((PrioritySampler) sampler).setSamplingPriority(span2);

      assertEquals(1.0, span1.getTag(RuleBasedTraceSampler.SAMPLING_RULE_RATE));
      assertEquals(1L, span1.getTag(RuleBasedTraceSampler.SAMPLING_LIMIT_RATE));
      assertNull(span1.getTag(RateByServiceTraceSampler.SAMPLING_AGENT_RATE));
      assertEquals((int) USER_KEEP, span1.getSamplingPriority().intValue());

      assertEquals(1.0, span2.getTag(RuleBasedTraceSampler.SAMPLING_RULE_RATE));
      assertEquals(1L, span2.getTag(RuleBasedTraceSampler.SAMPLING_LIMIT_RATE));
      assertNull(span2.getTag(RateByServiceTraceSampler.SAMPLING_AGENT_RATE));
      assertEquals((int) USER_DROP, span2.getSamplingPriority().intValue());
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
                  .buildSpan("operation")
                  .withServiceName("service")
                  .withTag("env", "bar")
                  .ignoreActiveSpan()
                  .start();

      DDSpan span2 =
          (DDSpan)
              tracer
                  .buildSpan("operation")
                  .withServiceName("service")
                  .withTag("env", "bar")
                  .ignoreActiveSpan()
                  .start();

      ((PrioritySampler) sampler).setSamplingPriority(span1);
      ((PrioritySampler) sampler).setSamplingPriority(span2);

      assertEquals(1.0, span1.getTag(RuleBasedTraceSampler.SAMPLING_RULE_RATE));
      assertEquals(1L, span1.getTag(RuleBasedTraceSampler.SAMPLING_LIMIT_RATE));
      assertNull(span1.getTag(RateByServiceTraceSampler.SAMPLING_AGENT_RATE));
      assertEquals((int) USER_KEEP, span1.getSamplingPriority().intValue());

      assertEquals(1.0, span2.getTag(RuleBasedTraceSampler.SAMPLING_RULE_RATE));
      assertEquals(1L, span2.getTag(RuleBasedTraceSampler.SAMPLING_LIMIT_RATE));
      assertNull(span2.getTag(RateByServiceTraceSampler.SAMPLING_AGENT_RATE));
      assertEquals((int) USER_DROP, span2.getSamplingPriority().intValue());
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
                  .buildSpan("operation")
                  .withServiceName("service")
                  .withTag("env", "bar")
                  .ignoreActiveSpan()
                  .start();
      DDSpan span2 =
          (DDSpan)
              tracer
                  .buildSpan("operation")
                  .withServiceName("foo")
                  .withTag("env", "bar")
                  .ignoreActiveSpan()
                  .start();

      ((PrioritySampler) sampler).setSamplingPriority(span1);
      ((PrioritySampler) sampler).setSamplingPriority(span2);

      assertEquals(1.0, span1.getTag(RuleBasedTraceSampler.SAMPLING_RULE_RATE));
      assertEquals(1L, span1.getTag(RuleBasedTraceSampler.SAMPLING_LIMIT_RATE));
      assertNull(span1.getTag(RateByServiceTraceSampler.SAMPLING_AGENT_RATE));
      assertEquals((int) USER_KEEP, span1.getSamplingPriority().intValue());

      assertEquals(1.0, span2.getTag(RuleBasedTraceSampler.SAMPLING_RULE_RATE));
      assertEquals(1L, span2.getTag(RuleBasedTraceSampler.SAMPLING_LIMIT_RATE));
      assertNull(span2.getTag(RateByServiceTraceSampler.SAMPLING_AGENT_RATE));
      assertEquals((int) USER_DROP, span2.getSamplingPriority().intValue());
    } finally {
      tracer.close();
    }
  }

  static BigInteger bigInteger(String str) {
    return new BigInteger(str);
  }

  static java.math.BigDecimal bigDecimal(String str) {
    return new java.math.BigDecimal(str);
  }

  static Object object() {
    return new Object() {
      @Override
      public String toString() {
        return "object";
      }
    };
  }
}
