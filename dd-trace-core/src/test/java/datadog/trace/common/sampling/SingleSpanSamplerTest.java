package datadog.trace.common.sampling;

import static datadog.trace.api.config.TracerConfig.SPAN_SAMPLING_RULES;
import static datadog.trace.api.config.TracerConfig.SPAN_SAMPLING_RULES_FILE;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLE_RATE;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.SamplingMechanism.DEFAULT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.Config;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.converter.SamplingMechanismConverter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;

class SingleSpanSamplerTest extends DDCoreJavaSpecification {
  @TempDir Path tempDir;

  @TableTest({
    "scenario                | rules                                                                                           ",
    "null rules              |                                                                                                 ",
    "empty rules             | '[]'                                                                                            ",
    "invalid sample_rate 10  | '[ { \"service\": \"*\", \"name\": \"*\", \"sample_rate\": 10.0 } ]'                            ",
    "invalid sample_rate all | '[ { \"service\": \"*\", \"name\": \"*\", \"sample_rate\": \"all\" } ]'                         ",
    "invalid max_per_second  | '[ { \"service\": \"*\", \"name\": \"*\", \"sample_rate\": 1.0, \"max_per_second\": \"N/A\" } ]'"
  })
  void singleSpanSamplerNotCreatedWhenNoRulesProvided(String rules) {
    Properties properties = new Properties();
    if (rules != null) {
      properties.setProperty(SPAN_SAMPLING_RULES, rules);
    }

    SingleSpanSampler sampler = SingleSpanSampler.Builder.forConfig(Config.get(properties));

    assertNull(sampler);
  }

  @TableTest({
    "scenario                   | rules                                                                                                     | isFirstSampled | expectedMechanism                    | expectedRate | expectedLimit",
    "* match rate 1.0           | '[ { \"service\": \"*\", \"name\": \"*\", \"sample_rate\": 1.0 } ]'                                       | true           | SamplingMechanism.SPAN_SAMPLING_RATE | 1.0          |              ",
    "* match rate 1.0 limit 10  | '[ { \"service\": \"*\", \"name\": \"*\", \"sample_rate\": 1.0, \"max_per_second\": 10 } ]'               | true           | SamplingMechanism.SPAN_SAMPLING_RATE | 1.0          | 10           ",
    "ser* oper* limit 15        | '[ { \"service\": \"ser*\", \"name\": \"oper*\", \"sample_rate\": 1.0, \"max_per_second\": 15 } ]'        | true           | SamplingMechanism.SPAN_SAMPLING_RATE | 1.0          | 15           ",
    "?ervice operati?n limit 10 | '[ { \"service\": \"?ervice\", \"name\": \"operati?n\", \"sample_rate\": 1.0, \"max_per_second\": 10 } ]' | true           | SamplingMechanism.SPAN_SAMPLING_RATE | 1.0          | 10           ",
    "service operation limit 5  | '[ { \"service\": \"service\", \"name\": \"operation\", \"sample_rate\": 1.0, \"max_per_second\": 5 } ]'  | true           | SamplingMechanism.SPAN_SAMPLING_RATE | 1.0          | 5            ",
    "service-b no match         | '[ { \"service\": \"service-b\", \"name\": \"*\", \"sample_rate\": 1.0, \"max_per_second\": 10 } ]'       | false          |                                      |              |              ",
    "rate 0.0 no sample         | '[ { \"service\": \"*\", \"name\": \"*\", \"sample_rate\": 0.0 } ]'                                       | false          |                                      |              |              ",
    "operation-b no match       | '[ { \"service\": \"*\", \"name\": \"operation-b\", \"sample_rate\": 0.5 } ]'                             | false          |                                      |              |              "
  })
  void singleSpanSamplerSetSamplingPriority(
      String rules,
      boolean isFirstSampled,
      @ConvertWith(SamplingMechanismConverter.class) Byte expectedMechanism,
      Double expectedRate,
      Integer expectedLimit) {
    Properties properties = new Properties();
    if (rules != null) {
      properties.setProperty(SPAN_SAMPLING_RULES, rules);
    }
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    SingleSpanSampler sampler = SingleSpanSampler.Builder.forConfig(Config.get(properties));

    DDSpan span =
        (DDSpan)
            tracer
                .buildSpan("datadog", "operation")
                .withServiceName("service")
                .withTag("env", "bar")
                .ignoreActiveSpan()
                .start();

    assertEquals(isFirstSampled, sampler.setSamplingPriority(span));

    assertTagEquals(expectedMechanism, span.getTag("_dd.span_sampling.mechanism"));
    assertTagEquals(expectedRate, span.getTag("_dd.span_sampling.rule_rate"));
    assertTagEquals(expectedLimit, span.getTag("_dd.span_sampling.max_per_second"));
  }

