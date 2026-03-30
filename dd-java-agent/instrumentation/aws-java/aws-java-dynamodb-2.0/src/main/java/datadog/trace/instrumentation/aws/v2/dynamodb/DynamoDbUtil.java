package datadog.trace.instrumentation.aws.v2.dynamodb;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.SpanPointerUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class DynamoDbUtil {
  /**
   * Extracts a string value from a DynamoDB AttributeValue.
   *
   * @param value The AttributeValue to extract from
   * @return The extracted string value
   */
  private static String extractValueAsString(AttributeValue value) {
    if (value == null) {
      return "";
    }

    if (value.s() != null) {
      return value.s();
    } else if (value.n() != null) {
      return value.n();
    } else if (value.b() != null) {
      // For binary values, convert the bytes back to string
      return new String(value.b().asByteArray(), java.nio.charset.Charset.defaultCharset());
    }

    return "";
  }

  /**
   * Creates a DynamoDB span pointer link from the given primary keys and adds it to the span.
   *
   * @param span The span to add the pointer link to
   * @param tableName The DynamoDB table name
   * @param keys The primary key/values to extract from
   */
  static void addSpanPointer(AgentSpan span, String tableName, Map<String, AttributeValue> keys) {
    if (keys == null || keys.isEmpty() || tableName == null) {
      return;
    }

    String primaryKey1Name;
    String primaryKey1Value;
    String primaryKey2Name = null;
    String primaryKey2Value = null;

    if (keys.size() == 1) {
      // Single primary key case
      Map.Entry<String, AttributeValue> entry = keys.entrySet().iterator().next();
      primaryKey1Name = entry.getKey();
      primaryKey1Value = extractValueAsString(entry.getValue());
    } else {
      // Sort keys alphabetically
      List<String> keyNames = new ArrayList<>(keys.keySet());
      Collections.sort(keyNames);

      // First key (alphabetically)
      primaryKey1Name = keyNames.get(0);
      primaryKey1Value = extractValueAsString(keys.get(primaryKey1Name));

      // Second key
      primaryKey2Name = keyNames.get(1);
      primaryKey2Value = extractValueAsString(keys.get(primaryKey2Name));
    }

    SpanPointerUtils.addDynamoDbSpanPointer(
        span, tableName, primaryKey1Name, primaryKey1Value, primaryKey2Name, primaryKey2Value);
  }
}
