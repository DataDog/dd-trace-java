package datadog.trace.instrumentation.weblogic.t3;

import static java.util.Arrays.copyOf;
import static java.util.Collections.emptyMap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

final class T3PropagationFields {
  private static final int MAX_ENCODED_LENGTH = 8192;
  private static final int MAX_BINARY_LENGTH = MAX_ENCODED_LENGTH / 4 * 3;
  private static final String BAGGAGE = "baggage";

  private T3PropagationFields() {}

  static String encode(Map<String, String> fields) throws IOException {
    if (fields.isEmpty()) {
      return null;
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream output = new DataOutputStream(bytes);
    for (Map.Entry<String, String> field : fields.entrySet()) {
      if (BAGGAGE.equals(field.getKey())) {
        continue;
      }
      if (field.getKey().length() + field.getValue().length() > MAX_BINARY_LENGTH - bytes.size()) {
        return null;
      }
      output.writeUTF(field.getKey());
      output.writeUTF(field.getValue());
      if (bytes.size() > MAX_BINARY_LENGTH) {
        return null;
      }
    }
    int traceFieldsSize = bytes.size();
    String baggage = fields.get(BAGGAGE);
    if (baggage != null
        && BAGGAGE.length() + baggage.length() <= MAX_BINARY_LENGTH - traceFieldsSize) {
      output.writeUTF(BAGGAGE);
      output.writeUTF(baggage);
    }
    byte[] payload = bytes.toByteArray();
    if (payload.length > MAX_BINARY_LENGTH) {
      payload = copyOf(payload, traceFieldsSize);
    }
    return payload.length == 0 ? null : Base64.getEncoder().encodeToString(payload);
  }

  static Map<String, String> decode(String encoded) {
    if (encoded == null || encoded.length() > MAX_ENCODED_LENGTH) {
      return emptyMap();
    }
    try {
      DataInputStream input =
          new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
      Map<String, String> fields = new LinkedHashMap<>();
      while (input.available() > 0) {
        String key = input.readUTF();
        String value = input.readUTF();
        if (key.isEmpty() || fields.put(key, value) != null) {
          return emptyMap();
        }
      }
      return fields;
    } catch (IllegalArgumentException | IOException e) {
      return emptyMap();
    }
  }
}
