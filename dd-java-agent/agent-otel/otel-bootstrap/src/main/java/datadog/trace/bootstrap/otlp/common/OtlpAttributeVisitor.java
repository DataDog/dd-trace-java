package datadog.trace.bootstrap.otlp.common;

/** A visitor to visit OpenTelemetry attributes. */
public interface OtlpAttributeVisitor {

  int STRING = 0; // AttributeType.STRING
  int BOOLEAN = 1; // AttributeType.BOOLEAN
  int LONG = 2; // AttributeType.LONG
  int DOUBLE = 3; // AttributeType.DOUBLE
  int STRING_ARRAY = 4; // AttributeType.STRING_ARRAY
  int BOOLEAN_ARRAY = 5; // AttributeType.BOOLEAN_ARRAY
  int LONG_ARRAY = 6; // AttributeType.LONG_ARRAY
  int DOUBLE_ARRAY = 7; // AttributeType.DOUBLE_ARRAY

  /**
   * Visits an attribute.
   *
   * @param type the attribute type
   * @param key the attribute key
   * @param value the attribute value
   */
  void visitAttribute(int type, String key, Object value);
}
