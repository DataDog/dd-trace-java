package datadog.trace.common.sampling;

import static datadog.trace.api.sampling.SamplingRule.MATCH_ALL;
import static org.junit.jupiter.api.Assertions.*;

import com.squareup.moshi.Moshi;
import datadog.trace.core.test.DDCoreSpecification;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class SpanSamplingRulesTest extends DDCoreSpecification {

  protected SpanSamplingRules deserializeRules(String jsonRules) {
    return SpanSamplingRules.deserialize(jsonRules);
  }

  @Test
  void deserializeEmptyListOfSpanSamplingRulesFromJson() {
    SpanSamplingRules rules = deserializeRules("[]");
    assertTrue(rules.isEmpty());
  }

  @Test
  void deserializeSpanSamplingRulesFromJson() {
    List<SpanSamplingRules.Rule> rules =
        deserializeRules(
                "[\n"
                    + "  {\"service\": \"service-name\", \"name\": \"operation-name\", \"resource\": \"resource-name\", \"tags\":"
                    + "    {\"tag-name1\": \"tag-pattern1\","
                    + "     \"tag-name2\": \"tag-pattern2\"},"
                    + "    \"sample_rate\": 0.0, \"max_per_second\": 10.0},\n"
                    + "  {},\n"
                    + "  {\"service\": \"\", \"name\": \"\", \"resource\": \"\", \"tags\": {}},\n"
                    + "  {\"service\": null, \"name\": null, \"resource\": null, \"tags\": null, \"sample_rate\": null, \"max_per_second\": null},\n"
                    + "\n"
                    + "  {\"sample_rate\": 0.25},\n"
                    + "  {\"sample_rate\": 0.5},\n"
                    + "  {\"sample_rate\": 0.75},\n"
                    + "  {\"sample_rate\": 1},\n"
                    + "\n"
                    + "  {\"max_per_second\": 0.2},\n"
                    + "  {\"max_per_second\": 1.0},\n"
                    + "  {\"max_per_second\": 10},\n"
                    + "  {\"max_per_second\": 10.123},\n"
                    + "  {\"max_per_second\": 10000}\n"
                    + "]")
            .getRules();
    int ruleIndex = 0;

    assertEquals(13, rules.size());

    // Test a complete rule
    assertEquals("service-name", rules.get(ruleIndex).getService());
    assertEquals("operation-name", rules.get(ruleIndex).getName());
    assertEquals("resource-name", rules.get(ruleIndex).getResource());
    Map<String, String> expectedTags = new HashMap<>();
    expectedTags.put("tag-name1", "tag-pattern1");
    expectedTags.put("tag-name2", "tag-pattern2");
    assertEquals(expectedTags, rules.get(ruleIndex).getTags());
    assertEquals(0.0d, rules.get(ruleIndex).getSampleRate(), 0.0);
    assertEquals(10, rules.get(ruleIndex++).getMaxPerSecond());

    // Test default values with an empty rule
    assertEquals(MATCH_ALL, rules.get(ruleIndex).getService());
    assertEquals(MATCH_ALL, rules.get(ruleIndex).getName());
    assertEquals(MATCH_ALL, rules.get(ruleIndex).getResource());
    assertEquals(Collections.emptyMap(), rules.get(ruleIndex).getTags());
    assertEquals(1d, rules.get(ruleIndex).getSampleRate(), 0.0);
    assertEquals(Integer.MAX_VALUE, rules.get(ruleIndex++).getMaxPerSecond());

    // Test rule with empty values
    assertEquals("", rules.get(ruleIndex).getService());
    assertEquals("", rules.get(ruleIndex).getName());
    assertEquals("", rules.get(ruleIndex).getResource());
    assertEquals(Collections.emptyMap(), rules.get(ruleIndex).getTags());
    assertEquals(1d, rules.get(ruleIndex).getSampleRate(), 0.0);
    assertEquals(Integer.MAX_VALUE, rules.get(ruleIndex++).getMaxPerSecond());

    // Test rule with null values
    assertEquals(MATCH_ALL, rules.get(ruleIndex).getService());
    assertEquals(MATCH_ALL, rules.get(ruleIndex).getName());
    assertEquals(MATCH_ALL, rules.get(ruleIndex).getResource());
    assertEquals(Collections.emptyMap(), rules.get(ruleIndex).getTags());
    assertEquals(1d, rules.get(ruleIndex).getSampleRate(), 0.0);
    assertEquals(Integer.MAX_VALUE, rules.get(ruleIndex++).getMaxPerSecond());

    // Test different sample rate values
    assertEquals(0.25d, rules.get(ruleIndex++).getSampleRate(), 0.0);
    assertEquals(0.5d, rules.get(ruleIndex++).getSampleRate(), 0.0);
    assertEquals(0.75d, rules.get(ruleIndex++).getSampleRate(), 0.0);
    assertEquals(1d, rules.get(ruleIndex++).getSampleRate(), 0.0);

    // Test different max per second values
    assertEquals(1, rules.get(ruleIndex++).getMaxPerSecond());
    assertEquals(1, rules.get(ruleIndex++).getMaxPerSecond());
    assertEquals(10, rules.get(ruleIndex++).getMaxPerSecond());
    assertEquals(10, rules.get(ruleIndex++).getMaxPerSecond());
    assertEquals(10000, rules.get(ruleIndex++).getMaxPerSecond());
  }

  @ParameterizedTest
  @MethodSource("invalidSampleRateArguments")
  void skipSpanSamplingRulesWithInvalidSampleRateValues(String rate) {
    SpanSamplingRules rules =
        deserializeRules(
            "[{\"service\": \"usersvc\", \"name\": \"healthcheck\", \"sample_rate\": "
                + rate
                + "}]");
    assertTrue(rules.isEmpty());
  }

  static Stream<Arguments> invalidSampleRateArguments() {
    return Stream.of(
        Arguments.of("-0.1"),
        Arguments.of("-11"),
        Arguments.of("1.2"),
        Arguments.of("100"),
        Arguments.of("\"zero\""),
        Arguments.of("\"\""));
  }

  @ParameterizedTest
  @MethodSource("invalidMaxPerSecondArguments")
  void skipSpanSamplingRulesWithInvalidMaxPerSecondValues(String limit) {
    SpanSamplingRules rules =
        deserializeRules(
            "[{\"service\": \"usersvc\", \"name\": \"healthcheck\", \"max_per_second\": "
                + limit
                + "}]");
    assertTrue(rules.isEmpty());
  }

  static Stream<Arguments> invalidMaxPerSecondArguments() {
    return Stream.of(
        Arguments.of("0"), Arguments.of("-11"), Arguments.of("\"zero\""), Arguments.of("\"\""));
  }

  @ParameterizedTest
  @MethodSource("incorrectJsonArguments")
  void skipSpanSamplingRulesWhenIncorrectJsonProvided(String jsonRules) {
    SpanSamplingRules rules = deserializeRules(jsonRules);
    assertTrue(rules.isEmpty());
  }

  static Stream<Arguments> incorrectJsonArguments() {
    return Stream.of(
        Arguments.of("["), Arguments.of("{\"service\": \"usersvc\",}"), Arguments.of(""));
  }

  @Test
  void renderJsonRuleCorrectlyWhenToStringIsCalled() throws IOException {
    String json =
        "{\"max_per_second\":\"10\",\"name\":\"name\",\"resource\":\"resource\",\"sample_rate\":\"0.5\",\"service\":\"service\",\"tags\":{\"a\":\"b\",\"foo\":\"bar\"}}";
    SpanSamplingRules.JsonRule jsonRule =
        new Moshi.Builder().build().adapter(SpanSamplingRules.JsonRule.class).fromJson(json);
    assertEquals(json, jsonRule.toString());
  }

  @Test
  void keepOnlyValidRulesWhenInvalidRulesArePresent() {
    SpanSamplingRules rules =
        SpanSamplingRules.deserialize(
            "[\n"
                + "  {\"service\": \"usersvc\", \"name\": \"healthcheck\", \"sample_rate\": 0.5},\n"
                + "  {\"service\": \"usersvc\", \"name\": \"healthcheck2\", \"sample_rate\": 200}\n"
                + "]");
    assertEquals(1, rules.getRules().size());
  }

  public static class SpanSamplingRulesFileTest extends SpanSamplingRulesTest {

    public static String createRulesFile(String rules) throws IOException {
      Path p = Files.createTempFile("single-span-sampling-rules", ".json");
      Files.write(p, rules.getBytes());
      return p.toString();
    }

    @Override
    protected SpanSamplingRules deserializeRules(String jsonRules) {
      try {
        return SpanSamplingRules.deserializeFile(createRulesFile(jsonRules));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
