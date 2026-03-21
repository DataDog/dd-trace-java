package datadog.trace.common.sampling;

import static datadog.trace.api.sampling.SamplingRule.MATCH_ALL;
import static org.junit.jupiter.api.Assertions.*;

import com.squareup.moshi.Moshi;
import datadog.trace.core.test.DDCoreSpecification;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class TraceSamplingRulesTest extends DDCoreSpecification {

  @Test
  void deserializeEmptyListOfTraceSamplingRulesFromJson() {
    TraceSamplingRules rules = TraceSamplingRules.deserialize("[]");
    assertTrue(rules.isEmpty());
  }

  @Test
  void deserializeTraceSamplingRulesFromJson() {
    List<TraceSamplingRules.Rule> rules =
        TraceSamplingRules.deserialize(
                "[\n"
                    + "  {\"service\": \"service-name\", \"name\": \"operation-name\", \"resource\": \"resource-name\", \"tags\":"
                    + "    {\"tag-name1\": \"tag-pattern1\","
                    + "     \"tag-name2\": \"tag-pattern2\"},"
                    + "    \"sample_rate\": 0.0},\n"
                    + "  {},\n"
                    + "  {\"service\": \"\", \"name\": \"\", \"resource\": \"\", \"tags\": {}},\n"
                    + "  {\"service\": null, \"name\": null, \"resource\": null, \"tags\": null, \"sample_rate\": null},\n"
                    + "\n"
                    + "  {\"sample_rate\": 0.25},\n"
                    + "  {\"sample_rate\": 0.5},\n"
                    + "  {\"sample_rate\": 0.75},\n"
                    + "  {\"sample_rate\": 1}\n"
                    + "]")
            .getRules();
    int ruleIndex = 0;

    assertEquals(8, rules.size());

    // Test a complete rule
    assertEquals("service-name", rules.get(ruleIndex).getService());
    assertEquals("operation-name", rules.get(ruleIndex).getName());
    assertEquals("resource-name", rules.get(ruleIndex).getResource());
    Map<String, String> expectedTags = new HashMap<>();
    expectedTags.put("tag-name1", "tag-pattern1");
    expectedTags.put("tag-name2", "tag-pattern2");
    assertEquals(expectedTags, rules.get(ruleIndex).getTags());
    assertEquals(0d, rules.get(ruleIndex++).getSampleRate(), 0.0);

    // Test default values with an empty rule
    assertEquals(MATCH_ALL, rules.get(ruleIndex).getService());
    assertEquals(MATCH_ALL, rules.get(ruleIndex).getName());
    assertEquals(MATCH_ALL, rules.get(ruleIndex).getResource());
    assertEquals(Collections.emptyMap(), rules.get(ruleIndex).getTags());
    assertEquals(1d, rules.get(ruleIndex++).getSampleRate(), 0.0);

    // Test rule with empty values
    assertEquals("", rules.get(ruleIndex).getService());
    assertEquals("", rules.get(ruleIndex).getName());
    assertEquals("", rules.get(ruleIndex).getResource());
    assertEquals(Collections.emptyMap(), rules.get(ruleIndex).getTags());
    assertEquals(1d, rules.get(ruleIndex++).getSampleRate(), 0.0);

    // Test rule with null values
    assertEquals(MATCH_ALL, rules.get(ruleIndex).getService());
    assertEquals(MATCH_ALL, rules.get(ruleIndex).getName());
    assertEquals(MATCH_ALL, rules.get(ruleIndex).getResource());
    assertEquals(Collections.emptyMap(), rules.get(ruleIndex).getTags());
    assertEquals(1d, rules.get(ruleIndex++).getSampleRate(), 0.0);

    // Test different sample rate values
    assertEquals(0.25d, rules.get(ruleIndex++).getSampleRate(), 0.0);
    assertEquals(0.5d, rules.get(ruleIndex++).getSampleRate(), 0.0);
    assertEquals(0.75d, rules.get(ruleIndex++).getSampleRate(), 0.0);
    assertEquals(1d, rules.get(ruleIndex++).getSampleRate(), 0.0);
  }

  @ParameterizedTest
  @MethodSource("invalidSampleRateArguments")
  void skipTraceSamplingRulesWithInvalidSampleRateValues(String rate) {
    TraceSamplingRules rules =
        TraceSamplingRules.deserialize(
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
        Arguments.of("\"\""),
        Arguments.of("{}"),
        Arguments.of("[]"));
  }

  @ParameterizedTest
  @MethodSource("incorrectJsonArguments")
  void skipTraceSamplingRulesWhenIncorrectJsonProvided(String jsonRules) {
    TraceSamplingRules rules = TraceSamplingRules.deserialize(jsonRules);
    assertTrue(rules.isEmpty());
  }

  static Stream<Arguments> incorrectJsonArguments() {
    return Stream.of(
        Arguments.of("["), Arguments.of("{\"service\": \"usersvc\",}"), Arguments.of(""));
  }

  @Test
  void renderJsonRuleCorrectlyWhenToStringIsCalled() throws IOException {
    String json =
        "{\"name\":\"name\",\"resource\":\"resource\",\"sample_rate\":\"0.5\",\"service\":\"service\",\"tags\":{\"a\":\"b\",\"foo\":\"bar\"}}";
    TraceSamplingRules.JsonRule jsonRule =
        new Moshi.Builder().build().adapter(TraceSamplingRules.JsonRule.class).fromJson(json);
    assertEquals(json, jsonRule.toString());
  }

  @Test
  void keepOnlyValidRulesWhenInvalidRulesArePresent() {
    TraceSamplingRules rules =
        TraceSamplingRules.deserialize(
            "[\n"
                + "  {\"service\": \"usersvc\", \"name\": \"healthcheck\", \"sample_rate\": 0.5},\n"
                + "  {\"service\": \"usersvc\", \"name\": \"healthcheck\", \"sample_rate\": 200}\n"
                + "]");
    assertEquals(1, rules.getRules().size());
  }
}
