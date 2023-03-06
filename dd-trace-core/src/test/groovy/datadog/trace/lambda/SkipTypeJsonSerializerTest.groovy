package datadog.trace.lambda

import datadog.trace.core.test.DDCoreSpecification
import com.squareup.moshi.Moshi

abstract class AbstractSerialize {
  public String randomString
}

class SubClass extends AbstractSerialize {
  SubClass() {
    this.randomString = "tutu"
  }
}

class SkipAbstractTypeJsonSerializerTest extends DDCoreSpecification {

  static class TestJsonObject {

    public String field1
    public boolean field2
    public AbstractSerialize field3
    public NestedJsonObject field4

    TestJsonObject() {
      this.field1 = "toto"
      this.field2 = true
      this.field3 = new SubClass()
      this.field4 = new NestedJsonObject()
    }
  }

  static class NestedJsonObject {

    public AbstractSerialize field

    NestedJsonObject() {
      this.field = new SubClass()
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
    result == "{\"field1\":\"toto\",\"field2\":true,\"field3\":{\"randomString\":\"tutu\"},\"field4\":{\"field\":{\"randomString\":\"tutu\"}}}"
  }
}
