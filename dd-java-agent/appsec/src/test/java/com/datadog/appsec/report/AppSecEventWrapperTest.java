package com.datadog.appsec.report;

import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.appsec.ddwaf.WAFResultData.Parameter;
import com.datadog.appsec.ddwaf.WAFResultData.Rule;
import com.datadog.appsec.ddwaf.WAFResultData.RuleMatch;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AppSecEventWrapperTest {

  @Test
  void validateJsonSerializationForAppSecEvent() {
    Parameter parameter =
        new Parameter(
            "parameter_address",
            singletonList("parameter_key_path"),
            "parameter_value",
            singletonList("parameter_highlight"));
    RuleMatch ruleMatch =
        new RuleMatch("rule_match_operator", "rule_match_operator_value", singletonList(parameter));
    AppSecEvent event =
        new AppSecEvent.Builder()
            .withRule(new Rule("rule_id", "rule_name", singletonMap("tag", "value")))
            .withRuleMatches(singletonList(ruleMatch))
            .build();

    String json = new AppSecEventWrapper(singletonList(event)).toString();

    String expectedJson =
        "{\"triggers\":[{\"rule\":{\"id\":\"rule_id\",\"name\":\"rule_name\",\"tags\":{\"tag\":\"value\"}},"
            + "\"rule_matches\":[{\"operator\":\"rule_match_operator\",\"operator_value\":\"rule_match_operator_value\","
            + "\"parameters\":[{\"address\":\"parameter_address\",\"highlight\":[\"parameter_highlight\"],"
            + "\"key_path\":[\"parameter_key_path\"],\"value\":\"parameter_value\"}]}]}]}";
    assertEquals(expectedJson, json);
  }

  // libddwaf 2.x emits array indices in key_path as numbers (e.g. key_path: [0], not ["0"]);
  // this guards that a whole-number Double round-trips as "0", not "0.0".
  @Test
  void validateJsonSerializationForNumericKeyPath() {
    Parameter parameter =
        new Parameter("server.request.body", Arrays.asList("items", 0.0, "name"), "value", null);
    RuleMatch ruleMatch = new RuleMatch("operator", "operator_value", singletonList(parameter));
    AppSecEvent event =
        new AppSecEvent.Builder()
            .withRule(new Rule("rule_id", "rule_name", singletonMap("tag", "value")))
            .withRuleMatches(singletonList(ruleMatch))
            .build();

    String json = new AppSecEventWrapper(singletonList(event)).toString();

    assertTrue(json.contains("\"key_path\":[\"items\",0,\"name\"]"), "actual json: " + json);
  }

  // Non-whole-number Doubles must keep Moshi's default formatting - only whole numbers get the
  // integer-style rewrite.
  @Test
  void validateJsonSerializationForNonIntegralKeyPath() {
    Parameter parameter =
        new Parameter("server.request.body", Arrays.asList("items", 1.5, "name"), "value", null);
    RuleMatch ruleMatch = new RuleMatch("operator", "operator_value", singletonList(parameter));
    AppSecEvent event =
        new AppSecEvent.Builder()
            .withRule(new Rule("rule_id", "rule_name", singletonMap("tag", "value")))
            .withRuleMatches(singletonList(ruleMatch))
            .build();

    String json = new AppSecEventWrapper(singletonList(event)).toString();

    assertTrue(json.contains("\"key_path\":[\"items\",1.5,\"name\"]"), "actual json: " + json);
  }
}
