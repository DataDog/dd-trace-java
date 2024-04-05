package datadog.trace.core.datastreams

import datadog.trace.core.test.DDCoreSpecification

class SchemaBuilderTest extends DDCoreSpecification {

  def "schema is converted correctly to JSON"() {
    given:
    SchemaBuilder builder = new SchemaBuilder()

    when:
    boolean shouldExtractPerson = builder.shouldExtractSchema("person", 0)
    boolean shouldExtractAddress = builder.shouldExtractSchema("address", 1)
    boolean shouldExtractPerson2 = builder.shouldExtractSchema("person", 0)
    boolean shouldExtractTooDeep = builder.shouldExtractSchema("city", 11)
    builder.addProperty("person", "name", false, "string", "name of the person", null, null, null)
    builder.addProperty("person", "phone_numbers", true, "string", null, null, null, null)
    builder.addProperty("person", "person_name", false, "string", null, null, null, null)
    builder.addProperty("person", "address", false, "object", null, "#/components/schemas/address", null, null)
    builder.addProperty("address", "zip", false, "number", null, null, "int", null)
    builder.addProperty("address", "street", false, "string", null, null, null, null)
    String schema = builder.build()

    then:
    "{\"components\":{\"schemas\":{\"person\":{\"properties\":{\"name\":{\"description\":\"name of the person\",\"type\":\"string\"},\"phone_numbers\":{\"items\":{\"type\":\"string\"},\"type\":\"array\"},\"person_name\":{\"type\":\"string\"},\"address\":{\"\$ref\":\"#/components/schemas/address\",\"type\":\"object\"}},\"type\":\"object\"},\"address\":{\"properties\":{\"zip\":{\"format\":\"int\",\"type\":\"number\"},\"street\":{\"type\":\"string\"}},\"type\":\"object\"}}},\"openapi\":\"3.0.0\"}" == schema
    shouldExtractPerson
    shouldExtractAddress
    !shouldExtractPerson2
    !shouldExtractTooDeep
  }
}
