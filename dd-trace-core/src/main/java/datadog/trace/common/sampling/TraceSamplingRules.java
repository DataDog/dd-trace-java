package datadog.trace.common.sampling;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Represents list of Trace Sampling Rules read from JSON. See TRACE_SAMPLING_RULES */
public class TraceSamplingRules {

  private static final Logger log = LoggerFactory.getLogger(TraceSamplingRules.class);

  public static final class Rule {
    public static Rule create(String service, String name, double sampleRate) {
      if (sampleRate < 0 || sampleRate > 1.0) {
        logError(
            service, name, Double.toString(sampleRate), "sample_rate must be between 0.0 and 1.0");
        return null;
      }
      return new Rule(service, name, sampleRate);
    }

    public static Rule create(String service, String name, String sample_rate) {
      if (sample_rate == null) {
        logError(service, name, null, "missing mandatory sample_rate");
        return null;
      }
      try {
        return create(service, name, Double.parseDouble(sample_rate));
      } catch (NumberFormatException ex) {
        logError(service, name, sample_rate, "sample_rate must be a number between 0.0 and 1.0");
        return null;
      }
    }

    private static void logError(String service, String name, String sample_rate, String error) {
      log.error(
          "Skipping invalid Trace Sampling Rule: { \"service\": \""
              + service
              + "\", \"name\": \""
              + name
              + "\", \"sample_rate\": "
              + sample_rate
              + " } - "
              + error);
    }

    private final String service;

    private final String name;

    private final double sampleRate;

    private Rule(String service, String name, double sampleRate) {
      this.service = service;
      this.name = name;
      this.sampleRate = sampleRate;
    }

    public String getService() {
      return service;
    }

    public String getName() {
      return name;
    }

    public double getSampleRate() {
      return sampleRate;
    }
  }

  public static TraceSamplingRules deserialize(String json) {
    try {
      return new TraceSamplingRules(LIST_OF_RULES_ADAPTER.fromJson(json));
    } catch (Throwable ex) {
      log.error("Couldn't parse Trace Sampling Rules from JSON: {}", json, ex);
      return null;
    }
  }

  private static final class JsonRule {
    String service;
    String name;
    String sample_rate;

    private Rule toRule() {
      return Rule.create(service, name, sample_rate);
    }
  }

  private static final class RuleAdapter {
    @FromJson
    Rule fromJson(JsonRule jsonRule) {
      return jsonRule.toRule();
    }
  }

  private static final Moshi MOSHI = new Moshi.Builder().add(new RuleAdapter()).build();

  private static final ParameterizedType LIST_OF_RULES =
      Types.newParameterizedType(List.class, Rule.class);
  private static final JsonAdapter<List<Rule>> LIST_OF_RULES_ADAPTER = MOSHI.adapter(LIST_OF_RULES);

  private final List<Rule> rules;

  public TraceSamplingRules(List<Rule> rules) {
    List<Rule> notNullRules = new ArrayList<>(rules.size());
    for (Rule rule : rules) {
      if (rule != null) {
        notNullRules.add(rule);
      }
    }
    this.rules = notNullRules;
  }

  public List<Rule> getRules() {
    return Collections.unmodifiableList(rules);
  }

  public boolean isEmpty() {
    return rules.isEmpty();
  }
}
