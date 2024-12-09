package datadog.trace.bootstrap.instrumentation.messaging;

import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.nio.ByteBuffer;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Parses trace context from an embedded '_datadog' message attribute. */
public final class DatadogAttributeParser {
  private static final Logger log = LoggerFactory.getLogger(DatadogAttributeParser.class);

  private static final Base64.Decoder BASE_64 = Base64.getDecoder();

  /** Parses trace context properties from the given JSON and passes them to the classifier. */
  public static void forEachProperty(AgentPropagation.KeyClassifier classifier, String json) {
    if (null == json) {
      return;
    }
    try {
      if (acceptJsonProperty(classifier, json, "x-datadog-trace-id")) {
        acceptJsonProperty(classifier, json, "x-datadog-parent-id");
        acceptJsonProperty(classifier, json, "x-datadog-sampling-priority");
      }
      if (Config.get().isDataStreamsEnabled()) {
        acceptJsonProperty(classifier, json, "dd-pathway-ctx-base64");
      }
    } catch (Exception e) {
      log.debug("Problem extracting _datadog context", e);
    }
  }

  /** Parses trace context properties from the given JSON and passes them to the classifier. */
  public static void forEachProperty(AgentPropagation.KeyClassifier classifier, ByteBuffer json) {
    if (null == json) {
      return;
    }
    try {
      String jsonStr;
      // peak at the first character to know if we're dealing with json or base64 (since '{' is not
      // a valid b64 char).
      if (json.get(0) == '{') {
        jsonStr = UTF_8.decode(json).toString();
      } else {
        // TODO remove this branch once we are sure that this is never happening.
        // passing a base64 string in a byte buffer is a nonsense, since the base64 is a less
        // efficient representation.
        jsonStr = UTF_8.decode(BASE_64.decode(json)).toString();
      }
      forEachProperty(classifier, jsonStr);
    } catch (Exception e) {
      log.debug("Problem decoding _datadog context", e);
    }
  }

  // Simple parser that assumes values are JSON strings that don't contain escaped quotes
  private static boolean acceptJsonProperty(
      AgentPropagation.KeyClassifier classifier, String json, String key) {
    int keyStart = json.indexOf(key);
    if (keyStart > 0) {
      int separator = json.indexOf(':', keyStart + key.length());
      if (separator > 0) {
        int valueStart = json.indexOf('"', separator + 1);
        if (valueStart > 0) {
          int valueEnd = json.indexOf('"', valueStart + 1);
          if (valueEnd > 0) {
            return classifier.accept(key, json.substring(valueStart + 1, valueEnd));
          }
        }
      }
    }
    return false;
  }
}
