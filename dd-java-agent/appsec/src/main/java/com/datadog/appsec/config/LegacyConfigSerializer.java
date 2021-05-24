package com.datadog.appsec.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class LegacyConfigSerializer extends StdSerializer<AppSecConfig> {

  public LegacyConfigSerializer() {
    this(null);
  }

  public LegacyConfigSerializer(Class<AppSecConfig> t) {
    super(t);
  }

  @Override
  public void serialize(AppSecConfig config, JsonGenerator gen, SerializerProvider provider)
      throws IOException {

    List<String> blockingRulesId = new LinkedList<>();
    List<String> passRulesId = new LinkedList<>();
    Set<String> parameters = new LinkedHashSet<>();

    gen.writeStartObject();
    gen.writeArrayFieldStart("rules");
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

      gen.writeStartObject();
      gen.writeStringField("rule_id", ruleId);
      gen.writeArrayFieldStart("filters");

      for (Step step : r.steps) {
        gen.writeStartObject();

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
          gen.writeStringField("operator", operator);
          gen.writeArrayFieldStart("targets");
          for (String user_param : step.targets.user_params) {
            parameters.add(user_param);
            gen.writeString(user_param);
          }
          gen.writeEndArray();
          if (step.targets.regex != null) {
            gen.writeObjectField("value", step.targets.regex);
          }
        }

        if (step.transformers != null && !step.transformers.isEmpty()) {
          gen.writeArrayFieldStart("transformations");
          for (Object obj : step.transformers) {
            gen.writeObject(obj);
          }
          gen.writeEndArray();
        }

        gen.writeEndObject();
      }
      gen.writeEndArray();
      gen.writeEndObject();
    }
    gen.writeEndArray();

    // Manifest
    gen.writeObjectFieldStart("manifest");
    for (String parameter : parameters) {
      gen.writeObjectFieldStart(parameter);
      gen.writeStringField("inherit_from", parameter);
      gen.writeBooleanField("run_on_value", true);
      gen.writeBooleanField("run_on_key", false);
      gen.writeEndObject();
    }
    gen.writeEndObject();

    gen.writeArrayFieldStart("flows");

    gen.writeStartObject();
    gen.writeStringField("name", "blocking");
    gen.writeArrayFieldStart("steps");

    // Block step
    if (!blockingRulesId.isEmpty()) {
      gen.writeStartObject();
      gen.writeStringField("id", "start");
      gen.writeArrayFieldStart("rule_ids");
      for (String s : blockingRulesId) {
        gen.writeObject(s);
      }
      gen.writeEndArray();
      gen.writeStringField("on_match", "exit_block");
      gen.writeEndObject();
    }

    // Monitor step
    if (!passRulesId.isEmpty()) {
      gen.writeStartObject();
      gen.writeStringField("id", "start");
      gen.writeArrayFieldStart("rule_ids");
      for (String s : passRulesId) {
        gen.writeObject(s);
      }
      gen.writeEndArray();
      gen.writeStringField("on_match", "exit_monitor");
      gen.writeEndObject();
    }

    gen.writeEndArray();
    gen.writeEndObject();

    gen.writeEndArray();
    gen.writeEndObject();
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
