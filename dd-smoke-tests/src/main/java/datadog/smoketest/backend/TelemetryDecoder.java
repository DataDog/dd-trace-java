package datadog.smoketest.backend;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonDataException;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Parses app-telemetry JSON into maps: a single intake body (what the mock backend collects
 * per-request) or the test agent's {@code /test/session/apmtelemetry} array. Moshi decodes JSON
 * numbers as {@code Double}, which is fine for the presence/string assertions telemetry tests do.
 */
final class TelemetryDecoder {
  private static final Type MESSAGE =
      Types.newParameterizedType(Map.class, String.class, Object.class);
  private static final Moshi MOSHI = new Moshi.Builder().build();
  private static final JsonAdapter<Map<String, Object>> MESSAGE_ADAPTER = MOSHI.adapter(MESSAGE);
  private static final JsonAdapter<List<Map<String, Object>>> MESSAGE_LIST_ADAPTER =
      MOSHI.adapter(Types.newParameterizedType(List.class, MESSAGE));

  private TelemetryDecoder() {}

  /** Decodes one telemetry intake body (a JSON object) into a message map. */
  static Map<String, Object> decodeMessage(byte[] json) {
    try {
      Map<String, Object> message =
          MESSAGE_ADAPTER.fromJson(new String(json, StandardCharsets.UTF_8));
      return message == null ? Collections.emptyMap() : message;
    } catch (IOException | JsonDataException e) {
      throw new IllegalStateException("Failed to parse telemetry message", e);
    }
  }

  /** Decodes the test agent's {@code /test/apmtelemetry} response (a JSON array of messages). */
  static List<Map<String, Object>> decodeMessages(String json) {
    try {
      List<Map<String, Object>> messages = MESSAGE_LIST_ADAPTER.fromJson(json);
      return messages == null ? Collections.emptyList() : messages;
    } catch (IOException | JsonDataException e) {
      throw new IllegalStateException(
          "Failed to parse /test/session/apmtelemetry response: " + json, e);
    }
  }
}
