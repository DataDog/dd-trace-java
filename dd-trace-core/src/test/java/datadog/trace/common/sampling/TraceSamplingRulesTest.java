package datadog.trace.common.sampling;

import static datadog.trace.api.sampling.SamplingRule.MATCH_ALL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class TraceSamplingRulesTest {

  @Test
  void deserializeEmptyListOfTraceSamplingRulesFromJson() {
    assertTrue(TraceSamplingRules.deserialize("[]").isEmpty());
  }

  @Test
  void deserializeTraceSamplingRulesFromJson() {
    List<TraceSamplingRules.Rule> rules =
        TraceSamplingRules.deserialize(
                "[\n"
                    + "  {\"service\": \"service-name\", \"name\": \"operation-name\", \"resource\": \"resource-name\", \"tags\":\n"
                    + "    {\"tag-name1\": \"tag-pattern1\",\n"
                    + "     \"tag-name2\": \"tag-pattern2\"},\n"
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
    Map<String, String> expectedTags = new LinkedHashMap<>();
    expectedTags.put("tag-name1", "tag-pattern1");
    expectedTags.put("tag-name2", "tag-pattern2");
    assertEquals("service-name", rules.get(ruleIndex).getService());
    assertEquals("operation-name", rules.get(ruleIndex).getName());
    assertEquals("resource-name", rules.get(ruleIndex).getResource());
    assertEquals(expectedTags, rules.get(ruleIndex).getTags());
    assertEquals(0d, rules.get(ruleIndex++).getSampleRate(), 1e-9);

    // Test default values with an empty rule
    assertEquals(MATCH_ALL, rules.get(ruleIndex).getService());
    assertEquals(MATCH_ALL, rules.get(ruleIndex).getName());
    assertEquals(MATCH_ALL, rules.get(ruleIndex).getResource());
    assertTrue(rules.get(ruleIndex).getTags().isEmpty());
    assertEquals(1d, rules.get(ruleIndex++).getSampleRate(), 1e-9);

    // Test rule with empty values
    assertEquals("", rules.get(ruleIndex).getService());
    assertEquals("", rules.get(ruleIndex).getName());
    assertEquals("", rules.get(ruleIndex).getResource());
    assertTrue(rules.get(ruleIndex).getTags().isEmpty());
    assertEquals(1d, rules.get(ruleIndex++).getSampleRate(), 1e-9);

    // Test rule with null values
    assertEquals(MATCH_ALL, rules.get(ruleIndex).getService());
    assertEquals(MATCH_ALL, rules.get(ruleIndex).getName());
    assertEquals(MATCH_ALL, rules.get(ruleIndex).getResource());
    assertTrue(rules.get(ruleIndex).getTags().isEmpty());
    assertEquals(1d, rules.get(ruleIndex++).getSampleRate(), 1e-9);

    // Test different sample rate values
    assertEquals(0.25d, rules.get(ruleIndex++).getSampleRate(), 1e-9);
    assertEquals(0.5d, rules.get(ruleIndex++).getSampleRate(), 1e-9);
    assertEquals(0.75d, rules.get(ruleIndex++).getSampleRate(), 1e-9);
    assertEquals(1d, rules.get(ruleIndex++).getSampleRate(), 1e-9);
  }

  @TableTest({
    "scenario | rate      ",
    "-0.1     | -0.1      ",
    "-11      | -11       ",
    "1.2      | 1.2       ",
    "100      | 100       ",
    "\"zero\" | '\"zero\"'",
    "\"\"     | '\"\"'    ",
    "{}       | '{}'      ",
    "[]       | '[]'      "
  })
  void skipTraceSamplingRulesWithInvalidSampleRateValues(String rate) {
    String json =
        "[{\"service\": \"usersvc\", \"name\": \"healthcheck\", \"sample_rate\": " + rate + "}]";
    TraceSamplingRules result = TraceSamplingRules.deserialize(json);

    assertTrue(result.isEmpty());
  }

  @TableTest({
    "scenario               | jsonRules                    ",
    "truncated open bracket | '['                          ",
    "trailing comma         | '{\"service\": \"usersvc\",}'",
    "empty string           | ''                           "
  })
  void skipTraceSamplingRulesWhenIncorrectJsonProvided(String jsonRules) {
    assertTrue(TraceSamplingRules.deserialize(jsonRules).isEmpty());
  }

  @Test
  @SuppressWarnings("unchecked")
  void renderJsonRuleCorrectlyWhenToStringIsCalled() throws Exception {
    String json =
        "{\"name\":\"name\",\"resource\":\"resource\",\"sample_rate\":\"0.5\",\"service\":\"service\",\"tags\":{\"a\":\"b\",\"foo\":\"bar\"}}";
    Class<?> jsonRuleClass =
        Class.forName("datadog.trace.common.sampling.TraceSamplingRules$JsonRule");
    Moshi moshi = new Moshi.Builder().build();
    JsonAdapter<Object> adapter = (JsonAdapter<Object>) moshi.adapter(jsonRuleClass);
    Object jsonRule = adapter.fromJson(json);

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
