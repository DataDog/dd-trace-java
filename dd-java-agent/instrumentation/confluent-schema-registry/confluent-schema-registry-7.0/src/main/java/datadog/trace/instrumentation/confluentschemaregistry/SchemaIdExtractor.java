package datadog.trace.instrumentation.confluentschemaregistry;

/**
 * Helper class to extract schema ID from Confluent Schema Registry wire format. Wire format:
 * [magic_byte][4-byte schema id][data]
 */
public class SchemaIdExtractor {
  public static int extractSchemaId(byte[] data) {
    if (data == null || data.length < 5 || data[0] != 0) {
      return -1;
    }

    try {
      // Confluent wire format: [magic_byte][4-byte schema id][data]
      return ((data[1] & 0xFF) << 24)
          | ((data[2] & 0xFF) << 16)
          | ((data[3] & 0xFF) << 8)
          | (data[4] & 0xFF);
    } catch (Throwable ignored) {
      return -1;
    }
  }
}
