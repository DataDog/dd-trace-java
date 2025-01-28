package datadog.trace.core.datastreams;

import com.squareup.moshi.Json;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.Schema;
import datadog.trace.bootstrap.instrumentation.api.SchemaIterator;
import datadog.trace.util.FNV64Hash;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SchemaBuilder implements datadog.trace.bootstrap.instrumentation.api.SchemaBuilder {
  private final OpenApiSchema schema = new OpenApiSchema();
  private static final DDCache<String, Schema> CACHE = DDCaches.newFixedSizeCache(32);
  private static final int maxDepth = 10;
  private static final int maxProperties = 1000;
  private static final long HASH_INIT = FNV64Hash.generateHash(new byte[0], FNV64Hash.Version.v1A);
  private long currentHash = HASH_INIT;
  private int properties;
  private final SchemaIterator iterator;

  public SchemaBuilder(SchemaIterator iterator) {
    this.iterator = iterator;
  }

  @Override
  public boolean addProperty(
      String schemaName,
      String fieldName,
      boolean isArray,
      String type,
      String description,
      String ref,
      String format,
      List<String> enumValues,
      Map<String, String> extensions) {
    if (properties >= maxProperties) {
      return false;
    }
    properties++;
    OpenApiSchema.Property property =
        new OpenApiSchema.Property(
            type, description, ref, format, enumValues, isArray ? null : extensions, null);
    if (isArray) {
      property = new OpenApiSchema.Property("array", null, null, null, null, extensions, property);
    }
    schema.components.schemas.get(schemaName).properties.put(fieldName, property);
    return true;
  }

  public void addToHash(int value) {
    addToHash(Integer.toString(value));
  }

  public void addToHash(String value) {
    currentHash = FNV64Hash.continueHash(currentHash, value, FNV64Hash.Version.v1A);
  }

  public Schema build() {
    this.iterator.iterateOverSchema(this);
    Moshi moshi = new Moshi.Builder().build();
    JsonAdapter<OpenApiSchema> jsonAdapter = moshi.adapter(OpenApiSchema.class);
    String definition = jsonAdapter.toJson(this.schema);
    if (currentHash == HASH_INIT) {
      // if hash was not computed along the way,
      // we fall back to computing it from the json representation of the schema
      currentHash = FNV64Hash.generateHash(definition, FNV64Hash.Version.v1A);
    }
    return new Schema(definition, Long.toUnsignedString(currentHash));
  }

  @Override
  public boolean shouldExtractSchema(String schemaName, int depth) {
    if (depth > maxDepth) {
      return false;
    }
    if (schema.components.schemas.containsKey(schemaName)) {
      return false;
    }
    schema.components.schemas.put(schemaName, new OpenApiSchema.Schema());
    return true;
  }

  public static class OpenApiSchema {
    public String openapi = "3.0.0";
    public final Components components = new Components();

    public static class Components {
      public final Map<String, Schema> schemas = new LinkedHashMap<>();
    }

    public static class Schema {
      public String type = "object";
      public final Map<String, Property> properties = new LinkedHashMap<>();
    }

    public static class Property {
      public String type;
      public String description;

      @Json(name = "$ref")
      public String ref;

      public String format;

      @Json(name = "enum")
      public List<String> enumValues;

      public final Map<String, String> extensions;
      public Property items;

      public Property(
          String type,
          String description,
          String ref,
          String format,
          List<String> enumValues,
          Map<String, String> extensions,
          Property items) {
        this.type = type;
        this.description = description;
        this.ref = ref;
        this.format = format;
        this.enumValues = enumValues;
        this.extensions = extensions;
        this.items = items;
      }
    }
  }

  public static Schema getSchema(String schemaName, SchemaIterator iterator) {
    return CACHE.computeIfAbsent(schemaName, s -> new SchemaBuilder(iterator).build());
  }
}
