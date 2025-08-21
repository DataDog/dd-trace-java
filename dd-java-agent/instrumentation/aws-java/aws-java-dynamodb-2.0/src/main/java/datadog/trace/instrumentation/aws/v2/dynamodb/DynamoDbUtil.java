package datadog.trace.instrumentation.aws.v2.dynamodb;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DYNAMO_PRIMARY_KEY_1;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DYNAMO_PRIMARY_KEY_1_VALUE;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DYNAMO_PRIMARY_KEY_2;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DYNAMO_PRIMARY_KEY_2_VALUE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
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
   * Gets primary key/values and exports them as temporary tags on the span so that
   * SpanPointersProcessor.java can complete the span pointer creation.
   *
   * @param span The span to set the temporary tags on
   * @param keys The primary key/values to extract from
   */
  static void exportTagsWithKnownKeys(AgentSpan span, Map<String, AttributeValue> keys) {
    if (keys == null || keys.isEmpty()) {
      return;
    }

    if (keys.size() == 1) {
      // Single primary key case
      Map.Entry<String, AttributeValue> entry = keys.entrySet().iterator().next();
      span.setTag(DYNAMO_PRIMARY_KEY_1, entry.getKey());
      span.setTag(DYNAMO_PRIMARY_KEY_1_VALUE, extractValueAsString(entry.getValue()));
    } else {
      // Sort keys alphabetically
      List<String> keyNames = new ArrayList<>(keys.keySet());
      Collections.sort(keyNames);

      // First key (alphabetically)
      String primaryKey1Name = keyNames.get(0);
      span.setTag(DYNAMO_PRIMARY_KEY_1, primaryKey1Name);
      span.setTag(DYNAMO_PRIMARY_KEY_1_VALUE, extractValueAsString(keys.get(primaryKey1Name)));

      // Second key
      String primaryKey2Name = keyNames.get(1);
      span.setTag(DYNAMO_PRIMARY_KEY_2, primaryKey2Name);
      span.setTag(DYNAMO_PRIMARY_KEY_2_VALUE, extractValueAsString(keys.get(primaryKey2Name)));
    }
  }
}
