package datadog.trace.core.datastreams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.instrumentation.api.Schema;
import datadog.trace.bootstrap.instrumentation.api.SchemaIterator;
import datadog.trace.core.DDCoreJavaSpecification;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

public class SchemaBuilderTest extends DDCoreJavaSpecification {

  static class Iterator implements SchemaIterator {
    @Override
    public void iterateOverSchema(
        datadog.trace.bootstrap.instrumentation.api.SchemaBuilder builder) {
      HashMap<String, String> extension = new HashMap<>(1);
      extension.put("x-test-extension-1", "hello");
      extension.put("x-test-extension-2", "world");
      builder.addProperty(
          "person", "name", false, "string", "name of the person", null, null, null, null);
      builder.addProperty("person", "phone_numbers", true, "string", null, null, null, null, null);
      builder.addProperty("person", "person_name", false, "string", null, null, null, null, null);
      builder.addProperty(
          "person",
          "address",
          false,
          "object",
          null,
          "#/components/schemas/address",
          null,
          null,
          null);
      builder.addProperty("address", "zip", false, "number", null, null, "int", null, null);
      builder.addProperty("address", "street", false, "string", null, null, null, null, extension);
    }
  }

  @Test
  void schemaIsConvertedCorrectlyToJson() {
    SchemaBuilder builder = new SchemaBuilder(new Iterator());

    boolean shouldExtractPerson = builder.shouldExtractSchema("person", 0);
    boolean shouldExtractAddress = builder.shouldExtractSchema("address", 1);
    boolean shouldExtractPerson2 = builder.shouldExtractSchema("person", 0);
    boolean shouldExtractTooDeep = builder.shouldExtractSchema("city", 11);
    Schema schema = builder.build();

    String expectedDefinition =
        "{\"components\":{\"schemas\":{\"person\":{\"properties\":{\"name\":{\"description\":\"name of the person\",\"type\":\"string\"},\"phone_numbers\":{\"items\":{\"type\":\"string\"},\"type\":\"array\"},\"person_name\":{\"type\":\"string\"},\"address\":{\"$ref\":\"#/components/schemas/address\",\"type\":\"object\"}},\"type\":\"object\"},\"address\":{\"properties\":{\"zip\":{\"format\":\"int\",\"type\":\"number\"},\"street\":{\"extensions\":{\"x-test-extension-1\":\"hello\",\"x-test-extension-2\":\"world\"},\"type\":\"string\"}},\"type\":\"object\"}}},\"openapi\":\"3.0.0\"}";
    assertEquals(expectedDefinition, schema.definition);
    assertEquals("16548065305426330543", schema.id);
    assertTrue(shouldExtractPerson);
    assertTrue(shouldExtractAddress);
    assertFalse(shouldExtractPerson2);
    assertFalse(shouldExtractTooDeep);
  }
}
