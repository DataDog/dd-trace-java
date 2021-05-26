package com.datadog.appsec.config;

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

  @ToJson
  public void toJson(JsonWriter writer, AppSecConfig config) throws IOException {

    List<String> blockingRulesId = new LinkedList<>();
    List<String> passRulesId = new LinkedList<>();
    Set<String> parameters = new LinkedHashSet<>();

    writer.beginObject();
    writer.name("rules").beginArray();

    for (Rule r : config.rules) {

      String ruleId;
      if (r.id != null) {
        ruleId = r.id.toString();
      } else {
        ruleId = md5(r.name);
      }

      if (r.action == Action.BLOCK) {
        blockingRulesId.add(ruleId);
      } else {
        passRulesId.add(ruleId);
      }

      writer.beginObject();
      writer.name("rule_id").value(ruleId);

      writer.name("filters").beginArray();
      for (Step step : r.steps) {
        writer.beginObject();

        String operator = null;
        switch (step.operation) {
          case MATCH_REGEX:
            operator = "@rx";
            break;
          case HAS_SQLI_PATTERN:
            operator = "@detectSQLi";
            break;
          case HAS_XSS_PATTERN:
            operator = "@detectXSS";
            break;
          default:
        }

        if (operator != null) {
          writer.name("operator").value(operator);
          writer.name("targets").beginArray();
          for (String user_param : step.targets.user_params) {
            parameters.add(user_param);
            writer.value(user_param);
          }
          writer.endArray();
          if (step.targets.regex != null) {
            writer.name("value").value(step.targets.regex);
          }
        }

        if (step.transformers != null && !step.transformers.isEmpty()) {
          writer.name("transformations").beginArray();
          for (String s : step.transformers) {
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
    writer.name("name").value("blocking");
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
}
