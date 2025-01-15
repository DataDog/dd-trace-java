package datadog.trace.bootstrap.instrumentation.api;

import java.util.List;
import java.util.Map;

public interface SchemaBuilder {
  boolean addProperty(
      String schemaName,
      String fieldName,
      boolean isArray,
      String type,
      String description,
      String ref,
      String format,
      List<String> enumValue,
      Map<String, String> extensions);

  void addToHash(int value);

  void addToHash(String value);

  boolean shouldExtractSchema(String schemaName, int depth);
}
