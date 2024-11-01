package datadog.trace.core.tagprocessor;

import datadog.trace.api.Config;
import datadog.trace.api.ConfigDefaults;
import datadog.trace.api.telemetry.LogCollector;
import datadog.trace.core.DDSpanContext;
import datadog.trace.payloadtags.PayloadTagsData;
import datadog.trace.payloadtags.json.JsonPath;
import datadog.trace.payloadtags.json.JsonPathParser;
import datadog.trace.payloadtags.json.JsonStreamParser;
import datadog.trace.payloadtags.json.PathCursor;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Post-processor that extracts tags from payload paths injected as tags by instrumentations. */
public final class PayloadTagsProcessor implements TagsPostProcessor {
  private static final Logger log = LoggerFactory.getLogger(PayloadTagsProcessor.class);

  private static final String REDACTED = "redacted";
  private static final String BINARY = "<binary>";
  private static final String DD_PAYLOAD_TAGS_INCOMPLETE = "_dd.payload_tags_incomplete";

  @Nullable
  public static PayloadTagsProcessor create(Config config) {
    // TODO test with a config test
    Map<String, RedactionRules> redactionRulesByTagPrefix = new HashMap<>();
    if (config.isCloudRequestPayloadTaggingEnabled()) {
      redactionRulesByTagPrefix.put(
          PayloadTagsData.KnownPayloadTags.AWS_REQUEST_BODY,
          new RedactionRules.Builder()
              .addRedactionJsonPaths(ConfigDefaults.DEFAULT_CLOUD_PAYLOAD_TAGGING)
              .addRedactionJsonPaths(ConfigDefaults.DEFAULT_CLOUD_REQUEST_PAYLOAD_TAGGING)
              .addRedactionJsonPaths(config.getCloudRequestPayloadTagging())
              .build());
    }
    if (config.isCloudResponsePayloadTaggingEnabled()) {
      redactionRulesByTagPrefix.put(
          PayloadTagsData.KnownPayloadTags.AWS_RESPONSE_BODY,
          new RedactionRules.Builder()
              .addRedactionJsonPaths(ConfigDefaults.DEFAULT_CLOUD_PAYLOAD_TAGGING)
              .addRedactionJsonPaths(ConfigDefaults.DEFAULT_CLOUD_RESPONSE_PAYLOAD_TAGGING)
              .addRedactionJsonPaths(config.getCloudResponsePayloadTagging())
              .build());
    }
    if (redactionRulesByTagPrefix.isEmpty()) {
      return null;
    }
    int maxDepth = config.getCloudPayloadTaggingMaxDepth();
    int maxTags = config.getCloudPayloadTaggingMaxTags();
    return new PayloadTagsProcessor(redactionRulesByTagPrefix, maxDepth, maxTags);
  }

  private final Map<String, RedactionRules> redactionRulesByTagPrefix;
  private final int maxDepth;
  private final int maxTags;

  private PayloadTagsProcessor(
      // TODO test all the other cases via constructor
      Map<String, RedactionRules> redactionRulesByTagPrefix, int maxDepth, int maxTags) {
    this.redactionRulesByTagPrefix = redactionRulesByTagPrefix;
    this.maxDepth = maxDepth;
    this.maxTags = maxTags;
  }

  @Override
  public Map<String, Object> processTags(Map<String, Object> spanTags, DDSpanContext spanContext) {
    Map<String, Object> collectedTags =
        new HashMap<>(); // TODO avoid allocation by inlining visitor
    for (Map.Entry<String, RedactionRules> tagPrefixRedactionRules :
        redactionRulesByTagPrefix.entrySet()) {
      String tagPrefix = tagPrefixRedactionRules.getKey();
      RedactionRules redactionRules = tagPrefixRedactionRules.getValue();
      Object tagValue = spanTags.get(tagPrefix);
      if (tagValue instanceof PayloadTagsData) {
        PayloadTagsData payloadTagsData = (PayloadTagsData) tagValue;
        JsonStreamParser.Visitor visitor = // TODO avoid allocation by inlining visitor
            new JsonVisitorTagCollector(
                maxDepth, maxTags, redactionRules, tagPrefix, collectedTags);
        collectPayloadTags(payloadTagsData, redactionRules, tagPrefix, visitor, collectedTags);
        spanTags.putAll(collectedTags);
        spanTags.remove(tagPrefix);
      } else if (tagValue != null) {
        log.debug(
            LogCollector.SEND_TELEMETRY,
            "Expected PayloadTagsData for known payload tag '{}', but got '{}'",
            tagPrefix,
            tagValue);
      }
    }
    return spanTags;
  }

