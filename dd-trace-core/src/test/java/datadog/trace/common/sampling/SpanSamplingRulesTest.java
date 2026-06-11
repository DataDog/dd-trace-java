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

class SpanSamplingRulesTest {

  protected SpanSamplingRules deserializeRules(String jsonRules) {
    return SpanSamplingRules.deserialize(jsonRules);
  }

  @Test
  void deserializeEmptyListOfSpanSamplingRulesFromJson() {
    assertTrue(deserializeRules("[]").isEmpty());
  }

  @Test
  void deserializeSpanSamplingRulesFromJson() {
    SpanSamplingRules result =
        deserializeRules(
            "[\n"
                + "  {\"service\": \"service-name\", \"name\": \"operation-name\", \"resource\": \"resource-name\", \"tags\":\n"
                + "    {\"tag-name1\": \"tag-pattern1\",\n"
                + "     \"tag-name2\": \"tag-pattern2\"},\n"
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
                + "]");
    List<SpanSamplingRules.Rule> rules = result.getRules();
    int ruleIndex = 0;

    assertEquals(13, rules.size());

    // Test a complete rule
    Map<String, String> expectedTags = new LinkedHashMap<>();
    expectedTags.put("tag-name1", "tag-pattern1");
    expectedTags.put("tag-name2", "tag-pattern2");
    assertEquals("service-name", rules.get(ruleIndex).getService());
    assertEquals("operation-name", rules.get(ruleIndex).getName());
    assertEquals("resource-name", rules.get(ruleIndex).getResource());
    assertEquals(expectedTags, rules.get(ruleIndex).getTags());
    assertEquals(0.0d, rules.get(ruleIndex).getSampleRate(), 1e-9);
    assertEquals(10, rules.get(ruleIndex++).getMaxPerSecond());

    // Test default values with an empty rule
    assertEquals(MATCH_ALL, rules.get(ruleIndex).getService());
    assertEquals(MATCH_ALL, rules.get(ruleIndex).getName());
    assertEquals(MATCH_ALL, rules.get(ruleIndex).getResource());
    assertTrue(rules.get(ruleIndex).getTags().isEmpty());
    assertEquals(1d, rules.get(ruleIndex).getSampleRate(), 1e-9);
    assertEquals(Integer.MAX_VALUE, rules.get(ruleIndex++).getMaxPerSecond());

    // Test rule with empty values
    assertEquals("", rules.get(ruleIndex).getService());
    assertEquals("", rules.get(ruleIndex).getName());
    assertEquals("", rules.get(ruleIndex).getResource());
    assertTrue(rules.get(ruleIndex).getTags().isEmpty());
    assertEquals(1d, rules.get(ruleIndex).getSampleRate(), 1e-9);
    assertEquals(Integer.MAX_VALUE, rules.get(ruleIndex++).getMaxPerSecond());

    // Test rule with null values
    assertEquals(MATCH_ALL, rules.get(ruleIndex).getService());
    assertEquals(MATCH_ALL, rules.get(ruleIndex).getName());
    assertEquals(MATCH_ALL, rules.get(ruleIndex).getResource());
    assertTrue(rules.get(ruleIndex).getTags().isEmpty());
    assertEquals(1d, rules.get(ruleIndex).getSampleRate(), 1e-9);
    assertEquals(Integer.MAX_VALUE, rules.get(ruleIndex++).getMaxPerSecond());

    // Test different sample rate values
    assertEquals(0.25d, rules.get(ruleIndex++).getSampleRate(), 1e-9);
    assertEquals(0.5d, rules.get(ruleIndex++).getSampleRate(), 1e-9);
    assertEquals(0.75d, rules.get(ruleIndex++).getSampleRate(), 1e-9);
    assertEquals(1d, rules.get(ruleIndex++).getSampleRate(), 1e-9);

    // Test different max per second values
    assertEquals(1, rules.get(ruleIndex++).getMaxPerSecond());
    assertEquals(1, rules.get(ruleIndex++).getMaxPerSecond());
    assertEquals(10, rules.get(ruleIndex++).getMaxPerSecond());
    assertEquals(10, rules.get(ruleIndex++).getMaxPerSecond());
    assertEquals(10000, rules.get(ruleIndex++).getMaxPerSecond());
  }

  @TableTest({
    "scenario | rate      ",
    "-0.1     | -0.1      ",
    "-11      | -11       ",
    "1.2      | 1.2       ",
    "100      | 100       ",
    "\"zero\" | '\"zero\"'",
    "\"\"     | '\"\"'    "
  })
  void skipSpanSamplingRulesWithInvalidSampleRateValues(String rate) {
    String json =
        "[{\"service\": \"usersvc\", \"name\": \"healthcheck\", \"sample_rate\": " + rate + "}]";
    SpanSamplingRules result = deserializeRules(json);

    assertTrue(result.isEmpty());
  }

  @TableTest({
    "scenario | limit     ",
    "0        | 0         ",
    "-11      | -11       ",
    "\"zero\" | '\"zero\"'",
    "\"\"     | '\"\"'    "
  })
  void skipSpanSamplingRulesWithInvalidMaxPerSecondValues(String limit) {
    String json =
        "[{\"service\": \"usersvc\", \"name\": \"healthcheck\", \"max_per_second\": "
            + limit
            + "}]";
    SpanSamplingRules result = deserializeRules(json);

    assertTrue(result.isEmpty());
  }

  @TableTest({
    "scenario               | jsonRules                    ",
    "truncated open bracket | '['                          ",
    "trailing comma         | '{\"service\": \"usersvc\",}'",
    "empty string           | ''                           "
  })
  void skipSpanSamplingRulesWhenIncorrectJsonProvided(String jsonRules) {
    assertTrue(deserializeRules(jsonRules).isEmpty());
  }

  @Test
  @SuppressWarnings("unchecked")
  void renderJsonRuleCorrectlyWhenToStringIsCalled() throws Exception {
    String json =
        "{\"max_per_second\":\"10\",\"name\":\"name\",\"resource\":\"resource\",\"sample_rate\":\"0.5\",\"service\":\"service\",\"tags\":{\"a\":\"b\",\"foo\":\"bar\"}}";
    Class<?> jsonRuleClass =
        Class.forName("datadog.trace.common.sampling.SpanSamplingRules$JsonRule");
    Moshi moshi = new Moshi.Builder().build();
    JsonAdapter<Object> adapter = (JsonAdapter<Object>) moshi.adapter(jsonRuleClass);
    Object jsonRule = adapter.fromJson(json);

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
}
