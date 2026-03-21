package datadog.trace.core.datastreams;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.datastreams.DataStreamsTransactionExtractor;
import datadog.trace.core.test.DDCoreSpecification;
import java.util.List;
import org.junit.jupiter.api.Test;

class DataStreamsTransactionExtractorsTest extends DDCoreSpecification {

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
    assertEquals(
        DataStreamsTransactionExtractor.Type.HTTP_OUT_HEADERS, extractors.get(0).getType());
    assertEquals("transaction_id", extractors.get(0).getValue());
    assertEquals("second_extractor", extractors.get(1).getName());
    assertEquals(DataStreamsTransactionExtractor.Type.HTTP_IN_HEADERS, extractors.get(1).getType());
    assertEquals("transaction_id", extractors.get(1).getValue());
  }
}
