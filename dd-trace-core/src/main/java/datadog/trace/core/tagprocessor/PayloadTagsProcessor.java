package datadog.trace.core.tagprocessor;

import datadog.trace.api.Config;
import datadog.trace.api.ConfigDefaults;
import datadog.trace.core.DDSpanContext;
import datadog.trace.payloadtags.PathCursor;
import datadog.trace.payloadtags.PayloadTagsData;
import datadog.trace.payloadtags.json.JsonPath;
import datadog.trace.payloadtags.json.JsonPathParser;
import datadog.trace.payloadtags.json.JsonStreamTraversal;
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
  public static PayloadTagsProcessor create(Config config) {
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
  private final int tagsLimit;

  private PayloadTagsProcessor(
      List<RedactionJsonPaths> redactionJsonPaths, int depthLimit, int tagsLimit) {
    this.redactionJsonPaths = redactionJsonPaths;
    this.depthLimit = depthLimit;
    this.tagsLimit = tagsLimit;
  }

  @Override
  public Map<String, Object> processTags(Map<String, Object> tags, DDSpanContext spanContext) {
    for (RedactionJsonPaths redactionJsonPaths : redactionJsonPaths) {
      String knownPayloadTag = redactionJsonPaths.knownPayloadTag;
      Object tagValue = tags.get(knownPayloadTag);
      if (tagValue instanceof PayloadTagsData) {
        // remove payload path data tag to be replaced by post-processed extracted tags
        tags.remove(knownPayloadTag);
        PayloadTagsData payloadTagsData = (PayloadTagsData) tagValue;
        // TODO calc local limit taking into account already collected tags
        JsonStreamTraversal.Visitor visitor =
            new JsonVisitorTagCollector(depthLimit, tagsLimit, redactionJsonPaths, tags);
        processPayloadTags(payloadTagsData, redactionJsonPaths, visitor, tags);
      } else {
        // TODO expected to be PayloadTagsData but it's not
      }
    }
    return tags;
  }

  private static void processPayloadTags(
      PayloadTagsData payloadTagsData,
      RedactionJsonPaths redactionJsonPaths,
      JsonStreamTraversal.Visitor visitor,
      Map<String, Object> collectedTags) {
    for (PathCursor cursor : payloadTagsData.all()) {
      JsonPath redactionJsonPath = redactionJsonPaths.findMatching(cursor);
      String prefix = redactionJsonPaths.knownPayloadTag;
      if (redactionJsonPath != null) {
        collectedTags.put(cursor.dotted(prefix), REDACTED);
      } else {
        Object value = cursor.attachedValue();
        if (value instanceof InputStream) {
          if (!JsonStreamTraversal.traverse((InputStream) value, visitor, cursor)) {
            collectedTags.put(cursor.dotted(prefix), BINARY);
          }
        } else if (value instanceof String) {
          String raw = (String) value;
          if (!JsonStreamTraversal.traverse(raw, visitor, cursor)) {
            // keep string value as-is if it's not a valid JSON object or array
            collectedTags.put(cursor.dotted(prefix), tagValue(value));
          }
        } else {
          collectedTags.put(cursor.dotted(prefix), tagValue(value));
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

  private static final class JsonVisitorTagCollector implements JsonStreamTraversal.Visitor {
    private final int tagsLimit;
    private final int depthLimit;
    private final RedactionJsonPaths redactionJsonPaths;

    private final Map<String, Object> collectedTags;
    private boolean stopFlag;

    public JsonVisitorTagCollector(
        int depthLimit,
        int tagsLimit,
        RedactionJsonPaths redactionJsonPaths,
        Map<String, Object> collectedTags) {
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
        collectedTags.put(pathCursor.dotted(redactionJsonPaths.knownPayloadTag), REDACTED);
        return false;
      }
      return true;
    }

    @Override
    public void valueVisited(PathCursor pathCursor, Object value) {
      if (collectedTags.size() < tagsLimit) {
        collectedTags.put(pathCursor.dotted(redactionJsonPaths.knownPayloadTag), tagValue(value));
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
