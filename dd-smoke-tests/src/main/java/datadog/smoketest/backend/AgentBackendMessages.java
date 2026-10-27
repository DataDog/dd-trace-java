package datadog.smoketest.backend;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonDataException;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.util.Strings;
import java.io.IOException;
import java.lang.reflect.Type;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * The agent-protocol JSON the backends exchange with a launched app's tracer: parses what they
 * capture (telemetry intake bodies, remote-config polls, the test agent's session responses) into
 * maps, and builds the responses they serve. Moshi decodes JSON numbers as {@code Double}, which is
 * fine for the presence/string assertions these tests do.
 */
final class AgentBackendMessages {
  private static final Type MESSAGE =
      Types.newParameterizedType(Map.class, String.class, Object.class);
  private static final Moshi MOSHI = new Moshi.Builder().build();
  private static final JsonAdapter<Map<String, Object>> MESSAGE_ADAPTER = MOSHI.adapter(MESSAGE);
  private static final JsonAdapter<List<Map<String, Object>>> MESSAGE_LIST_ADAPTER =
      MOSHI.adapter(Types.newParameterizedType(List.class, MESSAGE));

  private AgentBackendMessages() {}

  /**
   * Decodes one captured JSON object (a telemetry intake body, a remote-config poll, ...).
   *
   * @param json The captured request body.
   * @return The decoded map, or an empty map if the body is a JSON {@code null}.
   */
  static Map<String, Object> decodeMessage(byte[] json) {
    try {
      Map<String, Object> message = MESSAGE_ADAPTER.fromJson(new String(json, UTF_8));
      return message == null ? emptyMap() : message;
    } catch (IOException | JsonDataException e) {
      throw new IllegalStateException("Failed to parse JSON object", e);
    }
  }

  /**
   * Decodes the test agent's {@code /test/session/apmtelemetry} response (a JSON array of
   * messages).
   *
   * @param json The response body.
   * @return The decoded message maps, or an empty list if the body is a JSON {@code null}.
   */
  static List<Map<String, Object>> decodeMessages(String json) {
    try {
      List<Map<String, Object>> messages = MESSAGE_LIST_ADAPTER.fromJson(json);
      return messages == null ? emptyList() : messages;
    } catch (IOException | JsonDataException e) {
      throw new IllegalStateException(
          "Failed to parse /test/session/apmtelemetry response: " + json, e);
    }
  }

  /**
   * Builds a {@code /v0.7/config} response serving a single config, as the Groovy smoke base did.
   * The TUF envelope is unsigned — the tracer only verifies signatures when {@code
   * remote_config.integrity_check.enabled} is on — but it always checks each target file against
   * the sha256 and byte length declared in {@code targets}.
   *
   * @param path The RC target path (e.g. {@code datadog/2/APM_TRACING/config_overrides/config}).
   * @param config The config content as a JSON object literal.
   * @return The remote-config response body.
   */
  static String remoteConfigResponse(String path, String config) {
    byte[] raw = config.getBytes(UTF_8);
    String quotedPath = '"' + path.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    String targets =
        "{\"signed\":{\"expires\":\"9999-12-31T23:59:59Z\",\"spec_version\":\"1.0.0\",\"targets\":{"
            + quotedPath
            + ":{\"custom\":{\"v\":1},\"hashes\":{\"sha256\":\""
            + sha256(config)
            + "\"},\"length\":"
            + raw.length
            + "}}}}";
    Base64.Encoder base64 = Base64.getEncoder();
    return "{\"client_configs\":["
        + quotedPath
        + "],\"roots\":[],\"target_files\":[{\"path\":"
        + quotedPath
        + ",\"raw\":\""
        + base64.encodeToString(raw)
        + "\"}],\"targets\":\""
        + base64.encodeToString(targets.getBytes(UTF_8))
        + "\"}";
  }

  private static String sha256(String input) {
    try {
      return Strings.sha256(input);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
