package datadog.trace.lambda

import datadog.trace.core.test.DDCoreSpecification
import com.squareup.moshi.Moshi

class SkipTypeJsonSerializerTest extends DDCoreSpecification {

  static class TestJsonObject {

    public String field1
    public boolean field2
    public NestedJsonObject field3

    TestJsonObject() {
      this.field1 = "toto"
      this.field2 = true
      this.field3 = new NestedJsonObject()
    }
  }

  static class NestedJsonObject {

    public String field

    NestedJsonObject() {
      this.field = "tutu"
    }
  }

  def "test skip String serialization"() {
    given:
    def adapter = new Moshi.Builder()
      .add(SkipTypeJsonSerializer.newFactory("java.lang.String"))
      .build()
      .adapter(Object)

    when:
    def result = adapter.toJson(new TestJsonObject())

    then:
    result == "{\"field1\":{},\"field2\":true,\"field3\":{\"field\":{}}}"
  }
}
