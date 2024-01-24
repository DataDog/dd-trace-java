package datadog.trace.common.sampling;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.ToJson;
import com.squareup.moshi.Types;
import datadog.trace.api.sampling.SamplingRule;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Represents list of Trace Sampling Rules read from JSON. See TRACE_SAMPLING_RULES */
public class TraceSamplingRules {

  public static final TraceSamplingRules EMPTY = new TraceSamplingRules(Collections.emptyList());
  private static final Logger log = LoggerFactory.getLogger(TraceSamplingRules.class);
  private static final Moshi MOSHI = new Moshi.Builder().add(new RuleAdapter()).build();
  private static final ParameterizedType LIST_OF_RULES =
      Types.newParameterizedType(List.class, Rule.class);
  private static final JsonAdapter<List<Rule>> LIST_OF_RULES_ADAPTER = MOSHI.adapter(LIST_OF_RULES);
  private final List<Rule> rules;

  public TraceSamplingRules(List<Rule> rules) {
    this.rules = Collections.unmodifiableList(rules);
  }

  public static TraceSamplingRules deserialize(String json) {
    TraceSamplingRules result = EMPTY;
    try {
      result = filterOutNullRules(LIST_OF_RULES_ADAPTER.fromJson(json));
    } catch (Throwable ex) {
      log.error("Couldn't parse Trace Sampling Rules from JSON: {}", json, ex);
    }
    return result;
  }

  private static TraceSamplingRules filterOutNullRules(List<Rule> rules) {
    if (rules == null || rules.isEmpty()) {
      return EMPTY;
    }
    List<Rule> notNullRules = new ArrayList<>(rules.size());
    for (Rule rule : rules) {
      if (rule != null) {
        notNullRules.add(rule);
      }
    }
    if (notNullRules.isEmpty()) {
      return EMPTY;
    }
    return new TraceSamplingRules(notNullRules);
  }

  public List<Rule> getRules() {
    return rules;
  }

  public boolean isEmpty() {
    return this.rules.isEmpty();
  }

  public static final class Rule implements SamplingRule.TraceSamplingRule {
    private final String service;
    private final String name;
    private final String resource;
    private final Map<String, String> tags;
    private final double sampleRate;

    private Rule(
        String service, String name, String resource, Map<String, String> tags, double sampleRate) {
      this.service = service;
      this.name = name;
      this.resource = resource;
      this.tags = tags;
      this.sampleRate = sampleRate;
    }

    /**
     * Validate and create a {@link Rule} from its {@link JsonRule} representation.
     *
     * @param jsonRule The {@link JsonRule} to validate.
     * @return A {@link Rule} if the {@link JsonRule} is valid, {@code null} otherwise.
     */
    public static Rule create(JsonRule jsonRule) {
      String service = SamplingRule.normalizeGlob(jsonRule.service);
      String name = SamplingRule.normalizeGlob(jsonRule.name);
      String resource = SamplingRule.normalizeGlob(jsonRule.resource);
      Map<String, String> tags = jsonRule.tags;
      if (tags == null) {
        tags = Collections.emptyMap();
      }
      double sampleRate = 1D;
      if (jsonRule.sample_rate != null) {
        try {
          sampleRate = Double.parseDouble(jsonRule.sample_rate);
        } catch (NumberFormatException ex) {
          logRuleError(jsonRule, "sample_rate must be a number between 0.0 and 1.0");
          return null;
        }
        if (sampleRate < 0D || sampleRate > 1D) {
          logRuleError(jsonRule, "sample_rate must be between 0.0 and 1.0");
          return null;
        }
      }
      return new Rule(service, name, resource, tags, sampleRate);
    }

    private static void logRuleError(JsonRule rule, String error) {
      log.error("Skipping invalid Trace Sampling Rule: {} - {}", rule, error);
    }

    @Override
    public String getService() {
      return service;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public String getResource() {
      return resource;
    }

    @Override
    public Map<String, String> getTags() {
      return tags;
    }

    @Override
    public double getSampleRate() {
      return sampleRate;
    }
  }

  private static final class JsonRule {
    private static final JsonAdapter<JsonRule> jsonAdapter = MOSHI.adapter(JsonRule.class);

    String service;
    String name;
    String resource;
    Map<String, String> tags;
    String sample_rate;

    @Override
    public String toString() {
      return jsonAdapter.toJson(this);
    }
  }

  private static final class RuleAdapter {
    @FromJson
    Rule fromJson(JsonRule jsonRule) {
      return Rule.create(jsonRule);
    }

    @ToJson
    JsonRule toJson(Rule rule) {
      // This method only purpose is to prevent a "No @ToJson adapter for class" exception.
      throw new UnsupportedOperationException();
    }
  }
}
