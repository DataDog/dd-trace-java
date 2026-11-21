import com.google.gson.Gson
import com.google.gson.JsonIOException
import com.google.gson.JsonSyntaxException
import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonWriter
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.Tags

import java.io.StringReader
import java.io.StringWriter

class GsonInstrumentationTest extends InstrumentationSpecification {

  def gson = new Gson()

  def "test toJson creates a span"() {
    when:
    def result = gson.toJson(new TestObject("hello", 42))

    then:
    result != null
    result.contains("hello")
    assertTraces(1) {
      trace(1) {
        span {
          operationName "gson.toJson"
          spanType "json"
          errored false
          tags {
            defaultTags()
            "$Tags.COMPONENT" "gson"
            "$Tags.SPAN_KIND" "internal"
            "json.source.type" TestObject.name
          }
        }
      }
    }
  }

  def "test fromJson with Class creates a span"() {
    when:
    def result = gson.fromJson('{"name":"hello","value":42}', TestObject)

    then:
    result != null
    result.name == "hello"
    result.value == 42
    assertTraces(1) {
      trace(1) {
        span {
          operationName "gson.fromJson"
          spanType "json"
          errored false
          tags {
            defaultTags()
            "$Tags.COMPONENT" "gson"
            "$Tags.SPAN_KIND" "internal"
            "json.target.type" TestObject.name
          }
        }
      }
    }
  }

  def "test fromJson with TypeToken creates a span"() {
    setup:
    def typeToken = new TypeToken<List<TestObject>>() {}

    when:
    def result = gson.fromJson('[{"name":"a","value":1},{"name":"b","value":2}]', typeToken.getType())

    then:
    result != null
    result.size() == 2
    result[0].name == "a"
    assertTraces(1) {
      trace(1) {
        span {
          operationName "gson.fromJson"
          spanType "json"
          errored false
          tags {
            defaultTags()
            "$Tags.COMPONENT" "gson"
            "$Tags.SPAN_KIND" "internal"
            "json.target.type" String
          }
        }
      }
    }
  }

  def "test nested toJson calls produce exactly one span"() {
    setup:
    def outer = new NestingObject(gson, new TestObject("nested", 99))
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.clear()

    when:
    def result = gson.toJson(outer)

    then:
    result != null
    assertTraces(1) {
      trace(1) {
        span {
          operationName "gson.toJson"
          spanType "json"
          errored false
          tags {
            defaultTags()
            "$Tags.COMPONENT" "gson"
            "$Tags.SPAN_KIND" "internal"
            "json.source.type" NestingObject.name
          }
        }
      }
    }
  }

  def "test nested fromJson calls produce exactly one span"() {
    when:
    def result = gson.fromJson('{"inner":{"name":"nested","value":99}}', OuterObject)

    then:
    result != null
    result.inner != null
    result.inner.name == "nested"
    assertTraces(1) {
      trace(1) {
        span {
          operationName "gson.fromJson"
          spanType "json"
          errored false
          tags {
            defaultTags()
            "$Tags.COMPONENT" "gson"
            "$Tags.SPAN_KIND" "internal"
            "json.target.type" OuterObject.name
          }
        }
      }
    }
  }

  def "test fromJson with malformed input sets error tags"() {
    when:
    gson.fromJson('{invalid json', TestObject)

    then:
    thrown(JsonSyntaxException)
    assertTraces(1) {
      trace(1) {
        span {
          operationName "gson.fromJson"
          spanType "json"
          errored true
          tags {
            defaultTags()
            "$Tags.COMPONENT" "gson"
            "$Tags.SPAN_KIND" "internal"
            "json.target.type" TestObject.name
            errorTags(JsonSyntaxException, String)
          }
        }
      }
    }
  }

  def "test toJson with Type parameter creates a span"() {
    setup:
    def list = [new TestObject("a", 1), new TestObject("b", 2)]
    def type = new TypeToken<List<TestObject>>() {}.getType()

    when:
    def result = gson.toJson(list, type)

    then:
    result != null
    result.contains("a")
    result.contains("b")
    assertTraces(1) {
      trace(1) {
        span {
          operationName "gson.toJson"
          spanType "json"
          errored false
          tags {
            defaultTags()
            "$Tags.COMPONENT" "gson"
            "$Tags.SPAN_KIND" "internal"
            "json.source.type" ArrayList.name
          }
        }
      }
    }
  }

  def "test fromJson with Reader creates a span"() {
    setup:
    def reader = new StringReader('{"name":"streamed","value":7}')

    when:
    def result = gson.fromJson(reader, TestObject)

    then:
    result != null
    result.name == "streamed"
    result.value == 7
    assertTraces(1) {
      trace(1) {
        span {
          operationName "gson.fromJson"
          spanType "json"
          errored false
          tags {
            defaultTags()
            "$Tags.COMPONENT" "gson"
            "$Tags.SPAN_KIND" "internal"
            "json.target.type" TestObject.name
          }
        }
      }
    }
  }

  def "test toJson with Appendable writes and creates a span"() {
    setup:
    def writer = new StringWriter()

    when:
    gson.toJson(new TestObject("buffered", 3), writer)

    then:
    def output = writer.toString()
    output.contains("buffered")
    output.contains("3")
    assertTraces(1) {
      trace(1) {
        span {
          operationName "gson.toJson"
          spanType "json"
          errored false
          tags {
            defaultTags()
            "$Tags.COMPONENT" "gson"
            "$Tags.SPAN_KIND" "internal"
            "json.source.type" TestObject.name
          }
        }
      }
    }
  }

  def "test toJson with failing serializer sets error tags"() {
    setup:
    def failingGson = new Gson()

    when:
    failingGson.toJson(new TestObject("ok", 1), new FailingAppendable())

    then:
    thrown(JsonIOException)
    assertTraces(1) {
      trace(1) {
        span {
          operationName "gson.toJson"
          spanType "json"
          errored true
          tags {
            defaultTags()
            "$Tags.COMPONENT" "gson"
            "$Tags.SPAN_KIND" "internal"
            "json.source.type" TestObject.name
            errorTags(JsonIOException, String)
          }
        }
      }
    }
  }

  static class TestObject {
    String name
    int value

    TestObject() {}

    TestObject(String name, int value) {
      this.name = name
      this.value = value
    }
  }

  static class OuterObject {
    TestObject inner

    OuterObject() {}
  }

  /**
   * Object whose serialization triggers a nested toJson call internally,
   * used to verify the call-depth guard produces exactly one span.
   */
  static class NestingObject {
    String preSerializedInner

    NestingObject() {}

    NestingObject(Gson gson, TestObject inner) {
      // Pre-serialize the inner object — this triggers a nested Gson.toJson() call
      this.preSerializedInner = gson.toJson(inner)
    }
  }

  static class FailingAppendable implements Appendable {
    @Override
    Appendable append(CharSequence csq) throws IOException {
      throw new IOException("Simulated write failure")
    }

    @Override
    Appendable append(CharSequence csq, int start, int end) throws IOException {
      throw new IOException("Simulated write failure")
    }

    @Override
    Appendable append(char c) throws IOException {
      throw new IOException("Simulated write failure")
    }
  }
}
