package datadog.trace.core.datastreams;

import static datadog.trace.api.datastreams.DataStreamsTransactionExtractor.Type.HTTP_IN_HEADERS;
import static datadog.trace.api.datastreams.DataStreamsTransactionExtractor.Type.HTTP_OUT_HEADERS;
import static datadog.trace.api.datastreams.DataStreamsTransactionExtractor.Type.KAFKA_CONSUME_HEADERS;
import static datadog.trace.api.datastreams.DataStreamsTransactionExtractor.Type.KAFKA_PRODUCE_HEADERS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.datastreams.DataStreamsTransactionExtractor;
import datadog.trace.core.DDCoreJavaSpecification;
import java.util.List;
import org.junit.jupiter.api.Test;

public class DataStreamsTransactionExtractorsTest extends DDCoreJavaSpecification {
  @Test
  void deserializeFromJson() {
    DataStreamsTransactionExtractors list =
        DataStreamsTransactionExtractors.deserialize(
            "[\n"
                + "  {\"name\": \"extractor\", \"type\": \"HTTP_OUT_HEADERS\", \"value\": \"transaction_id\"},\n"
                + "  {\"name\": \"second_extractor\", \"type\": \"HTTP_IN_HEADERS\", \"value\": \"transaction_id\"}\n"
                + "]");
    List<DataStreamsTransactionExtractor> extractors = list.getExtractors();

    assertEquals(2, extractors.size());
    assertEquals("extractor", extractors.get(0).getName());
    assertEquals(HTTP_OUT_HEADERS, extractors.get(0).getType());
    assertEquals("transaction_id", extractors.get(0).getValue());
    assertEquals("second_extractor", extractors.get(1).getName());
    assertEquals(HTTP_IN_HEADERS, extractors.get(1).getType());
    assertEquals("transaction_id", extractors.get(1).getValue());
  }

  @Test
  void deserializeKafkaTypes() {
    DataStreamsTransactionExtractors list =
        DataStreamsTransactionExtractors.deserialize(
            "["
                + "{\"name\": \"consume\", \"type\": \"KAFKA_CONSUME_HEADERS\", \"value\": \"txn\"},"
                + "{\"name\": \"produce\", \"type\": \"KAFKA_PRODUCE_HEADERS\", \"value\": \"txn\"}"
                + "]");
    List<DataStreamsTransactionExtractor> extractors = list.getExtractors();

    assertEquals(2, extractors.size());
    assertEquals(KAFKA_CONSUME_HEADERS, extractors.get(0).getType());
    assertEquals(KAFKA_PRODUCE_HEADERS, extractors.get(1).getType());
  }

  @Test
  void deserializeUnknownTypeReturnsEmpty() {
    DataStreamsTransactionExtractors list =
        DataStreamsTransactionExtractors.deserialize(
            "[{\"name\": \"ext\", \"type\": \"NOT_A_REAL_TYPE\", \"value\": \"v\"}]");

    assertSame(DataStreamsTransactionExtractors.EMPTY, list);
    assertTrue(list.getExtractors().isEmpty());
  }

  @Test
  void deserializeEmptyArrayReturnsEmptyList() {
    DataStreamsTransactionExtractors list = DataStreamsTransactionExtractors.deserialize("[]");

    assertTrue(list.getExtractors().isEmpty());
  }

  @Test
  void deserializeInvalidJsonReturnsEmpty() {
    DataStreamsTransactionExtractors list =
        DataStreamsTransactionExtractors.deserialize("not valid json");

    assertSame(DataStreamsTransactionExtractors.EMPTY, list);
    assertTrue(list.getExtractors().isEmpty());
  }

  @Test
  void deserializeNullJsonReturnsEmpty() {
    DataStreamsTransactionExtractors list = DataStreamsTransactionExtractors.deserialize("null");

    assertTrue(list.getExtractors().isEmpty());
  }

  @Test
  void implToStringContainsFields() {
    DataStreamsTransactionExtractors list =
        DataStreamsTransactionExtractors.deserialize(
            "[{\"name\": \"myext\", \"type\": \"HTTP_OUT_HEADERS\", \"value\": \"myval\"}]");
    String str = list.getExtractors().get(0).toString();

    assertTrue(str.contains("myext"));
    assertTrue(str.contains("HTTP_OUT_HEADERS"));
    assertTrue(str.contains("myval"));
  }
}
