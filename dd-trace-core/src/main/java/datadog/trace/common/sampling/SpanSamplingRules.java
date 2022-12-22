package datadog.trace.common.sampling;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import okio.Okio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Represents list of Span Sampling Rules read from JSON. See SPAN_SAMPLING_RULES */
public class SpanSamplingRules {

  private static final Logger log = LoggerFactory.getLogger(SpanSamplingRules.class);

  public static final class Rule {
    public static Rule create(
        String service, String name, Double sampleRate, Integer maxPerSecond) {
      if (sampleRate == null) {
        sampleRate = 1.0;
      } else if (sampleRate < 0 || sampleRate > 1.0) {
        logError(
            service,
            name,
            Double.toString(sampleRate),
            maxPerSecond == null ? null : Double.toString(maxPerSecond),
            "sample_rate must be between 0.0 and 1.0");
        return null;
      }
      if (maxPerSecond == null) {
        maxPerSecond = Integer.MAX_VALUE;
      } else if (maxPerSecond < 1) {
        logError(
            service,
            name,
            Double.toString(sampleRate),
            Double.toString(maxPerSecond),
            "max_per_second must be greater than zero");
        return null;
      }
      return new Rule(service, name, sampleRate, maxPerSecond);
    }

    public static Rule create(String service, String name, String sampleRate, String maxPerSecond) {
      double sampleRateParsed = 1.0;
      if (sampleRate != null) {
        try {
          sampleRateParsed = Double.parseDouble(sampleRate);
        } catch (NumberFormatException ex) {
          logError(
              service,
              name,
              sampleRate,
              maxPerSecond,
              "sample_rate must be a number between 0.0 and 1.0");
          return null;
        }
      }
      Integer maxPerSecondParsed = null;
      if (maxPerSecond != null) {
        try {
          maxPerSecondParsed = Integer.parseInt(maxPerSecond);
        } catch (NumberFormatException ex) {
          logError(
              service, name, sampleRate, maxPerSecond, "max_per_second must be greater than zero");
          return null;
        }
      }
      return create(service, name, sampleRateParsed, maxPerSecondParsed);
    }

    private static void logError(
        String service, String name, String sampleRate, String maxPerSecond, String error) {
      log.error(
          "Skipping invalid Trace Sampling Rule: { \"service\": \""
              + service
              + "\", \"name\": \""
              + name
              + "\", \"sample_rate\": "
              + sampleRate
              + "\", \"max_per_second\": "
              + maxPerSecond
              + " } - "
              + error);
    }

    private final String service;

    private final String name;

    private final double sampleRate;
    private final int maxPerSecond;

    private Rule(String service, String name, double sampleRate, int maxPerSecond) {
      this.service = service;
      this.name = name;
      this.sampleRate = sampleRate;
      this.maxPerSecond = maxPerSecond;
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

    public int getMaxPerSecond() {
      return maxPerSecond;
    }
  }

  public static SpanSamplingRules deserialize(String json) {
    try {
      List<Rule> rules = LIST_OF_RULES_ADAPTER.fromJson(json);
      return filterOutNullRules(rules);
    } catch (Throwable ex) {
      log.error("Couldn't parse Span Sampling Rules from JSON: {}", json, ex);
      return null;
    }
  }

  public static SpanSamplingRules deserializeFile(String jsonFile) {
    try (JsonReader reader = JsonReader.of(Okio.buffer(Okio.source(new File(jsonFile))))) {
      List<Rule> rules = LIST_OF_RULES_ADAPTER.fromJson(reader);
      return filterOutNullRules(rules);
    } catch (FileNotFoundException e) {
      log.warn("Span sampling rules file {} doesn't exit", jsonFile);
    } catch (IOException e) {
      log.error("Couldn't read Span sampling rules file {}. Failed with {}", jsonFile, e);
    } catch (Throwable ex) {
      log.error(
          "Couldn't parse Span Sampling Rules from JSON file {}. Failed with {}", jsonFile, ex);
    }
    return null;
  }

  private static SpanSamplingRules filterOutNullRules(List<Rule> rules) {
    if (rules == null) {
      return null;
    }
    List<Rule> notNullRules = new ArrayList<>(rules.size());
    for (Rule rule : rules) {
      if (rule != null) {
        notNullRules.add(rule);
      }
    }
    if (notNullRules.isEmpty()) {
      return null;
    }
    return new SpanSamplingRules(notNullRules);
  }

  private static final class JsonRule {
    String service;
    String name;
    String sample_rate;
    String max_per_second;

    private Rule toRule() {
      return Rule.create(service, name, sample_rate, max_per_second);
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

  public SpanSamplingRules(List<Rule> rules) {
    this.rules = rules;
  }

  public List<Rule> getRules() {
    return Collections.unmodifiableList(rules);
  }

  public boolean isEmpty() {
    return rules.isEmpty();
  }
}
