package datadog.trace.common.sampling;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.ToJson;
import com.squareup.moshi.Types;
import datadog.trace.api.sampling.SamplingRule;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import okio.Okio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Represents list of Span Sampling Rules read from JSON. See SPAN_SAMPLING_RULES */
public class SpanSamplingRules {
  public static final SpanSamplingRules EMPTY = new SpanSamplingRules(Collections.emptyList());
  private static final Logger log = LoggerFactory.getLogger(SpanSamplingRules.class);
  private static final Moshi MOSHI = new Moshi.Builder().add(new RuleAdapter()).build();
  private static final ParameterizedType LIST_OF_RULES =
      Types.newParameterizedType(List.class, Rule.class);
  private static final JsonAdapter<List<Rule>> LIST_OF_RULES_ADAPTER = MOSHI.adapter(LIST_OF_RULES);
  private final List<Rule> rules;

  public SpanSamplingRules(List<Rule> rules) {
    this.rules = Collections.unmodifiableList(rules);
  }

  public static SpanSamplingRules deserialize(String json) {
    SpanSamplingRules result = EMPTY;
    try {
      result = filterOutNullRules(LIST_OF_RULES_ADAPTER.fromJson(json));
    } catch (Throwable ex) {
      log.error("Couldn't parse Span Sampling Rules from JSON: {}", json, ex);
    }
    return result;
  }

  public static SpanSamplingRules deserializeFile(String jsonFile) {
    SpanSamplingRules result = EMPTY;
    try (JsonReader reader = JsonReader.of(Okio.buffer(Okio.source(new File(jsonFile))))) {
      result = filterOutNullRules(LIST_OF_RULES_ADAPTER.fromJson(reader));
    } catch (FileNotFoundException e) {
      log.warn("Span Sampling Rules file {} doesn't exist", jsonFile);
    } catch (IOException e) {
      log.error("Couldn't read Span Sampling Rules file {}.", jsonFile, e);
    } catch (Throwable ex) {
      log.error("Couldn't parse Span Sampling Rules from JSON file {}.", jsonFile, ex);
    }
    return result;
  }

  private static SpanSamplingRules filterOutNullRules(List<Rule> rules) {
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
    return new SpanSamplingRules(notNullRules);
  }

  public List<Rule> getRules() {
    return rules;
  }

  public boolean isEmpty() {
    return rules.isEmpty();
  }

  public static final class Rule implements SamplingRule.SpanSamplingRule {
    private final String service;
    private final String name;
    private final String resource;
    private final Map<String, String> tags;
    private final double sampleRate;
    private final int maxPerSecond;

    private Rule(
        String service,
        String name,
        String resource,
        Map<String, String> tags,
        double sampleRate,
        int maxPerSecond) {
      this.service = service;
      this.name = name;
      this.resource = resource;
      this.tags = tags;
      this.sampleRate = sampleRate;
      this.maxPerSecond = maxPerSecond;
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
      int maxPerSecond = Integer.MAX_VALUE;
      if (jsonRule.max_per_second != null) {
        try {
          double parsedMaxPerSeconds = Double.parseDouble(jsonRule.max_per_second);
          if (parsedMaxPerSeconds <= 0) {
            logRuleError(jsonRule, "max_per_second must be greater than 0.0");
            return null;
          }
          maxPerSecond = Math.max((int) parsedMaxPerSeconds, 1);
        } catch (NumberFormatException ex) {
          logRuleError(jsonRule, "max_per_second must be a number greater than 0.0");
          return null;
        }
      }
      return new Rule(service, name, resource, tags, sampleRate, maxPerSecond);
    }

    private static void logRuleError(JsonRule rule, String error) {
      log.error("Skipping invalid Span Sampling Rule: {} - {}", rule, error);
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

    @Override
    public int getMaxPerSecond() {
      return maxPerSecond;
    }
  }

  private static final class JsonRule {
    private static final JsonAdapter<JsonRule> jsonAdapter = MOSHI.adapter(JsonRule.class);

    String service;
    String name;
    String resource;
    Map<String, String> tags;
    String sample_rate; // Use String to be able to map int as double
    String max_per_second; // Use String to be able to map int as double

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
    JsonRule toJson(TraceSamplingRules.Rule rule) {
      // This method only purpose is to prevent a "No @ToJson adapter for class" exception.
      throw new UnsupportedOperationException();
    }
  }
}