  private void collectPayloadTags(
      PayloadTagsData payloadTagsData,
      RedactionRules redactionRules,
      String tagPrefix,
      JsonStreamParser.Visitor visitor,
      Map<String, Object> collectedTags) {
    for (PayloadTagsData.PathAndValue pathAndValue : payloadTagsData.all()) {
      if (pathAndValue.path.length > maxDepth) {
        continue;
      }
      if (collectedTags.size() >= maxTags) {
        collectedTags.put(DD_PAYLOAD_TAGS_INCOMPLETE, true);
        break;
      }
      PathCursor cursor = new PathCursor(pathAndValue.path, maxDepth);
      if (redactionRules.findMatching(cursor) != null) {
        collectedTags.put(cursor.toString(tagPrefix), REDACTED);
      } else {
        Object value = pathAndValue.value;
        if (value instanceof InputStream) {
          if (!JsonStreamParser.tryToParse((InputStream) value, visitor, cursor)) {
            collectedTags.put(cursor.toString(tagPrefix), BINARY);
          }
        } else if (value instanceof String) {
          String str = (String) value;
          if (!JsonStreamParser.tryToParse(str, visitor, cursor)) {
            collectedTags.put(cursor.toString(tagPrefix), str);
          }
        } else if (value instanceof Number || value instanceof Boolean) {
          collectedTags.put(cursor.toString(tagPrefix), value);
        } else if (value == null) {
          collectedTags.put(cursor.toString(tagPrefix), null);
        } else {
          collectedTags.put(cursor.toString(tagPrefix), String.valueOf(value));
        }
      }
    }
  }

  private static final class RedactionRules {

    public static final class Builder {
      private final List<JsonPath> redactionRules = new ArrayList<>();

      public RedactionRules.Builder addRedactionJsonPaths(List<String> jsonPaths) {
        this.redactionRules.addAll(parseJsonPaths(jsonPaths));
        return this;
      }

      private static List<JsonPath> parseJsonPaths(List<String> rules) {
        if (rules.isEmpty() || rules.size() == 1 && rules.get(0).equalsIgnoreCase("all")) {
          return Collections.emptyList();
        }
        List<JsonPath> result = new ArrayList<>(rules.size());
        for (String rule : rules) {
          try {
            JsonPath jp = JsonPathParser.parse(rule);
            result.add(jp);
          } catch (Exception ex) {
            log.warn("Skipping failed to parse redaction rule '{}'. {}", rule, ex.getMessage());
          }
        }
        return result;
      }

      public RedactionRules build() {
        return new RedactionRules(redactionRules);
      }
    }

    private final List<JsonPath> paths;

    private RedactionRules(List<JsonPath> paths) {
      this.paths = paths;
    }

    public JsonPath findMatching(PathCursor pathCursor) {
      for (JsonPath jp : paths) {
        if (jp.matches(pathCursor)) {
          return jp;
        }
      }
      return null;
    }
  }

  /**
   * Collects tags by walking through JSON structure and extracting values from it. Controls number
   * of collected tags and depth of traversal. Redacts values that match redaction paths.
   */
  private static final class JsonVisitorTagCollector implements JsonStreamParser.Visitor {
    private final int maxTags;
    private final int maxDepth;
    private final RedactionRules redactionRules;
    private final String tagPrefix;

    private final Map<String, Object> collectedTags;

    public JsonVisitorTagCollector(
        int maxDepth,
        int maxTags,
        RedactionRules redactionRules,
        String tagPrefix,
        Map<String, Object> collectedTags) {
      this.maxDepth = maxDepth;
      this.maxTags = maxTags;
      this.redactionRules = redactionRules;
      this.tagPrefix = tagPrefix;
      this.collectedTags = collectedTags;
    }

    @Override
    public boolean visitCompound(PathCursor path) {
      if (path.levels() < maxDepth) {
        return redactIfMatchesAnyJsonPaths(path);
      }
      return false;
    }

    @Override
    public boolean visitPrimitive(PathCursor path) {
      return redactIfMatchesAnyJsonPaths(path);
    }

    private boolean redactIfMatchesAnyJsonPaths(PathCursor path) {
      if (redactionRules.findMatching(path) != null) {
        collectedTags.put(path.toString(tagPrefix), REDACTED);
        return false;
      }
      return true;
    }

    @Override
    public void booleanValue(PathCursor path, boolean value) {
      collectedTags.put(path.toString(tagPrefix), value);
    }

    @Override
    public void stringValue(PathCursor path, String value) {
      collectedTags.put(path.toString(tagPrefix), value);
    }

    @Override
    public void intValue(PathCursor path, int value) {
      collectedTags.put(path.toString(tagPrefix), value);
    }

    @Override
    public void longValue(PathCursor path, long value) {
      collectedTags.put(path.toString(tagPrefix), value);
    }

    @Override
    public void doubleValue(PathCursor path, double value) {
      collectedTags.put(path.toString(tagPrefix), value);
    }

    @Override
    public void nullValue(PathCursor path) {
      collectedTags.put(path.toString(tagPrefix), null);
    }

    @Override
    public boolean keepParsing(PathCursor path) {
      if (collectedTags.size() < maxTags) {
        return true;
      }
      collectedTags.put(DD_PAYLOAD_TAGS_INCOMPLETE, true);
      return false;
    }

    @Override
    public void expandValueFailed(PathCursor path, Exception exception) {
      log.debug("Failed to expand value at path '{}'", path.toString(""), exception);
    }
  }
}
