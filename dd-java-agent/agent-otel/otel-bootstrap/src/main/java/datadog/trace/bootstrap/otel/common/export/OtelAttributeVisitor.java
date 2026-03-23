package datadog.trace.bootstrap.otel.common.export;

import io.opentelemetry.api.common.AttributeType;

/** A visitor to visit OpenTelemetry attributes. */
public interface OtelAttributeVisitor {

  int STRING = AttributeType.STRING.ordinal();
  int BOOLEAN = AttributeType.BOOLEAN.ordinal();
  int LONG = AttributeType.LONG.ordinal();
  int DOUBLE = AttributeType.DOUBLE.ordinal();
  int STRING_ARRAY = AttributeType.STRING_ARRAY.ordinal();
  int BOOLEAN_ARRAY = AttributeType.BOOLEAN_ARRAY.ordinal();
  int LONG_ARRAY = AttributeType.LONG_ARRAY.ordinal();
  int DOUBLE_ARRAY = AttributeType.DOUBLE_ARRAY.ordinal();

  void visitAttribute(int type, String key, Object value);
}
