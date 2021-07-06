package com.datadog.appsec.config;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.ToJson;

import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class LegacyConfigJsonAdapter {

  @FromJson
  public AppSecConfig fromJson(JsonReader reader) {
    // Stub method needed to bypass internal moshi checker that not allow to use abstract classes
    return null;
  }

  @ToJson
  public void toJson(JsonWriter writer, AppSecConfig config) throws IOException {
    List<String> blockingRulesId = new LinkedList<>();
    List<String> passRulesId = new LinkedList<>();
    Set<String> parameters = new LinkedHashSet<>();

    writer.beginObject();
    writer.name("rules").beginArray();

    for (Event event : config.events) {

      String ruleId;
      if (event.id != null) {
        ruleId = event.id;
      } else {
        ruleId = md5(event.name);
      }

      if (event.action == Action.BLOCK) {
        blockingRulesId.add(ruleId);
      } else if (event.action == Action.LOG){
        passRulesId.add(ruleId);
      }

      writer.beginObject();
      writer.name("rule_id").value(ruleId);

      writer.name("filters").beginArray();
      for (Condition cond : event.conditions) {
        writer.beginObject();

        String operator = null;
        List<String> inputs = new LinkedList<>();
        List<String> values = new LinkedList<>();
        switch (cond.operation) {
          case MATCH_REGEX:
            operator = "@rx";
            Condition.MatchRegexCondition rxCond = (Condition.MatchRegexCondition) cond;

            for (String input : rxCond.inputs) {
              input = toLegacyAddress(input);
              inputs.add(input);
              parameters.add(input);
            }
            values.add(rxCond.regex);
            break;
          case PHRASE_MATCH:
            operator = "@pm";
            Condition.PhraseMatchCondition pmCond = (Condition.PhraseMatchCondition) cond;

            for (String input : pmCond.inputs) {
              input = toLegacyAddress(input);
              inputs.add(input);
              parameters.add(input);
            }
            values = pmCond.list;
            break;
          case MATCH_STRING:
            operator = "@eq";
            Condition.MatchStringCondition eqCond = (Condition.MatchStringCondition) cond;

            for (String input : eqCond.inputs) {
              input = toLegacyAddress(input);
              inputs.add(input);
              parameters.add(input);
            }
            values = eqCond.text;
            break;
          default:
        }

        if (operator != null) {
          writer.name("operator").value(operator);
          writer.name("targets").beginArray();
          for (String input : inputs) {
            writer.value(input);
          }
          writer.endArray();
          if (values != null) {
            if (values.size() == 1) {
              writer.name("value").value(values.get(0));
            } else if (values.size() > 1){
              writer.name("value").beginArray();
              for (String value : values) {
                writer.value(value);
              }
              writer.endArray();
            }
          }
        }

        if (event.transformers != null && !event.transformers.isEmpty()) {
          writer.name("transformations").beginArray();
          for (String s : event.transformers) {
            writer.value(s);
          }
          writer.endArray();
        }

        writer.endObject();
      }
      writer.endArray();
      writer.endObject();
    }
    writer.endArray();

    // Manifest
    writer.name("manifest").beginObject();
    for (String parameter : parameters) {
      writer.name(parameter).beginObject();
      writer.name("inherit_from").value(parameter);
      writer.name("run_on_value").value(true);
      writer.name("run_on_key").value(false);
      writer.endObject();
    }
    writer.endObject();

    writer.name("flows").beginArray();

    writer.beginObject();
    writer.name("name").value("flow_map");
    writer.name("steps").beginArray();

    // Block step
    if (!blockingRulesId.isEmpty()) {
      writer.beginObject();
      writer.name("id").value("start");
      writer.name("rule_ids").beginArray();
      for (String s : blockingRulesId) {
        writer.value(s);
      }
      writer.endArray();
      writer.name("on_match").value("exit_block");
      writer.endObject();
    }

    // Monitor step
    if (!passRulesId.isEmpty()) {
      writer.beginObject();
      writer.name("id").value("start");
      writer.name("rule_ids").beginArray();
      for (String s : passRulesId) {
        writer.value(s);
      }
      writer.endArray();
      writer.name("on_match").value("exit_monitor");
      writer.endObject();
    }

    writer.endArray();
    writer.endObject();
    writer.endArray();

    writer.endObject();
  }

  private String md5(String s) {
    try {
      MessageDigest md = MessageDigest.getInstance("MD5");
      md.update(s.getBytes(), 0, s.length());
      return new BigInteger(1, md.digest()).toString(16);
    } catch (NoSuchAlgorithmException e) {
      e.printStackTrace();
    }
    return null;
  }

  /**
   * If input starts with '$' - then remove it
   */
  private String toLegacyAddress(String input) {
    // Ignore first '$' symbol
    if (input.charAt(0) == '$') {
      input = input.substring(1);
    }
    return input;
  }
}
