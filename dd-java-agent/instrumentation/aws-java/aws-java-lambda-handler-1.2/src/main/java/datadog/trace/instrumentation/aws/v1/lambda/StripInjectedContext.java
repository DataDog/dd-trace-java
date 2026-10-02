package datadog.trace.instrumentation.aws.v1.lambda;

import datadog.json.JsonMapper;
import datadog.json.JsonReader;
import datadog.trace.api.Config;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Removes {@code "_datadog":{...}} trace-context carriers injected by Datadog into Lambda events
 * (EventBridge, SNS, SQS, ...) before the customer handler reads them.
 *
 * <p>Carriers are cut out of the JSON text instead of parsing and re-serializing the payload, so
 * numbers, key order and formatting of customer data are kept. Carriers inside string-encoded JSON
 * (an SNS {@code Message}, an SQS {@code body}) are handled by decoding that string, stripping it
 * recursively up to {@link #MAX_DEPTH} levels, and re-encoding it. A {@code _datadog} key whose
 * value is not an object is customer data and is kept. Fail-open: on any error the original payload
 * is used.
 */
public final class StripInjectedContext {

  private static final Logger log = LoggerFactory.getLogger(StripInjectedContext.class);

  /** SNS-to-SQS fan-out needs 2 levels of string-encoded JSON; one more is a safety margin. */
  static final int MAX_DEPTH = 3;

  private static final String CARRIER_KEY = "_datadog";
  private static final String QUOTED_CARRIER_KEY = "\"" + CARRIER_KEY + "\"";
  private static final byte[] CARRIER_KEY_BYTES = CARRIER_KEY.getBytes(StandardCharsets.UTF_8);

  /** Returned by {@link #carrierRange} when the carrier object never closes (malformed JSON). */
  private static final int[] UNCLOSED = new int[0];

  private StripInjectedContext() {}

  /** Reads {@code in} fully, strips "_datadog", and returns a new stream over the result. */
  public static ByteArrayInputStream replaceInputStream(InputStream in) {
    if (in == null) {
      return null;
    }
    byte[] original;
    try {
      original = readAllBytes(in);
    } catch (IOException e) {
      log.debug("Unable to read Lambda payload for _datadog stripping", e);
      return null;
    }
    try {
      return new ByteArrayInputStream(strip(original));
    } catch (RuntimeException e) {
      log.debug("Unable to strip _datadog from Lambda payload", e);
      return new ByteArrayInputStream(original);
    }
  }

  /** Strips "_datadog" from {@code payload} when the config is enabled. */
  public static byte[] strip(byte[] payload) {
    if (!Config.get().isLambdaStripInjectedContextEnabled()) {
      return payload;
    }
    return stripInternal(payload);
  }

  /** Unconditional strip logic, package-private so tests can call it directly. */
  static byte[] stripInternal(byte[] payload) {
    if (payload == null || !containsCarrierKey(payload)) {
      return payload;
    }
    String json = new String(payload, StandardCharsets.UTF_8);
    String stripped = stripJson(json, 0);
    return stripped.equals(json) ? payload : stripped.getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Walks the string tokens of {@code json} once. A {@code "_datadog"} key with an object value is
   * cut out; any other string containing {@code _datadog} is treated as encoded JSON and stripped
   * recursively. Returns {@code json} itself when nothing changed.
   */
  private static String stripJson(String json, int depth) {
    StringBuilder out = null;
    int copyFrom = 0;
    int nextKey = -1;
    int start = json.indexOf('"');
    while (start >= 0) {
      if (nextKey < start) {
        nextKey = json.indexOf(CARRIER_KEY, start);
        if (nextKey < 0) {
          break;
        }
      }
      int end = stringEnd(json, start);
      if (end < 0) {
        break;
      }
      int[] carrier = carrierRange(json, start, end, copyFrom);
      if (carrier == UNCLOSED) {
        break;
      }
      if (carrier != null) {
        if (out == null) {
          out = new StringBuilder(json.length());
        }
        out.append(json, copyFrom, carrier[0]);
        copyFrom = carrier[1];
        end = carrier[1];
      } else if (nextKey < end && depth < MAX_DEPTH) {
        String replacement = stripEncodedJson(json.substring(start, end), depth);
        if (replacement != null) {
          if (out == null) {
            out = new StringBuilder(json.length());
          }
          out.append(json, copyFrom, start).append(replacement);
          copyFrom = end;
        }
      }
      start = json.indexOf('"', end);
    }
    if (out == null) {
      return json;
    }
    return out.append(json, copyFrom, json.length()).toString();
  }

  /**
   * If the string token {@code [keyStart, keyEnd)} is a {@code "_datadog"} key with an object
   * value, returns the {@code [start, end)} range to cut, including one adjacent comma so the JSON
   * stays valid. A preceding comma before {@code copyFrom} was already cut and is not reused.
   * Returns {@link #UNCLOSED} if the value object never closes.
   */
  private static int[] carrierRange(String json, int keyStart, int keyEnd, int copyFrom) {
    if (keyEnd - keyStart != QUOTED_CARRIER_KEY.length()
        || !json.startsWith(QUOTED_CARRIER_KEY, keyStart)) {
      return null;
    }
    int colon = skipWhitespace(json, keyEnd);
    if (colon >= json.length() || json.charAt(colon) != ':') {
      return null;
    }
    int valueStart = skipWhitespace(json, colon + 1);
    if (valueStart >= json.length() || json.charAt(valueStart) != '{') {
      return null;
    }
    int valueEnd = objectEnd(json, valueStart);
    if (valueEnd < 0) {
      return UNCLOSED;
    }
    int before = skipWhitespaceBackward(json, keyStart - 1);
    if (before >= copyFrom && json.charAt(before) == ',') {
      return new int[] {before, valueEnd};
    }
    int after = skipWhitespace(json, valueEnd);
    if (after < json.length() && json.charAt(after) == ',') {
      return new int[] {keyStart, after + 1};
    }
    return new int[] {keyStart, valueEnd};
  }

  /**
   * Decodes the JSON string literal {@code literal}, strips it if it holds a JSON object, and
   * returns the re-encoded literal, or {@code null} if nothing changed.
   */
  private static String stripEncodedJson(String literal, int depth) {
    String decoded;
    try {
      decoded = new JsonReader(literal).nextString();
    } catch (IOException e) {
      return null;
    }
    int first = skipWhitespace(decoded, 0);
    if (first >= decoded.length() || decoded.charAt(first) != '{') {
      return null;
    }
    String stripped = stripJson(decoded, depth + 1);
    return stripped.equals(decoded) ? null : JsonMapper.toJson(stripped);
  }

  /** Returns the index just past the closing quote of the string opening at {@code start}. */
  private static int stringEnd(String json, int start) {
    for (int i = start + 1; i < json.length(); i++) {
      char c = json.charAt(i);
      if (c == '\\') {
        i++;
      } else if (c == '"') {
        return i + 1;
      }
    }
    return -1;
  }

  /** Returns the index just past the closing brace of the object opening at {@code start}. */
  private static int objectEnd(String json, int start) {
    int depth = 0;
    for (int i = start; i < json.length(); i++) {
      char c = json.charAt(i);
      if (c == '"') {
        int end = stringEnd(json, i);
        if (end < 0) {
          return -1;
        }
        i = end - 1;
      } else if (c == '{') {
        depth++;
      } else if (c == '}' && --depth == 0) {
        return i + 1;
      }
    }
    return -1;
  }

  private static int skipWhitespace(String json, int i) {
    while (i < json.length() && isWhitespace(json.charAt(i))) {
      i++;
    }
    return i;
  }

  private static int skipWhitespaceBackward(String json, int i) {
    while (i >= 0 && isWhitespace(json.charAt(i))) {
      i--;
    }
    return i;
  }

  private static boolean isWhitespace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r';
  }

  private static boolean containsCarrierKey(byte[] payload) {
    outer:
    for (int i = 0; i <= payload.length - CARRIER_KEY_BYTES.length; i++) {
      for (int j = 0; j < CARRIER_KEY_BYTES.length; j++) {
        if (payload[i + j] != CARRIER_KEY_BYTES[j]) {
          continue outer;
        }
      }
      return true;
    }
    return false;
  }

  /** Reads all bytes from {@code in}, restoring its position first if it's a byte stream. */
  private static byte[] readAllBytes(InputStream in) throws IOException {
    if (in instanceof ByteArrayInputStream) {
      // mark/reset instead of draining, in case the stream is read again elsewhere.
      ByteArrayInputStream bais = (ByteArrayInputStream) in;
      bais.mark(Integer.MAX_VALUE);
      byte[] bytes = new byte[bais.available()];
      int read = bais.read(bytes);
      bais.reset();
      return (read == bytes.length) ? bytes : Arrays.copyOf(bytes, Math.max(read, 0));
    }
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    int n;
    while ((n = in.read(chunk)) != -1) {
      buffer.write(chunk, 0, n);
    }
    return buffer.toByteArray();
  }
}
