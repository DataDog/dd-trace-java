package datadog.trace.lambda;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Defensive accessors for the JSON event formats shared with Bottlecap. */
final class LambdaEventData {
  private static final JsonAdapter<Object> JSON =
      new Moshi.Builder()
          .add(ByteArrayInputStream.class, new ReadFromInputStreamJsonAdapter())
          .add(ByteArrayOutputStream.class, new ReadFromOutputStreamJsonAdapter())
          .add(SkipUnsupportedTypeJsonAdapter.newFactory())
          .build()
          .adapter(Object.class);

  private LambdaEventData() {}

  @SuppressWarnings("unchecked")
  static Map<String, Object> object(Object value) {
    return value instanceof Map ? (Map<String, Object>) value : emptyMap();
  }

  static Object get(Object value, String... path) {
    for (String key : path) {
      value = object(value).get(key);
    }
    return value;
  }

  static String string(Object value, String... path) {
    Object field = get(value, path);
    return field instanceof String ? (String) field : "";
  }

  static String scalar(Object value) {
    if (value instanceof Number) {
      return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
    }
    return value instanceof String ? (String) value : "";
  }

  static Object parse(String value) throws java.io.IOException {
    return JSON.fromJson(value);
  }

  static Map<String, Object> json(String value) {
    try {
      return object(JSON.fromJson(value));
    } catch (Exception ignored) {
      return emptyMap();
    }
  }

  static String serializeValue(Object value) {
    return JSON.serializeNulls().toJson(value);
  }

  static String serialize(Object value) {
    try {
      return value == null ? "{}" : JSON.toJson(value);
    } catch (Exception ignored) {
      return "{}";
    }
  }

  static Map<String, Object> payload(Object event) {
    // The serializer resets ByteArrayInputStream payloads, leaving the handler's bytes intact.
    return event instanceof Map
        ? object(event)
        : json(event instanceof String ? (String) event : serialize(event));
  }

  static String decode(String encoded) {
    try {
      return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException ignored) {
      return "";
    }
  }

  static Collection<?> values(Object value) {
    if (value instanceof List) {
      return (List<?>) value;
    }
    return value instanceof Map ? ((Map<?, ?>) value).values() : emptyList();
  }

  static Map<String, Object> first(Object value) {
    for (Object entry : values(value)) {
      return object(entry);
    }
    return emptyMap();
  }

  static Map<String, String> headers(Object value) {
    Map<String, String> result = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : object(value).entrySet()) {
      if (entry.getValue() instanceof String) {
        result.put(entry.getKey().toLowerCase(Locale.ROOT), (String) entry.getValue());
      }
    }
    return result;
  }

  static Map<String, String> multiHeaders(Object value) {
    Map<String, String> result = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : object(value).entrySet()) {
      for (Object item : values(entry.getValue())) {
        if (item instanceof String) {
          result.put(entry.getKey().toLowerCase(Locale.ROOT), (String) item);
        }
        break;
      }
    }
    return result;
  }

  static long micros(Object value, long factor) {
    try {
      long result =
          new BigDecimal(scalar(value))
              .multiply(BigDecimal.valueOf(factor))
              .setScale(0, RoundingMode.DOWN)
              .longValueExact();
      return result > 0 ? result : 0;
    } catch (NumberFormatException | ArithmeticException ignored) {
      return 0;
    }
  }

  static long timestamp(String value) {
    try {
      Instant time = Instant.parse(value);
      long result =
          Math.addExact(
              Math.multiplyExact(time.getEpochSecond(), 1_000_000L), time.getNano() / 1_000);
      return result > 0 ? result : 0;
    } catch (DateTimeParseException | ArithmeticException ignored) {
      return 0;
    }
  }

  static void tag(Map<String, String> tags, String key, Object value) {
    if (value instanceof String || value instanceof Number) {
      tags.put(key, scalar(value));
    }
  }

  static String last(String value, String separator) {
    int index = value.lastIndexOf(separator);
    return value.substring(index + separator.length());
  }

  static String part(String value, String separator, int index) {
    String[] parts = value.split(separator);
    return index < parts.length ? parts[index] : "";
  }

  static String service(
      Map<String, String> mapping, String specific, String generic, String fallback) {
    return mapping.getOrDefault(specific, mapping.getOrDefault(generic, fallback));
  }

  static String partition(String region) {
    String[][] partitions = {
      {"cn-", "aws-cn"},
      {"us-gov-", "aws-us-gov"},
      {"us-isof-", "aws-iso-f"},
      {"us-isob-", "aws-iso-b"},
      {"us-iso-", "aws-iso"},
      {"eu-isoe-", "aws-iso-e"},
      {"eusc-", "aws-eusc"}
    };
    for (String[] candidate : partitions) {
      if (region.startsWith(candidate[0])) {
        return candidate[1];
      }
    }
    return "aws";
  }
}
