package datadog.trace.lambda

import datadog.trace.core.test.DDCoreSpecification
import com.squareup.moshi.Moshi

abstract class ImpossibleToSerialize {
  public abstract void randomMethod()
  public String randomString
}


class SkipAbstractTypeJsonSerializer extends DDCoreSpecification {

  static class TestJsonObject {

    public String field1
    public boolean field2
    public ImpossibleToSerialize field3
    public NestedJsonObject field4

    TestJsonObject() {
      this.field1 = "toto"
      this.field2 = true
      this.field3 = null
      this.field4 = new NestedJsonObject()
    }
  }

  static class NestedJsonObject {

    public ImpossibleToSerialize field

    NestedJsonObject() {
      this.field = null
    }
  }

  def "test skip String serialization"() {
    given:
    def adapter = new Moshi.Builder()
      .add(SkipAbstractTypeJsonSerializer.newFactory())
      .build()
      .adapter(Object)

    when:
    def result = adapter.toJson(new TestJsonObject())

    then:
    result == "{\"field1\":{},\"field2\":true,\"field3\":{\"field\":{}}}"
  }
}
