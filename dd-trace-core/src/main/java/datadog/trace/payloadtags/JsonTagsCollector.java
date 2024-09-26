package datadog.trace.payloadtags;

import datadog.trace.payloadtags.json.JsonPath;
import datadog.trace.payloadtags.json.JsonPathParser;
import datadog.trace.payloadtags.json.JsonStreamTraversal;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JsonTagsCollector {

  static final Logger log = LoggerFactory.getLogger(JsonTagsCollector.class);

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
          log.debug("Skipping failed to parse JSON path rule: '{}'. {}", rule, ex.getMessage());
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

    public JsonTagsCollector build() {
      return new JsonTagsCollector(redactionRules, limitTags, limitDeepness);
    }
  }

  public static final String REDACTED = "redacted";
  private static final String DD_PAYLOAD_TAGS_INCOMPLETE = "_dd.payload_tags_incomplete";

  private final List<JsonPath> redactionRules;
  private final int tagsLimit;
  private final int depthLimit;

  private JsonTagsCollector(List<JsonPath> redactionRules, int tagsLimit, int depthLimit) {
    this.redactionRules = redactionRules;
    this.tagsLimit = tagsLimit;
    this.depthLimit = depthLimit;
  }

  public Map<String, Object> process(InputStream is, String tagPrefix) {
    final Map<String, Object> collectedTags = new java.util.LinkedHashMap<>();

    JsonStreamTraversal.Visitor visitor = new JsonVisitorTagCollector(tagPrefix, collectedTags);

    try {
      JsonStreamTraversal.traverse(is, visitor);
    } catch (IOException e) {
      log.debug("Failed to process JSON payload. {}", e.getMessage());
      return Collections.emptyMap();
    }

    return collectedTags;
  }

  private final class JsonVisitorTagCollector implements JsonStreamTraversal.Visitor {
    private final StringBuilder tagPrefix;
    private final int tagsLimit;
    private final int depthLimit;

    private final Map<String, Object> collectedTags;
    private boolean stopFlag;

    private JsonVisitorTagCollector(String tagPrefix, Map<String, Object> collectedTags) {
      this.depthLimit = JsonTagsCollector.this.depthLimit;
      this.tagsLimit = JsonTagsCollector.this.tagsLimit;
      this.tagPrefix = new StringBuilder(tagPrefix);
      this.collectedTags = collectedTags;
    }

    @Override
    public boolean visitObject(JsonPath.Builder path) {
      return path.length() < depthLimit;
    }

    @Override
    public boolean visitValue(JsonPath path) {
      if (findMatchingRedactionRule(path) != null) {
        collectedTags.put(path.dotted(tagPrefix), REDACTED);
        return false;
      }
      return true;
    }

    private JsonPath findMatchingRedactionRule(JsonPath path) {
      for (JsonPath rule : redactionRules) {
        if (rule.matches(path)) {
          return rule;
        }
      }
      return null;
    }

    @Override
    public void valueVisited(JsonPath path, Object value) {
      if (value == null) {
        // convert `null` to a string, otherwise it won't be set as a tag value
        value = "null";
      }
      if (collectedTags.size() < tagsLimit) {
        collectedTags.put(path.dotted(tagPrefix), value);
      } else {
        collectedTags.put(DD_PAYLOAD_TAGS_INCOMPLETE, true);
        stopFlag = true;
      }
    }

    @Override
    public boolean keepTraversing() {
      return !stopFlag;
    }

    @Override
    public boolean expandValue(JsonPath path, String raw) {
      // try to expand JSON string value if it looks like a JSON object or array
      return raw.startsWith("{") && raw.endsWith("}") || raw.startsWith("[") && raw.endsWith("]");
    }

    @Override
    public void expandValueFailed(JsonPath path, String raw, Exception e) {
      // keep the original string value if it's not a valid JSON object or array
      valueVisited(path, raw);
    }
  }
}
