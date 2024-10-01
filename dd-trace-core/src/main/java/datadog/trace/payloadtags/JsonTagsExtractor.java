package datadog.trace.payloadtags;

import datadog.trace.payloadtags.json.JsonPath;
import datadog.trace.payloadtags.json.JsonPathParser;
import datadog.trace.payloadtags.json.JsonPointer;
import datadog.trace.payloadtags.json.JsonStreamTraversal;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JsonTagsExtractor {

  static final Logger log = LoggerFactory.getLogger(JsonTagsExtractor.class);

  public static final class Builder {
    private final List<JsonPath> redactionRules = new ArrayList<>();
    private int tagsLimit = 758;
    private int depthLimit = 10;

    public Builder addRedactionRules(List<String> rules) {
      this.redactionRules.addAll(parseRules(rules));
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
          log.debug("Skipping failed to parse JSONPath rule: '{}'. {}", rule, ex.getMessage());
        }
      }
      return result;
    }

    public Builder tagsLimit(int value) {
      this.tagsLimit = value;
      return this;
    }

    public Builder depthLimit(int value) {
      this.depthLimit = value;
      return this;
    }

    public JsonTagsExtractor build() {
      return new JsonTagsExtractor(redactionRules, tagsLimit, depthLimit);
    }
  }

  public static final String REDACTED = "redacted";
  private static final String DD_PAYLOAD_TAGS_INCOMPLETE = "_dd.payload_tags_incomplete";

  private final List<JsonPath> redactionRules;
  private final int tagsLimit;
  private final int depthLimit;

  private JsonTagsExtractor(List<JsonPath> redactionRules, int tagsLimit, int depthLimit) {
    this.redactionRules = redactionRules;
    this.tagsLimit = tagsLimit;
    this.depthLimit = depthLimit;
  }

  public Map<String, Object> extractTags(InputStream is, String tagPrefix) {
    final Map<String, Object> tags = new java.util.LinkedHashMap<>();

    JsonStreamTraversal.Visitor visitor = new JsonVisitorTagCollector(tagPrefix, tags);

    try {
      JsonStreamTraversal.traverse(is, visitor, depthLimit);
    } catch (IOException e) {
      log.debug("Failed to process JSON payload. {}", e.getMessage());
      return Collections.emptyMap();
    }

    return tags;
  }

  private final class JsonVisitorTagCollector implements JsonStreamTraversal.Visitor {
    private final String tagPrefix;
    private final int tagsLimit;
    private final int depthLimit;

    private final Map<String, Object> collectedTags;
    private boolean stopFlag;

    private JsonVisitorTagCollector(String tagPrefix, Map<String, Object> collectedTags) {
      this.tagPrefix = tagPrefix;
      this.depthLimit = JsonTagsExtractor.this.depthLimit;
      this.tagsLimit = JsonTagsExtractor.this.tagsLimit;
      this.collectedTags = collectedTags;
    }

    @Override
    public boolean skipInner(JsonPointer pointer) {
      return pointer.length() > depthLimit;
    }

    @Override
    public boolean visitValue(JsonPointer pointer) {
      if (findMatchingRedactionRule(pointer) != null) {
        collectedTags.put(pointer.dotted(tagPrefix), REDACTED);
        return false;
      }
      return true;
    }

    private JsonPath findMatchingRedactionRule(JsonPointer pointer) {
      log.debug("Checking redaction rules for path: {}", pointer.dotted("$"));
      for (JsonPath rule : redactionRules) {
        if (rule.matches(pointer)) {
          return rule;
        }
      }
      return null;
    }

    @Override
    public void valueVisited(JsonPointer pointer, Object value) {
      if (value == null) {
        // convert `null` to a string, otherwise it won't be set as a tag value
        value = "null";
      }
      if (collectedTags.size() < tagsLimit) {
        collectedTags.put(pointer.dotted(tagPrefix), value);
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
    public boolean expandValue(JsonPointer pointer, String raw) {
      // try to expand JSON string value if it looks like a JSON object or array
      return raw.startsWith("{") && raw.endsWith("}") || raw.startsWith("[") && raw.endsWith("]");
    }

    @Override
    public void expandValueFailed(JsonPointer pointer, String raw, Exception e) {
      // keep the original string value if it's not a valid JSON object or array
      valueVisited(pointer, raw);
    }
  }
}
