package datadog.trace.core.tagprocessor;

import datadog.trace.api.Config;
import datadog.trace.api.ConfigDefaults;
import datadog.trace.api.telemetry.LogCollector;
import datadog.trace.core.DDSpanContext;
import datadog.trace.payloadtags.PathCursor;
import datadog.trace.payloadtags.PayloadTagsData;
import datadog.trace.payloadtags.json.JsonPath;
import datadog.trace.payloadtags.json.JsonPathParser;
import datadog.trace.payloadtags.json.JsonStreamParser;
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
    int depthLimit = config.getCloudPayloadTaggingMaxDepth();
    int tagsLimit = config.getCloudPayloadTaggingMaxTags();
    return new PayloadTagsProcessor(knownPayloadTagsRedactionPaths, depthLimit, tagsLimit);
  }

  private final List<RedactionJsonPaths> redactionJsonPaths;
  private final int depthLimit;
  private final int maxTags;

  private PayloadTagsProcessor( // TODO test all the other cases via constructor
      List<RedactionJsonPaths> redactionJsonPaths, int depthLimit, int maxTags) {
    this.redactionJsonPaths = redactionJsonPaths;
    this.depthLimit = depthLimit;
    this.maxTags = maxTags;
  }

  @Override
  public Map<String, Object> processTags(Map<String, Object> tags, DDSpanContext spanContext) {
    for (RedactionJsonPaths redactionJsonPaths : redactionJsonPaths) {
      String knownPayloadTag = redactionJsonPaths.knownPayloadTag;
      Object tagValue = tags.get(knownPayloadTag);
      if (tagValue instanceof PayloadTagsData) {
        // remove payload path data tag and replace with  post-processed extracted tags
        tags.remove(knownPayloadTag);
        PayloadTagsData payloadTagsData = (PayloadTagsData) tagValue;
        // TODO calc local limit taking into account already collected tags
        JsonStreamParser.Visitor visitor =
            new JsonVisitorTagCollector(depthLimit, maxTags, redactionJsonPaths, tags);
        processPayloadTags(payloadTagsData, redactionJsonPaths, visitor, tags);
      } else {
        log.debug(
            LogCollector.SEND_TELEMETRY,
            "Expected PayloadTagsData for known payload tag '{}', but got '{}'",
            knownPayloadTag,
            tagValue);
      }
    }
    return tags;
  }

  private static void processPayloadTags(
      PayloadTagsData payloadTagsData,
      RedactionJsonPaths redactionJsonPaths,
      JsonStreamParser.Visitor visitor,
      Map<String, Object> collectedTags) {
    for (PathCursor cursor : payloadTagsData.all()) {
      String prefix = redactionJsonPaths.knownPayloadTag;
      if (redactionJsonPaths.findMatching(cursor) != null) {
        collectedTags.put(cursor.dotted(prefix), REDACTED);
      } else {
        Object value = cursor.attachedValue();
        if (value instanceof InputStream) {
          // try to parse the input stream as JSON if it starts like a JSON object or array
          if (!JsonStreamParser.tryToParse((InputStream) value, visitor, cursor)) {
            // if failed to parse, use predefined binary tag value
            collectedTags.put(cursor.dotted(prefix), BINARY);
          }
        } else if (value instanceof String) {
          String str = (String) value;
          // try to parse a string as JSON if it looks like a JSON object or array
          if (!JsonStreamParser.tryToParse(str, visitor, cursor)) {
            collectedTags.put(cursor.dotted(prefix), str);
          }
        } else if (value instanceof Number || value instanceof Boolean) {
          // use numbers and booleans as-is for tags
          collectedTags.put(cursor.dotted(prefix), value);
        } else {
          // everything else including null convert to a string representation
          collectedTags.put(cursor.dotted(prefix), String.valueOf(value));
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
    private final int depthLimit;
    private final RedactionJsonPaths redactionJsonPaths;

    private final Map<String, Object> collectedTags;
    private boolean stopFlag;

    public JsonVisitorTagCollector(
        int depthLimit,
        int maxTags,
        RedactionJsonPaths redactionJsonPaths,
        Map<String, Object> collectedTags) {
      this.depthLimit = depthLimit;
      this.maxTags = maxTags;
      this.redactionJsonPaths = redactionJsonPaths;
      this.collectedTags = collectedTags;
    }

    @Override
    public boolean skipInner(PathCursor pathCursor) {
      return pathCursor.length() > depthLimit;
    }

    @Override
    public boolean visitValue(PathCursor pathCursor) {
      if (redactionJsonPaths.findMatching(pathCursor) != null) {
        collectedTags.put(pathCursor.dotted(redactionJsonPaths.knownPayloadTag), REDACTED);
        return false;
      }
      return true;
    }

    @Override
    public void valueVisited(PathCursor pathCursor, Object value) {
      if (collectedTags.size() < maxTags) {
        collectedTags.put(
            pathCursor.dotted(redactionJsonPaths.knownPayloadTag), String.valueOf(value));
      } else {
        collectedTags.put(DD_PAYLOAD_TAGS_INCOMPLETE, true);
        stopFlag = true;
      }
    }

    @Override
    public boolean keepParsing() {
      return !stopFlag;
    }

    @Override
    public void expandValueFailed(PathCursor pathCursor, Exception exception) {
      log.debug("Failed to expand value at path '{}'", pathCursor.dotted(""), exception);
    }
  }
}
