package datadog.trace.core.datastreams;

import com.squareup.moshi.Json;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SchemaBuilder implements datadog.trace.bootstrap.instrumentation.api.SchemaBuilder {
  private final OpenApiSchema schema = new OpenApiSchema();

  @Override
  public void addProperty(
      String schemaName,
      String fieldName,
      boolean isArray,
      String type,
      String description,
      String ref,
      String format,
      List<String> enumValues) {
    OpenApiSchema.Property property =
        new OpenApiSchema.Property(type, description, ref, format, enumValues, null);
    if (isArray) {
      property = new OpenApiSchema.Property("array", null, null, null, null, property);
    }
    schema
        .components
        .schemas
        .computeIfAbsent(schemaName, k -> new OpenApiSchema.Schema())
        .properties
        .put(fieldName, property);
  }

  @Override
  public String build() {
    Moshi moshi = new Moshi.Builder().build();
    JsonAdapter<OpenApiSchema> jsonAdapter = moshi.adapter(OpenApiSchema.class);
    return jsonAdapter.toJson(schema);
  }

  public static class OpenApiSchema {
    public String openapi = "3.0.0";
    public Components components;

    public OpenApiSchema() {
      this.components = new Components();
    }

    public static class Components {
      public Map<String, Schema> schemas = new LinkedHashMap<>();
    }

    public static class Schema {
      public String type = "object";
      public Map<String, Property> properties = new LinkedHashMap<>();
    }

    public static class Property {
      public String type;
      public String description;

      @Json(name = "$ref")
      public String ref;

      public String format;

      @Json(name = "enum")
      public List<String> enumValues;

      public Property items;

      public Property(
          String type,
          String description,
          String ref,
          String format,
          List<String> enumValues,
          Property items) {
        this.type = type;
        this.description = description;
        this.ref = ref;
        this.format = format;
        this.enumValues = enumValues;
        this.items = items;
      }
    }
  }
}
