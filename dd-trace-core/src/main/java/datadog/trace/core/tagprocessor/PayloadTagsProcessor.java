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
  public static PayloadTagsProcessor create(Config config) { // TODO test with a config test
    List<RedactionJsonPaths> knownPayloadTagsRedactionPaths = new ArrayList<>();
    if (config.isCloudRequestPayloadTaggingEnabled()) {
      knownPayloadTagsRedactionPaths.add(
          new RedactionJsonPaths.Builder()
              .addRedactionJsonPaths(ConfigDefaults.DEFAULT_CLOUD_PAYLOAD_TAGGING)
              .addRedactionJsonPaths(ConfigDefaults.DEFAULT_CLOUD_REQUEST_PAYLOAD_TAGGING)
              .addRedactionJsonPaths(config.getCloudRequestPayloadTagging())
              .build(PayloadTagsData.KnownPayloadTags.AWS_REQUEST_BODY));
    }
    if (config.isCloudResponsePayloadTaggingEnabled()) {
      knownPayloadTagsRedactionPaths.add(
          new RedactionJsonPaths.Builder()
              .addRedactionJsonPaths(ConfigDefaults.DEFAULT_CLOUD_PAYLOAD_TAGGING)
              .addRedactionJsonPaths(ConfigDefaults.DEFAULT_CLOUD_RESPONSE_PAYLOAD_TAGGING)
              .addRedactionJsonPaths(config.getCloudResponsePayloadTagging())
              .build(PayloadTagsData.KnownPayloadTags.AWS_RESPONSE_BODY));
    }
    if (knownPayloadTagsRedactionPaths.isEmpty()) {
      return null;
    }
    int maxDepth = config.getCloudPayloadTaggingMaxDepth();
    int maxTags = config.getCloudPayloadTaggingMaxTags();
    return new PayloadTagsProcessor(knownPayloadTagsRedactionPaths, maxDepth, maxTags);
  }

  private final List<RedactionJsonPaths> redactionJsonPaths;
  private final int maxDepth;
  private final int maxTags;

  private PayloadTagsProcessor( // TODO test all the other cases via constructor
      List<RedactionJsonPaths> redactionJsonPaths, int maxDepth, int maxTags) {
    this.redactionJsonPaths = redactionJsonPaths;
    this.maxDepth = maxDepth;
    this.maxTags = maxTags;
  }

  @Override
  public Map<String, Object> processTags(Map<String, Object> tags, DDSpanContext spanContext) {
    int maxTagsBudget = maxTags;
    for (RedactionJsonPaths redactionJsonPaths : redactionJsonPaths) {
      String knownPayloadTag = redactionJsonPaths.knownPayloadTag;
      Object tagValue = tags.get(knownPayloadTag);
      if (tagValue instanceof PayloadTagsData) {
        // Remove the PayloadTagsData tag to replace it with post-processed extracted tags.
        tags.remove(knownPayloadTag);
        PayloadTagsData payloadTagsData = (PayloadTagsData) tagValue;
        JsonStreamParser.Visitor visitor =
            new JsonVisitorTagCollector(maxDepth, maxTagsBudget, redactionJsonPaths, tags);
        int numberOfTagsBefore = tags.size();
        processPayloadTags(payloadTagsData, redactionJsonPaths, visitor, tags);
        int numberOfTagsAfter = tags.size();
        maxTagsBudget -= numberOfTagsAfter - numberOfTagsBefore;
      } else if (tagValue != null) {
        log.debug(
            LogCollector.SEND_TELEMETRY,
            "Expected PayloadTagsData for known payload tag '{}', but got '{}'",
            knownPayloadTag,
            tagValue);
      }
    }
    return tags;
  }

  private void processPayloadTags(
      PayloadTagsData payloadTagsData,
      RedactionJsonPaths redactionJsonPaths,
      JsonStreamParser.Visitor visitor,
      Map<String, Object> collectedTags) {
    for (PayloadTagsData.PathAndValue pathAndValue : payloadTagsData.all()) {
      if (pathAndValue.path.length > maxDepth) {
        continue;
      }
      String prefix = redactionJsonPaths.knownPayloadTag;
      PathCursor cursor = new PathCursor(pathAndValue.path, maxDepth);
      if (redactionJsonPaths.findMatching(cursor) != null) {
        collectedTags.put(cursor.toString(prefix), REDACTED);
      } else {
        Object value = pathAndValue.value;
        if (value instanceof InputStream) {
          if (!JsonStreamParser.tryToParse((InputStream) value, visitor, cursor)) {
            collectedTags.put(cursor.toString(prefix), BINARY);
          }
        } else if (value instanceof String) {
          String str = (String) value;
          if (!JsonStreamParser.tryToParse(str, visitor, cursor)) {
            collectedTags.put(cursor.toString(prefix), str);
          }
        } else if (value instanceof Number || value instanceof Boolean) {
          collectedTags.put(cursor.toString(prefix), value);
        } else if (value == null) {
          collectedTags.put(cursor.toString(prefix), null);
        } else {
          collectedTags.put(cursor.toString(prefix), String.valueOf(value));
        }
      }
    }
  }

  /** Contains set of redaction rules per known integration payload tag. */
  private static final class RedactionJsonPaths {

    public static final class Builder {
      private final List<JsonPath> redactionRules = new ArrayList<>();

      public RedactionJsonPaths.Builder addRedactionJsonPaths(List<String> jsonPaths) {
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
            log.warn(
                "Skipping failed to parse redaction jsonpath: '{}'. {}", rule, ex.getMessage());
          }
        }
        return result;
      }

      public RedactionJsonPaths build(String prefix) {
        return new RedactionJsonPaths(prefix, redactionRules);
      }
    }

    private final String knownPayloadTag;
    private final List<JsonPath> paths;

    private RedactionJsonPaths(String knownPayloadTag, List<JsonPath> paths) {
      this.knownPayloadTag = knownPayloadTag;
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
    private final RedactionJsonPaths redactionJsonPaths;
    private final String tagPrefix;

    private final Map<String, Object> collectedTags;

    public JsonVisitorTagCollector(
        int maxDepth,
        int maxTags,
        RedactionJsonPaths redactionJsonPaths,
        Map<String, Object> collectedTags) {
      this.maxDepth = maxDepth;
      this.maxTags = maxTags;
      this.redactionJsonPaths = redactionJsonPaths;
      this.tagPrefix = redactionJsonPaths.knownPayloadTag;
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
      if (redactionJsonPaths.findMatching(path) != null) {
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
