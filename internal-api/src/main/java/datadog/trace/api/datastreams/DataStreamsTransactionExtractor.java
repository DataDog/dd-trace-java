package datadog.trace.api.datastreams;

public interface DataStreamsTransactionExtractor {
  enum Type {
    UNKNOWN,
    /** HTTP_OUT_HEADERS targets outgoing HTTP requests */
    HTTP_OUT_HEADERS,
    /** HTTP_IN_HEADERS targets incoming HTTP requests */
    HTTP_IN_HEADERS,
    /** KAFKA_CONSUME_HEADERS targets headers from consumed messages (after consume) */
    KAFKA_CONSUME_HEADERS,
    /** KAFKA_CONSUME_HEADERS targets headers from produced messages (before produce) */
    KAFKA_PRODUCE_HEADERS
  }

  /** getName returns transaction extractor name */
  String getName();

  /** getType returns transaction extractor type */
  Type getType();

  /** getType returns transaction extractor value */
  String getValue();
}
