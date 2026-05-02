package datadog.trace.bootstrap.otlp.common;

/** A visitor to visit OpenTelemetry attributes. */
public interface OtlpAttributeVisitor {

  int STRING_ATTRIBUTE = 0; // AttributeType.STRING
  int BOOLEAN_ATTRIBUTE = 1; // AttributeType.BOOLEAN
  int LONG_ATTRIBUTE = 2; // AttributeType.LONG
  int DOUBLE_ATTRIBUTE = 3; // AttributeType.DOUBLE
  int STRING_ARRAY_ATTRIBUTE = 4; // AttributeType.STRING_ARRAY
  int BOOLEAN_ARRAY_ATTRIBUTE = 5; // AttributeType.BOOLEAN_ARRAY
  int LONG_ARRAY_ATTRIBUTE = 6; // AttributeType.LONG_ARRAY
  int DOUBLE_ARRAY_ATTRIBUTE = 7; // AttributeType.DOUBLE_ARRAY

  /**
   * Visits an attribute.
   *
   * @param type the attribute type
   * @param key the attribute key
   * @param value the attribute value
   */
  void visitAttribute(int type, String key, Object value);
}
