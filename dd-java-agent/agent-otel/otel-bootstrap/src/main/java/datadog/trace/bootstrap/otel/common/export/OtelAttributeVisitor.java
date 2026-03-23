package datadog.trace.bootstrap.otel.common.export;

/** A visitor to visit OpenTelemetry attributes. */
public interface OtelAttributeVisitor {

  int STRING = 0; // AttributeType.STRING
  int BOOLEAN = 1; // AttributeType.BOOLEAN
  int LONG = 2; // AttributeType.LONG
  int DOUBLE = 3; // AttributeType.DOUBLE
  int STRING_ARRAY = 4; // AttributeType.STRING_ARRAY
  int BOOLEAN_ARRAY = 5; // AttributeType.BOOLEAN_ARRAY
  int LONG_ARRAY = 6; // AttributeType.LONG_ARRAY
  int DOUBLE_ARRAY = 7; // AttributeType.DOUBLE_ARRAY

  void visitAttribute(int type, String key, Object value);
}
