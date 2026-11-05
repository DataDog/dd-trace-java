package datadog.trace.lambda;

import static datadog.trace.core.tagprocessor.SpanPointersProcessor.DYNAMODB_PTR_KIND;
import static datadog.trace.core.tagprocessor.SpanPointersProcessor.S3_PTR_KIND;
import static datadog.trace.core.tagprocessor.SpanPointersProcessor.UP_DIRECTION;
import static datadog.trace.core.tagprocessor.SpanPointersProcessor.createPointer;
import static datadog.trace.lambda.LambdaEventData.first;
import static datadog.trace.lambda.LambdaEventData.get;
import static datadog.trace.lambda.LambdaEventData.object;
import static datadog.trace.lambda.LambdaEventData.part;
import static datadog.trace.lambda.LambdaEventData.string;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Bottlecap-compatible upstream pointers for the first S3 or DynamoDB event record. */
final class LambdaSpanPointers {
  private LambdaSpanPointers() {}

  static void attach(Config config, AgentSpan root, Map<String, Object> event) {
    if (!config.isAddSpanPointers("aws") || root == null) {
      return;
    }
    Map<String, Object> record = first(event.get("Records"));
    AgentSpanLink pointer = null;
    if (record.containsKey("s3")) {
      String bucket = string(record, "s3", "bucket", "name");
      String key = string(record, "s3", "object", "key");
      String etag = string(record, "s3", "object", "eTag");
      int start = 0;
      int end = etag.length();
      while (start < end && etag.charAt(start) == '"') {
        start++;
      }
      while (end > start && etag.charAt(end - 1) == '"') {
        end--;
      }
      etag = etag.substring(start, end);
      if (!bucket.isEmpty() && !key.isEmpty() && !etag.isEmpty()) {
        pointer = createPointer(S3_PTR_KIND, UP_DIRECTION, bucket, key, etag);
      }
    } else if (record.containsKey("dynamodb")) {
      pointer = dynamodb(record);
    }
    if (pointer != null) {
      root.addLink(pointer);
    }
  }

  private static AgentSpanLink dynamodb(Map<String, Object> record) {
    String table = part(string(record, "eventSourceARN"), "/", 1);
    Map<String, Object> keys = object(get(record, "dynamodb", "Keys"));
    if (table.isEmpty() || keys.isEmpty() || keys.size() > 2) {
      return null;
    }
    List<String> names = new ArrayList<>(keys.keySet());
    // Rust orders UTF-8 strings by bytes, including names outside the BMP.
    names.sort(LambdaSpanPointers::compareUtf8);
    String name1 = names.get(0);
    String value1 = value(keys.get(name1));
    String name2 = names.size() == 2 ? names.get(1) : "";
    String value2 = names.size() == 2 ? value(keys.get(name2)) : "";
    if (value1 == null || value2 == null) {
      return null;
    }
    return createPointer(DYNAMODB_PTR_KIND, UP_DIRECTION, table, name1, value1, name2, value2);
  }

  private static String value(Object attribute) {
    Map<String, Object> fields = object(attribute);
    if (fields.size() != 1) {
      return null;
    }
    Object text = fields.containsKey("S") ? fields.get("S") : fields.get("N");
    if (text instanceof String) {
      return (String) text;
    }
    Object binary = fields.get("B");
    if (binary instanceof String) {
      try {
        return StandardCharsets.UTF_8
            .newDecoder()
            .decode(ByteBuffer.wrap(Base64.getDecoder().decode((String) binary)))
            .toString();
      } catch (IllegalArgumentException | CharacterCodingException invalid) {
        return null;
      }
    }
    return null;
  }

  private static int compareUtf8(String left, String right) {
    byte[] a = left.getBytes(StandardCharsets.UTF_8);
    byte[] b = right.getBytes(StandardCharsets.UTF_8);
    for (int i = 0; i < Math.min(a.length, b.length); i++) {
      int difference = (a[i] & 255) - (b[i] & 255);
      if (difference != 0) {
        return difference;
      }
    }
    return Integer.compare(a.length, b.length);
  }
}
