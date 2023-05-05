package datadog.trace.common.sampling;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.api.sampling.SamplingRule;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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
    List<Rule> notNullRules = new ArrayList<>(rules.size());
    for (Rule rule : rules) {
      if (rule != null) {
        notNullRules.add(rule);
      }
    }
    this.rules = notNullRules;
  }

  public static TraceSamplingRules deserialize(String json) {
    TraceSamplingRules result = TraceSamplingRules.EMPTY;
    try {
      List<Rule> rules = LIST_OF_RULES_ADAPTER.fromJson(json);
      if (rules != null) {
        result = new TraceSamplingRules(rules);
      }
    } catch (Throwable ex) {
      log.error("Couldn't parse Trace Sampling Rules from JSON: {}", json, ex);
    }
    return result;
  }

  public List<Rule> getRules() {
    return Collections.unmodifiableList(this.rules);
  }

  public boolean isEmpty() {
    return this.rules.isEmpty();
  }

  public static final class Rule implements SamplingRule.TraceSamplingRule {
    private final String service;
    private final String name;
    private final String resource;
    private final Map<String, String> tags;
    private final TargetSpan targetSpan;
    private final double sampleRate;

    private Rule(
        String service,
        String name,
        String resource,
        Map<String, String> tags,
        TargetSpan targetSpan,
        double sampleRate) {
      this.service = service;
      this.name = name;
      this.resource = resource;
      this.tags = tags;
      this.targetSpan = targetSpan;
      this.sampleRate = sampleRate;
    }

    /**
     * Validate and create a {@link Rule} from its {@link JsonRule} representation.
     *
     * @param jsonRule The {@link JsonRule} to validate.
     * @return A {@link Rule} if the {@link JsonRule} is valid, {@code null} otherwise.
     */
    public static Rule create(JsonRule jsonRule) {
      // Validate service name
      String service = jsonRule.service;
      if (service == null || MATCH_ALL.equals(service)) {
        service = MATCH_ALL;
      }
      // Validate operation name
      String name = jsonRule.name;
      if (name == null || MATCH_ALL.equals(name)) {
        name = MATCH_ALL;
      }
      // Validate resource name
      String resource = jsonRule.resource;
      if (resource == null || MATCH_ALL.equals(resource)) {
        resource = MATCH_ALL;
      }
      // Validate tags
      Map<String, String> tags = jsonRule.tags;
      if (tags == null) {
        tags = Collections.emptyMap();
      }
      // Validate target_span
      TargetSpan targetSpan = TargetSpan.ROOT;
      if (jsonRule.target_span != null) {
        try {
          targetSpan = TargetSpan.valueOf(jsonRule.target_span.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
          logRuleError(jsonRule, "target_span must be either \"root\" or \"any\"");
          return null;
        }
      }
      // Validate sample_rate
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
      return new Rule(service, name, resource, tags, targetSpan, sampleRate);
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
    public TargetSpan getTargetSpan() {
      return targetSpan;
    }

    @Override
    public double getSampleRate() {
      return sampleRate;
    }
  }

  private static final class JsonRule {
    String service;
    String name;
    String resource;
    Map<String, String> tags;
    String target_span;
    String sample_rate;

    @Override
    public String toString() {
      StringBuilder tags = null;
      if (this.tags != null) {
        tags = new StringBuilder("{");
        for (Map.Entry<String, String> entry : this.tags.entrySet()) {
          tags.append("\"").append(entry.getKey()).append("\": ").append(entry.getValue());
        }
        tags.append("}");
      }
      return "{"
          + (this.service == null ? "" : "\"service:\" " + this.service + ",")
          + (this.name == null ? "" : "\"name:\" " + this.name + ",")
          + (this.resource == null ? "" : "\"resource:\" " + this.resource + ",")
          + (tags == null ? "" : "\"tags\": " + tags)
          + (this.target_span == null ? "" : "\"target_span:\" " + this.target_span + ",")
          + (this.sample_rate == null ? "" : "\"sample_rate:\" " + this.sample_rate + ",")
          + "}";
    }
  }

  private static final class RuleAdapter {
    @FromJson
    Rule fromJson(JsonRule jsonRule) {
      return Rule.create(jsonRule);
    }
  }
}