  @TableTest({
    "scenario              | rules                                                       | sampleRoot | sampleChild | rootMechanism                        | childMechanism                      ",
    "web.request match     | '[{\"service\": \"webserver\", \"name\": \"web.request\"}]' | true       | false       | SamplingMechanism.SPAN_SAMPLING_RATE |                                     ",
    "web.handler match     | '[{\"service\": \"webserver\", \"name\": \"web.handler\"}]' | false      | true        |                                      | SamplingMechanism.SPAN_SAMPLING_RATE",
    "web.* match both      | '[{\"service\": \"webserver\", \"name\": \"web.*\"}]'       | true       | true        | SamplingMechanism.SPAN_SAMPLING_RATE | SamplingMechanism.SPAN_SAMPLING_RATE",
    "other-server no match | '[{\"service\": \"other-server\"}]'                         | false      | false       |                                      |                                     "
  })
  void parentChildScenariosWhenTraceDroppedButSpansKeptBySingleSpanSampler(
      String rules,
      boolean sampleRoot,
      boolean sampleChild,
      @ConvertWith(SamplingMechanismConverter.class) Byte rootMechanism,
      @ConvertWith(SamplingMechanismConverter.class) Byte childMechanism) {
    Properties properties = new Properties();
    if (rules != null) {
      properties.setProperty(SPAN_SAMPLING_RULES, rules);
      properties.setProperty(TRACE_SAMPLE_RATE, "0");
    }
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    SingleSpanSampler sampler = SingleSpanSampler.Builder.forConfig(Config.get(properties));

    DDSpan rootSpan =
        (DDSpan)
            tracer
                .buildSpan("datadog", "web.request")
                .withServiceName("webserver")
                .ignoreActiveSpan()
                .start();

    DDSpan childSpan =
        (DDSpan)
            tracer
                .buildSpan("datadog", "web.handler")
                .withServiceName("webserver")
                .asChildOf(rootSpan)
                .ignoreActiveSpan()
                .start();

    // set trace sampling priority to drop the trace
    rootSpan.setSamplingPriority(SAMPLER_DROP, DEFAULT);

    // set spans sampling priority
    assertEquals(sampleRoot, sampler.setSamplingPriority(rootSpan));
    assertEquals(sampleChild, sampler.setSamplingPriority(childSpan));

    assertTagEquals(rootMechanism, rootSpan.getTag("_dd.span_sampling.mechanism"));
    assertTagEquals(childMechanism, childSpan.getTag("_dd.span_sampling.mechanism"));
  }

