package datadog.trace.common.sampling;

import static datadog.trace.api.config.TracerConfig.SPAN_SAMPLING_RULES;
import static datadog.trace.api.config.TracerConfig.SPAN_SAMPLING_RULES_FILE;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLE_RATE;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.SamplingMechanism.DEFAULT;
import static datadog.trace.api.sampling.SamplingMechanism.SPAN_SAMPLING_RATE;
import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.api.Config;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.test.DDCoreSpecification;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class SingleSpanSamplerTest extends DDCoreSpecification {

  @ParameterizedTest
  @MethodSource("notCreatedWhenNoRulesProvidedArguments")
  void singleSpanSamplerIsNotCreatedWhenNoRulesProvided(String rules) {
    Properties properties = new Properties();
    if (rules != null) {
      properties.setProperty(SPAN_SAMPLING_RULES, rules);
    }

    SingleSpanSampler sampler = SingleSpanSampler.Builder.forConfig(Config.get(properties));

    assertNull(sampler);
  }

  static Stream<Arguments> notCreatedWhenNoRulesProvidedArguments() {
    return Stream.of(
        Arguments.of((Object) null),
        Arguments.of("[]"),
        Arguments.of("[ { \"service\": \"*\", \"name\": \"*\", \"sample_rate\": 10.0 } ]"),
        Arguments.of("[ { \"service\": \"*\", \"name\": \"*\", \"sample_rate\": \"all\" } ]"),
        Arguments.of(
            "[ { \"service\": \"*\", \"name\": \"*\", \"sample_rate\": 1.0, \"max_per_second\": \"N/A\" } ]"));
  }

  @ParameterizedTest
  @MethodSource("setSamplingPriorityArguments")
  void singleSpanSamplerSetSamplingPriority(
      String rules,
      boolean isFirstSampled,
      Object expectedMechanism,
      Object expectedRate,
      Object expectedLimit) {
    Properties properties = new Properties();
    if (rules != null) {
      properties.setProperty(SPAN_SAMPLING_RULES, rules);
    }
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      SingleSpanSampler sampler = SingleSpanSampler.Builder.forConfig(Config.get(properties));

      DDSpan span =
          (DDSpan)
              tracer
                  .buildSpan("operation")
                  .withServiceName("service")
                  .withTag("env", "bar")
                  .ignoreActiveSpan()
                  .start();

      assertEquals(isFirstSampled, sampler.setSamplingPriority(span));

      assertEquals(expectedMechanism, span.getTag("_dd.span_sampling.mechanism"));
      assertEquals(expectedRate, span.getTag("_dd.span_sampling.rule_rate"));
      assertEquals(expectedLimit, span.getTag("_dd.span_sampling.max_per_second"));
    } finally {
      tracer.close();
    }
  }

  static Stream<Arguments> setSamplingPriorityArguments() {
    return Stream.of(
        Arguments.of(
            "[ { \"service\": \"*\", \"name\": \"*\", \"sample_rate\": 1.0 } ]",
            true,
            (int) SPAN_SAMPLING_RATE,
            1.0,
            null),
        Arguments.of(
            "[ { \"service\": \"*\", \"name\": \"*\", \"sample_rate\": 1.0, \"max_per_second\": 10 } ]",
            true,
            (int) SPAN_SAMPLING_RATE,
            1.0,
            10.0),
        Arguments.of(
            "[ { \"service\": \"ser*\", \"name\": \"oper*\", \"sample_rate\": 1.0, \"max_per_second\": 15 } ]",
            true,
            (int) SPAN_SAMPLING_RATE,
            1.0,
            15.0),
        Arguments.of(
            "[ { \"service\": \"?ervice\", \"name\": \"operati?n\", \"sample_rate\": 1.0, \"max_per_second\": 10 } ]",
            true,
            (int) SPAN_SAMPLING_RATE,
            1.0,
            10.0),
        Arguments.of(
            "[ { \"service\": \"service\", \"name\": \"operation\", \"sample_rate\": 1.0, \"max_per_second\": 5 } ]",
            true,
            (int) SPAN_SAMPLING_RATE,
            1.0,
            5.0),
        Arguments.of(
            "[ { \"service\": \"service-b\", \"name\": \"*\", \"sample_rate\": 1.0, \"max_per_second\": 10 } ]",
            false,
            null,
            null,
            null),
        Arguments.of(
            "[ { \"service\": \"*\", \"name\": \"*\", \"sample_rate\": 0.0 } ]",
            false,
            null,
            null,
            null),
        Arguments.of(
            "[ { \"service\": \"*\", \"name\": \"operation-b\", \"sample_rate\": 0.5 } ]",
            false,
            null,
            null,
            null));
  }

  @ParameterizedTest
  @MethodSource("parentChildScenariosArguments")
  void parentChildScenariosWhenTraceIsDroppedButIndividualSpansAreKeptBySingleSpanSampler(
      String rules,
      boolean sampleRoot,
      boolean sampleChild,
      Object rootMechanism,
      Object childMechanism) {
    Properties properties = new Properties();
    if (rules != null) {
      properties.setProperty(SPAN_SAMPLING_RULES, rules);
      properties.setProperty(TRACE_SAMPLE_RATE, "0");
    }
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      SingleSpanSampler sampler = SingleSpanSampler.Builder.forConfig(Config.get(properties));

      DDSpan rootSpan =
          (DDSpan)
              tracer
                  .buildSpan("web.request")
                  .withServiceName("webserver")
                  .ignoreActiveSpan()
                  .start();

      DDSpan childSpan =
          (DDSpan)
              tracer
                  .buildSpan("web.handler")
                  .withServiceName("webserver")
                  .asChildOf(rootSpan.context())
                  .ignoreActiveSpan()
                  .start();

      rootSpan.setSamplingPriority(SAMPLER_DROP, DEFAULT);

      assertEquals(sampleRoot, sampler.setSamplingPriority(rootSpan));
      assertEquals(sampleChild, sampler.setSamplingPriority(childSpan));

      assertEquals(rootMechanism, rootSpan.getTag("_dd.span_sampling.mechanism"));
      assertEquals(childMechanism, childSpan.getTag("_dd.span_sampling.mechanism"));
    } finally {
      tracer.close();
    }
  }

  static Stream<Arguments> parentChildScenariosArguments() {
    return Stream.of(
        Arguments.of(
            "[{\"service\": \"webserver\", \"name\": \"web.request\"}]",
            true,
            false,
            (int) SPAN_SAMPLING_RATE,
            null),
        Arguments.of(
            "[{\"service\": \"webserver\", \"name\": \"web.handler\"}]",
            false,
            true,
            null,
            (int) SPAN_SAMPLING_RATE),
        Arguments.of(
            "[{\"service\": \"webserver\", \"name\": \"web.*\"}]",
            true,
            true,
            (int) SPAN_SAMPLING_RATE,
            (int) SPAN_SAMPLING_RATE),
        Arguments.of("[{\"service\": \"other-server\"}]", false, false, null, null));
  }

  @ParameterizedTest
  @MethodSource("setSamplingPriorityWithMaxPerSecondLimitArguments")
  void singleSpanSamplerSetSamplingPriorityWithTheMaxPerSecondLimit(
      String rules, boolean isFirstSampled, boolean isSecondSampled) {
    Properties properties = new Properties();
    if (rules != null) {
      properties.setProperty(SPAN_SAMPLING_RULES, rules);
    }
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      SingleSpanSampler sampler = SingleSpanSampler.Builder.forConfig(Config.get(properties));

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

      assertEquals(isFirstSampled, sampler.setSamplingPriority(span1));
      assertEquals(isSecondSampled, sampler.setSamplingPriority(span2));
    } finally {
      tracer.close();
    }
  }

  static Stream<Arguments> setSamplingPriorityWithMaxPerSecondLimitArguments() {
    return Stream.of(
        Arguments.of(
            "[ { \"service\": \"*\", \"name\": \"*\", \"sample_rate\": 1.0, \"max_per_second\": 1 } ]",
            true,
            false),
        Arguments.of(
            "[ { \"service\": \"ser*\", \"name\": \"oper*\", \"sample_rate\": 1.0, \"max_per_second\": 1 } ]",
            true,
            false),
        Arguments.of(
            "[ { \"service\": \"?ervice\", \"name\": \"operati?n\", \"sample_rate\": 1.0, \"max_per_second\": 1 } ]",
            true,
            false),
        Arguments.of(
            "[ { \"service\": \"service\", \"name\": \"operation\", \"sample_rate\": 1.0, \"max_per_second\": 1 } ]",
            true,
            false),
        Arguments.of("[ { \"service\": \"service\", \"max_per_second\": 1 } ]", true, false),
        Arguments.of(
            "[ { \"name\": \"operation\", \"sample_rate\": 1.0, \"max_per_second\": 1 } ]",
            true,
            false),
        Arguments.of(
            "[ { \"service\": \"*\", \"name\": \"*\", \"sample_rate\": 1.0, \"max_per_second\": 2 } ]",
            true,
            true),
        Arguments.of(
            "[ { \"service\": \"ser*\", \"name\": \"oper*\", \"max_per_second\": 2 } ]",
            true,
            true),
        Arguments.of(
            "[ { \"service\": \"?ervice\", \"name\": \"operati?n\", \"sample_rate\": 1.0, \"max_per_second\": 2 } ]",
            true,
            true));
  }

  @Test
  void loadRulesFromFile() throws Exception {
    String rules =
        "[ { \"service\": \"*\", \"name\": \"op?ration*\", \"sample_rate\": 1.0, \"max_per_second\": 1 } ]";
    Properties properties = new Properties();
    String rulesFile = SpanSamplingRulesTest.SpanSamplingRulesFileTest.createRulesFile(rules);
    properties.setProperty(SPAN_SAMPLING_RULES_FILE, rulesFile);
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      SingleSpanSampler sampler = SingleSpanSampler.Builder.forConfig(Config.get(properties));

      DDSpan span1 =
          (DDSpan)
              tracer
                  .buildSpan("operation")
                  .withServiceName("service")
                  .withTag("env", "bar")
                  .ignoreActiveSpan()
                  .start();

      assertTrue(sampler.setSamplingPriority(span1));
    } finally {
      tracer.close();
    }
  }

  @ParameterizedTest
  @MethodSource("preferRulesInEnvVarOverRulesFromFileArguments")
  void preferRulesInEnvVarOverRulesFromFile(String envVarRules, String fileRules, boolean matched)
      throws Exception {
    Properties properties = new Properties();
    properties.setProperty(SPAN_SAMPLING_RULES, envVarRules);
    properties.setProperty(
        SPAN_SAMPLING_RULES_FILE,
        SpanSamplingRulesTest.SpanSamplingRulesFileTest.createRulesFile(fileRules));
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      SingleSpanSampler sampler = SingleSpanSampler.Builder.forConfig(Config.get(properties));

      DDSpan span1 =
          (DDSpan)
              tracer
                  .buildSpan("operation")
                  .withServiceName("service")
                  .withTag("env", "bar")
                  .ignoreActiveSpan()
                  .start();

      assertEquals(matched, sampler.setSamplingPriority(span1));
    } finally {
      tracer.close();
    }
  }

  static Stream<Arguments> preferRulesInEnvVarOverRulesFromFileArguments() {
    return Stream.of(
        Arguments.of("[ { \"sample_rate\": 0 } ]", "[ { \"sample_rate\": 1 } ]", false),
        Arguments.of("[ { \"sample_rate\": 1 } ]", "[ { \"sample_rate\": 0 } ]", true));
  }

  @Test
  void throwNpeWhenPassedListOfRulesIsNull() {
    NullPointerException exception =
        assertThrows(
            NullPointerException.class,
            () -> new SingleSpanSampler.RuleBasedSingleSpanSampler(null));
    assertEquals("SpanSamplingRules can't be null.", exception.getMessage());
  }
}
