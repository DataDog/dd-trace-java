package datadog.trace.core.tagprocessor;

import datadog.trace.api.Config;
import datadog.trace.api.ConfigDefaults;
import datadog.trace.core.DDSpanContext;
import datadog.trace.payloadtags.PathCursor;
import datadog.trace.payloadtags.PayloadPathData;
import datadog.trace.payloadtags.json.JsonPath;
import datadog.trace.payloadtags.json.JsonPathParser;
import datadog.trace.payloadtags.json.JsonStreamTraversal;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Post-processor that extracts tags from payload paths injected as tags by instrumentations. */
public final class PayloadTagsProcessor implements TagsPostProcessor {
  private static final Logger log = LoggerFactory.getLogger(PayloadTagsProcessor.class);

  private static final String REDACTED = "redacted";
  private static final String BINARY = "<binary>";
  private static final String DD_PAYLOAD_TAGS_INCOMPLETE = "_dd.payload_tags_incomplete";

  private final int depthLimit;
  private final int tagsLimit;

  private final Map<String, RedactionJsonPaths> knownPayloadTagsRedactionPaths;

  public PayloadTagsProcessor(Config config) {
    knownPayloadTagsRedactionPaths = new HashMap<>(2);
    if (config.isCloudRequestPayloadTaggingEnabled()) {
      knownPayloadTagsRedactionPaths.put(
          PayloadPathData.AWS_REQUEST_BODY,
          new RedactionJsonPaths.Builder()
              .addRedactionJsonPaths(ConfigDefaults.DEFAULT_CLOUD_PAYLOAD_TAGGING)
              .addRedactionJsonPaths(ConfigDefaults.DEFAULT_CLOUD_REQUEST_PAYLOAD_TAGGING)
              .addRedactionJsonPaths(config.getCloudRequestPayloadTagging())
              .build());
    }
    if (config.isCloudResponsePayloadTaggingEnabled()) {
      knownPayloadTagsRedactionPaths.put(
          PayloadPathData.AWS_RESPONSE_BODY,
          new RedactionJsonPaths.Builder()
              .addRedactionJsonPaths(ConfigDefaults.DEFAULT_CLOUD_PAYLOAD_TAGGING)
              .addRedactionJsonPaths(ConfigDefaults.DEFAULT_CLOUD_RESPONSE_PAYLOAD_TAGGING)
              .addRedactionJsonPaths(config.getCloudResponsePayloadTagging())
              .build());
    }
    depthLimit = config.getCloudPayloadTaggingMaxDepth();
    tagsLimit = config.getCloudPayloadTaggingMaxTags();
  }

  @Override
  public Map<String, Object> processTags(
      Map<String, Object> unsafeTags, DDSpanContext spanContext) {
    for (Map.Entry<String, RedactionJsonPaths> entry : knownPayloadTagsRedactionPaths.entrySet()) {
      String knownPayloadTag = entry.getKey();
      Object tag = unsafeTags.get(knownPayloadTag);
      if (tag instanceof PayloadPathData) {
        // remove payload path data tag to be replaced by post-processed extracted tags
        unsafeTags.remove(knownPayloadTag);
        PayloadPathData payloadPathData = (PayloadPathData) tag;
        // TODO calc local limit taking into account already collected tags
        JsonStreamTraversal.Visitor visitor =
            new JsonVisitorTagCollector(
                knownPayloadTag, depthLimit, tagsLimit, entry.getValue(), unsafeTags);
        processPayloadTags(payloadPathData, knownPayloadTag, entry.getValue(), visitor, unsafeTags);
      }
    }
    return unsafeTags;
  }

  private static void processPayloadTags(
      PayloadPathData payloadPathData,
      String tagPrefix,
      RedactionJsonPaths redactionJsonPaths,
      JsonStreamTraversal.Visitor visitor,
      Map<String, Object> collectedTags) {
    for (PathCursor cursor : payloadPathData.all()) {
      JsonPath redactionJsonPath = redactionJsonPaths.findMatching(cursor);
      if (redactionJsonPath != null) {
        collectedTags.put(cursor.dotted(tagPrefix), REDACTED);
      } else {
        Object value = cursor.attachedValue();
        if (value instanceof InputStream) {
          if (!JsonStreamTraversal.traverse((InputStream) value, visitor, cursor)) {
            collectedTags.put(cursor.dotted(tagPrefix), BINARY);
          }
        } else if (value instanceof String) {
          String raw = (String) value;
          if (!JsonStreamTraversal.traverse(raw, visitor, cursor)) {
            // keep string value as is if it's not a valid JSON object or array
            collectedTags.put(cursor.dotted(tagPrefix), tagValue(value));
          }
        } else {
          collectedTags.put(cursor.dotted(tagPrefix), tagValue(value));
        }
      }
    }
  }

  private static Object tagValue(Object value) {
    if (value == null) {
      // convert `null` to a string, otherwise it won't be set as a tag value
      return "null";
    }
    // TODO maybe check value type???
    // TODO maybe cut if too long???
    return value;
  }

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

      public RedactionJsonPaths build() {
        return new RedactionJsonPaths(redactionRules);
      }
    }

    private final List<JsonPath> paths;

    private RedactionJsonPaths(List<JsonPath> paths) {
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

  private static final class JsonVisitorTagCollector implements JsonStreamTraversal.Visitor {
    private final String tagPrefix;
    private final int tagsLimit;
    private final int depthLimit;
    private final RedactionJsonPaths redactionJsonPaths;

    private final Map<String, Object> collectedTags;
    private boolean stopFlag;

    public JsonVisitorTagCollector(
        String tagPrefix,
        int depthLimit,
        int tagsLimit,
        RedactionJsonPaths redactionJsonPaths,
        Map<String, Object> collectedTags) {
      this.tagPrefix = tagPrefix;
      this.depthLimit = depthLimit;
      this.tagsLimit = tagsLimit;
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
        collectedTags.put(pathCursor.dotted(tagPrefix), REDACTED);
        return false;
      }
      return true;
    }

    @Override
    public void valueVisited(PathCursor pathCursor, Object value) {
      if (collectedTags.size() < tagsLimit) {
        collectedTags.put(pathCursor.dotted(tagPrefix), tagValue(value));
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
    public void expandValueFailed(PathCursor pathCursor, Exception exception) {
      // TODO debug log???
    }
  }
}
