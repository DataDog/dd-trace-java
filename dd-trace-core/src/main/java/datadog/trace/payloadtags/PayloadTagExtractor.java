package datadog.trace.payloadtags;

import datadog.trace.payloadtags.json.JsonPath;
import datadog.trace.payloadtags.json.JsonPathParser;
import datadog.trace.payloadtags.json.JsonStreamTagCollector;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PayloadTagExtractor {

  static final Logger log = LoggerFactory.getLogger(PayloadTagExtractor.class);

  public static final class Builder {
    private List<JsonPath> redactionRules = Collections.emptyList();
    private int limitTags = 784;
    private int limitDeepness = 10;

    public Builder parseRedactionRules(List<String> rules) {
      this.redactionRules = parseRules(rules);
      return this;
    }

    private static List<JsonPath> parseRules(List<String> rules) {
      if (rules.isEmpty() || rules.size() == 1 && rules.get(0).equalsIgnoreCase("all")) {
        return Collections.emptyList();
      }
      List<JsonPath> result = new ArrayList<>(rules.size());
      for (String rule : rules) {
        try {
          JsonPath jp = JsonPathParser.parse(rule);
          result.add(jp);
        } catch (Exception ex) {
          log.debug("Skipping failed to parse JSON path rule: '{}'", rule, ex);
        }
      }
      return result;
    }

    public Builder limitTags(int limitTags) {
      this.limitTags = limitTags;
      return this;
    }

    public Builder limitDeepness(int limitDeepness) {
      this.limitDeepness = limitDeepness;
      return this;
    }

    public PayloadTagExtractor build() {
      return new PayloadTagExtractor(redactionRules, limitTags, limitDeepness);
    }
  }

  private final List<JsonPath> redactionRules;
  private final int tagsLimit;
  private final int depthLimit;

  private PayloadTagExtractor(List<JsonPath> redactionRules, int tagsLimit, int depthLimit) {
    this.redactionRules = redactionRules;
    this.tagsLimit = tagsLimit;
    this.depthLimit = depthLimit;
  }

  public Map<String, Object> process(InputStream is, String tagPrefix) {

    try {
      return JsonStreamTagCollector.collectTagsFromJson(
          is, this::redact, tagPrefix, tagsLimit, depthLimit);
    } catch (IOException e) {
      log.debug("Failed to process JSON payload", e); // TODO add more details?
    }

    return Collections.emptyMap();
  }

  private boolean redact(JsonPath path) {
    for (JsonPath rule : redactionRules) {
      if (rule.matches(path)) {
        return true;
      }
    }
    return false;
  }
}
