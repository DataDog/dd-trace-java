package datadog.trace.lambda

import datadog.trace.core.test.DDCoreSpecification
import com.squareup.moshi.Moshi
import java.io.ByteArrayInputStream;

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
    public ByteArrayInputStream field5

    TestJsonObject() {
      this.field1 = "toto"
      this.field2 = true
      this.field3 = new SubClass()
      this.field4 = new NestedJsonObject()
      this.field5 = new ByteArrayInputStream();
    }
  }

  static class NestedJsonObject {

    public AbstractSerialize field

    NestedJsonObject() {
      this.field = new SubClass()
    }
  }

  def "test string serialization"() {
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


  def "test simple case"() {
    given:
    def adapter = new Moshi.Builder()
      .add(SkipAbstractTypeJsonSerializer.newFactory())
      .build()
      .adapter(Object)

    when:
    def list = new LinkedHashMap<String, String>()
    list.put("key0","item0")
    list.put("key1","item1")
    list.put("key2","item2")
    def result = adapter.toJson(list)

    then:
    result == "{\"key0\":\"item0\",\"key1\":\"item1\",\"key2\":\"item2\"}"
  }
}
