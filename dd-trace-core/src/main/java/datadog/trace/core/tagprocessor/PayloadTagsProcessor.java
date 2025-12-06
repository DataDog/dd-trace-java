package datadog.trace.core.tagprocessor;

import static datadog.trace.util.json.JsonPathParser.parseJsonPaths;

import datadog.trace.api.Config;
import datadog.trace.api.ConfigDefaults;
import datadog.trace.api.telemetry.LogCollector;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.util.JsonStreamParser;
import datadog.trace.payloadtags.PayloadTagsData;
import datadog.trace.util.json.JsonPath;
import datadog.trace.util.json.PathCursor;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Post-processor that extracts tags from payload data injected as tags by instrumentations. */
public final class PayloadTagsProcessor implements TagsPostProcessor {
  private static final Logger log = LoggerFactory.getLogger(PayloadTagsProcessor.class);

  private static final String REDACTED = "redacted";
  private static final String BINARY = "<binary>";
  private static final String DD_PAYLOAD_TAGS_INCOMPLETE = "_dd.payload_tags_incomplete";

  @Nullable
  public static PayloadTagsProcessor create(Config config) {
    Map<String, RedactionRules> redactionRulesByTagPrefix = new HashMap<>();
    if (config.isCloudRequestPayloadTaggingEnabled()) {
      redactionRulesByTagPrefix.put(
          ConfigDefaults.DEFAULT_TRACE_CLOUD_PAYLOAD_REQUEST_TAG,
          new RedactionRules.Builder()
              .addRedactionJsonPaths(ConfigDefaults.DEFAULT_CLOUD_COMMON_PAYLOAD_TAGGING)
              .addRedactionJsonPaths(ConfigDefaults.DEFAULT_CLOUD_REQUEST_PAYLOAD_TAGGING)
              .addParsedRedactionJsonPaths(config.getCloudRequestPayloadTagging())
              .build());
    }
    if (config.isCloudResponsePayloadTaggingEnabled()) {
      redactionRulesByTagPrefix.put(
          ConfigDefaults.DEFAULT_TRACE_CLOUD_PAYLOAD_RESPONSE_TAG,
          new RedactionRules.Builder()
              .addRedactionJsonPaths(ConfigDefaults.DEFAULT_CLOUD_COMMON_PAYLOAD_TAGGING)
              .addRedactionJsonPaths(ConfigDefaults.DEFAULT_CLOUD_RESPONSE_PAYLOAD_TAGGING)
              .addParsedRedactionJsonPaths(config.getCloudResponsePayloadTagging())
              .build());
    }
    if (redactionRulesByTagPrefix.isEmpty()) {
      return null;
    }
    int maxDepth = config.getCloudPayloadTaggingMaxDepth();
    int maxTags = config.getCloudPayloadTaggingMaxTags();
    return new PayloadTagsProcessor(redactionRulesByTagPrefix, maxDepth, maxTags);
  }

  final Map<String, RedactionRules> redactionRulesByTagPrefix;
  final int maxDepth;
  final int maxTags;

  PayloadTagsProcessor(
      Map<String, RedactionRules> redactionRulesByTagPrefix, int maxDepth, int maxTags) {
    this.redactionRulesByTagPrefix = redactionRulesByTagPrefix;
    this.maxDepth = maxDepth;
    this.maxTags = maxTags;
  }

  @Override
  public Map<String, Object> processTags(
      Map<String, Object> unsafeTags, DDSpanContext spanContext, List<AgentSpanLink> spanLinks) {
    int spanMaxTags = maxTags + unsafeTags.size();
    for (Map.Entry<String, RedactionRules> tagPrefixRedactionRules :
        redactionRulesByTagPrefix.entrySet()) {
      String tagPrefix = tagPrefixRedactionRules.getKey();
      RedactionRules redactionRules = tagPrefixRedactionRules.getValue();
      Object tagValue = unsafeTags.get(tagPrefix);
      if (tagValue instanceof PayloadTagsData) {
        unsafeTags.remove(tagPrefix);
        spanMaxTags -= 1;
        PayloadTagsData payloadTagsData = (PayloadTagsData) tagValue;
        PayloadTagsCollector payloadTagsCollector =
            new PayloadTagsCollector(maxDepth, spanMaxTags, redactionRules, tagPrefix, unsafeTags);
        collectPayloadTags(payloadTagsData, payloadTagsCollector);
      } else if (tagValue != null) {
        log.debug(
            LogCollector.SEND_TELEMETRY,
            "Expected PayloadTagsData for known payload tag '{}', but got '{}'",
            tagPrefix,
            tagValue);
      }
    }
    return unsafeTags;
  }

  private void collectPayloadTags(
      PayloadTagsData payloadTagsData, PayloadTagsCollector payloadTagsCollector) {
    for (PayloadTagsData.PathAndValue pathAndValue : payloadTagsData.pathAndValues) {
      if (pathAndValue.path.length > maxDepth) {
        continue;
      }
      if (!payloadTagsCollector.keepCollectingTags()) {
        break;
      }
      PathCursor cursor = new PathCursor(pathAndValue.path, maxDepth);
      if (payloadTagsCollector.notRedacted(cursor)) {
        Object value = pathAndValue.value;
        if (value instanceof InputStream) {
          if (!JsonStreamParser.tryToParse((InputStream) value, payloadTagsCollector, cursor)) {
            payloadTagsCollector.stringValue(cursor, BINARY);
          }
        } else if (value instanceof String) {
          String str = (String) value;
          if (!JsonStreamParser.tryToParse(str, payloadTagsCollector, cursor)) {
            payloadTagsCollector.stringValue(cursor, str);
          }
        } else if (value instanceof Boolean) {
          payloadTagsCollector.booleanValue(cursor, (Boolean) value);
        } else if (value instanceof Integer) {
          payloadTagsCollector.intValue(cursor, (Integer) value);
        } else if (value instanceof Long) {
          payloadTagsCollector.longValue(cursor, (Long) value);
        } else if (value instanceof Double) {
          payloadTagsCollector.doubleValue(cursor, (Double) value);
        } else if (value == null) {
          payloadTagsCollector.nullValue(cursor);
        } else {
          payloadTagsCollector.stringValue(cursor, String.valueOf(value));
        }
      }
    }
  }

  static final class RedactionRules {

    public static final class Builder {
      private final List<JsonPath> redactionRules = new ArrayList<>();

      public RedactionRules.Builder addRedactionJsonPaths(List<String> jsonPaths) {
        this.redactionRules.addAll(parseJsonPaths(jsonPaths));
        return this;
      }

      public RedactionRules.Builder addParsedRedactionJsonPaths(List<JsonPath> jsonPaths) {
        if (null == jsonPaths) {
          log.warn("Provided JsonPaths list is null, skipping.");
          return this;
        }
        this.redactionRules.addAll(jsonPaths);
        return this;
      }

      RedactionRules build() {
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

  private static final class PayloadTagsCollector implements JsonStreamParser.Visitor {
    private final int maxTags;
    private final int maxDepth;
    private final RedactionRules redactionRules;
    private final String tagPrefix;

    private final Map<String, Object> collectedTags;

    public PayloadTagsCollector(
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
      if (path.length() < maxDepth) {
        return notRedacted(path);
      }
      return false;
    }

    @Override
    public boolean visitPrimitive(PathCursor path) {
      return notRedacted(path);
    }

    private boolean notRedacted(PathCursor path) {
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
      return keepCollectingTags();
    }

    public boolean keepCollectingTags() {
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
