package datadog.trace.lambda;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Opt-in Lambda payload tags with redaction before depth-limit serialization. */
final class LambdaPayloadCapture {
  private static final int MAX_CHARS = 4096;
  private static final Set<String> REDACTED =
      new HashSet<>(
          Arrays.asList(
              "password",
              "passwd",
              "pwd",
              "secret",
              "token",
              "authorization",
              "x-authorization",
              "api_key"));

  private LambdaPayloadCapture() {}

  static void capture(Config config, AgentSpan span, String prefix, Object payload) {
    if (!config.isCaptureLambdaPayload()) {
      return;
    }
    try {
      Object value =
          payload instanceof Map || payload instanceof List
              ? payload
              : LambdaEventData.parse(
                  payload instanceof String
                      ? (String) payload
                      : LambdaEventData.serialize(payload));
      flatten(span, prefix, value, 0, config.getCaptureLambdaPayloadMaxDepth());
    } catch (IOException | RuntimeException ignored) {
      // Payload capture must not affect the handler or invocation completion.
    }
  }

  private static boolean sensitive(String key) {
    return REDACTED.contains(key.substring(key.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT));
  }

  private static String truncate(String value) {
    int count = value.codePointCount(0, value.length());
    return count <= MAX_CHARS ? value : value.substring(0, value.offsetByCodePoints(0, MAX_CHARS));
  }

  private static void flatten(AgentSpan span, String key, Object value, int depth, int maxDepth) {
    if (sensitive(key)) {
      span.setTag(key, "redacted");
      return;
    }
    if (value == null) {
      span.setTag(key, "null");
      return;
    }
    if (depth >= maxDepth || depth >= 128) {
      span.setTag(key, truncate(LambdaEventData.serializeValue(redact(value, 0))));
    } else if (value instanceof Map) {
      for (Map.Entry<String, Object> entry : LambdaEventData.object(value).entrySet()) {
        flatten(span, key + "." + entry.getKey(), entry.getValue(), depth + 1, maxDepth);
      }
    } else if (value instanceof List) {
      List<?> values = (List<?>) value;
      if (values.isEmpty()) {
        span.setTag(key, "[]");
      }
      for (int i = 0; i < values.size(); i++) {
        flatten(span, key + "." + i, values.get(i), depth + 1, maxDepth);
      }
    } else if (value instanceof String) {
      try {
        flatten(span, key, LambdaEventData.parse((String) value), depth + 1, maxDepth);
      } catch (IOException | RuntimeException ignored) {
        span.setTag(key, truncate((String) value));
      }
    } else {
      span.setTag(key, value instanceof Number ? LambdaEventData.scalar(value) : value.toString());
    }
  }

  private static Object redact(Object value, int depth) {
    if (depth >= 128) {
      return "redacted";
    }
    if (value instanceof Map) {
      Map<String, Object> result = new LinkedHashMap<>();
      for (Map.Entry<String, Object> entry : LambdaEventData.object(value).entrySet()) {
        result.put(
            entry.getKey(),
            sensitive(entry.getKey()) ? "redacted" : redact(entry.getValue(), depth + 1));
      }
      return result;
    }
    if (value instanceof List) {
      List<Object> result = new ArrayList<>();
      for (Object item : (List<?>) value) {
        result.add(redact(item, depth + 1));
      }
      return result;
    }
    if (value instanceof String) {
      try {
        Object parsed = LambdaEventData.parse((String) value);
        if (parsed instanceof Map || parsed instanceof List) {
          return LambdaEventData.serializeValue(redact(parsed, depth + 1));
        }
      } catch (IOException | RuntimeException ignored) {
      }
    }
    return value;
  }
}