  @TableTest({
    "scenario                   | rules                                                                                                    | isFirstSampled | isSecondSampled",
    "* limit 1 first only       | '[ { \"service\": \"*\", \"name\": \"*\", \"sample_rate\": 1.0, \"max_per_second\": 1 } ]'               | true           | false          ",
    "ser* oper* limit 1         | '[ { \"service\": \"ser*\", \"name\": \"oper*\", \"sample_rate\": 1.0, \"max_per_second\": 1 } ]'        | true           | false          ",
    "?ervice operati?n limit 1  | '[ { \"service\": \"?ervice\", \"name\": \"operati?n\", \"sample_rate\": 1.0, \"max_per_second\": 1 } ]' | true           | false          ",
    "service operation limit 1  | '[ { \"service\": \"service\", \"name\": \"operation\", \"sample_rate\": 1.0, \"max_per_second\": 1 } ]' | true           | false          ",
    "service only limit 1       | '[ { \"service\": \"service\", \"max_per_second\": 1 } ]'                                                | true           | false          ",
    "name only limit 1          | '[ { \"name\": \"operation\", \"sample_rate\": 1.0, \"max_per_second\": 1 } ]'                           | true           | false          ",
    "* limit 2 both sampled     | '[ { \"service\": \"*\", \"name\": \"*\", \"sample_rate\": 1.0, \"max_per_second\": 2 } ]'               | true           | true           ",
    "ser* oper* no rate limit 2 | '[ { \"service\": \"ser*\", \"name\": \"oper*\", \"max_per_second\": 2 } ]'                              | true           | true           ",
    "?ervice operati?n limit 2  | '[ { \"service\": \"?ervice\", \"name\": \"operati?n\", \"sample_rate\": 1.0, \"max_per_second\": 2 } ]' | true           | true           "
  })
  void singleSpanSamplerSetSamplingPriorityWithMaxPerSecondLimit(
      String rules, boolean isFirstSampled, boolean isSecondSampled) {
    Properties properties = new Properties();
    if (rules != null) {
      properties.setProperty(SPAN_SAMPLING_RULES, rules);
    }
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    SingleSpanSampler sampler = SingleSpanSampler.Builder.forConfig(Config.get(properties));

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

    assertEquals(isFirstSampled, sampler.setSamplingPriority(span1));
    assertEquals(isSecondSampled, sampler.setSamplingPriority(span2));
  }

  @Test
  void loadRulesFromFile() throws Exception {
    String rules =
        "[ { \"service\": \"*\", \"name\": \"op?ration*\", \"sample_rate\": 1.0, \"max_per_second\": 1 } ]";
    Properties properties = new Properties();
    properties.setProperty(SPAN_SAMPLING_RULES_FILE, createRulesFile(rules));
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    SingleSpanSampler sampler = SingleSpanSampler.Builder.forConfig(Config.get(properties));

    DDSpan span1 =
        (DDSpan)
            tracer
                .buildSpan("datadog", "operation")
                .withServiceName("service")
                .withTag("env", "bar")
                .ignoreActiveSpan()
                .start();

    assertTrue(sampler.setSamplingPriority(span1));
  }

  @TableTest({
    "scenario              | envVarRules                  | fileRules                    | matched",
    "env 0 file 1 -> false | '[ { \"sample_rate\": 0 } ]' | '[ { \"sample_rate\": 1 } ]' | false  ",
    "env 1 file 0 -> true  | '[ { \"sample_rate\": 1 } ]' | '[ { \"sample_rate\": 0 } ]' | true   "
  })
  void preferRulesInEnvVarOverRulesFromFile(String envVarRules, String fileRules, boolean matched)
      throws Exception {
    Properties properties = new Properties();
    properties.setProperty(SPAN_SAMPLING_RULES, envVarRules);
    properties.setProperty(SPAN_SAMPLING_RULES_FILE, createRulesFile(fileRules));
    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();

    SingleSpanSampler sampler = SingleSpanSampler.Builder.forConfig(Config.get(properties));

    DDSpan span1 =
        (DDSpan)
            tracer
                .buildSpan("datadog", "operation")
                .withServiceName("service")
                .withTag("env", "bar")
                .ignoreActiveSpan()
                .start();

    assertEquals(matched, sampler.setSamplingPriority(span1));
  }

  @Test
  void throwNpeWhenPassedListOfRulesIsNull() {
    NullPointerException exception =
        assertThrows(
            NullPointerException.class,
            () -> new SingleSpanSampler.RuleBasedSingleSpanSampler(null));
    assertEquals("SpanSamplingRules can't be null.", exception.getMessage());
  }

  private String createRulesFile(String rules) throws IOException {
    Path tempFile = tempDir.resolve("single-span-sampling-rules.json");
    Files.write(tempFile, rules.getBytes(StandardCharsets.UTF_8));
    return tempFile.toString();
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
