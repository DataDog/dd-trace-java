package com.datadog.appsec.config

import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import datadog.trace.test.util.DDSpecification
import okio.Buffer

import java.lang.reflect.Constructor

class SafeMapAdapterTest extends DDSpecification {

  def adapter

  void setup() {
    // Access the private SafeMapAdapter class using reflection
    Class<?> safeMapAdapterClass = Class.forName('com.datadog.appsec.config.AppSecConfigServiceImpl$SafeMapAdapter')
    Constructor<?> constructor = safeMapAdapterClass.getDeclaredConstructor()
    constructor.setAccessible(true)
    adapter = constructor.newInstance()
  }

  void 'test fromJson with various JSON types'() {
    when:
    def result = adapter.fromJson(createReader(json))

    then:
    result == expected

    where:
    json                              | expected
    '{"key": "value"}'               | [key: "value"]
    '[1, 2, 3]'                      | [1L, 2L, 3L]
    '"hello"'                        | "hello"
    '123'                            | 123L
    '123.45'                         | 123.45d
    'true'                           | true
    'false'                          | false
    'null'                           | null
  }

  void 'test fromJson with nested objects'() {
    given:
    def json = '{"outer": {"inner": [1, "test", true]}}'

    when:
    def result = adapter.fromJson(createReader(json))

    then:
    result == [outer: [inner: [1L, "test", true]]]
  }

  void 'test fromJson with number parsing edge cases'() {
    when:
    def result = adapter.fromJson(createReader(json))

    then:
    result == expected

    where:
    json          | expected
    '0'           | 0L
    '0.0'         | 0.0d
    '-123'        | -123L
    '-123.45'     | -123.45d
    '1.7976931348623157E308' | Double.parseDouble('1.7976931348623157E308')
  }

  void 'test toJson throws UnsupportedOperationException'() {
    given:
    def writer = JsonWriter.of(new Buffer())

    when:
    adapter.toJson(writer, "test")

    then:
    thrown(UnsupportedOperationException)
  }

  void 'test fromJson with unexpected token throws IllegalStateException'() {
    given:
    // Mock a JsonReader that returns an unexpected token
    def reader = Mock(JsonReader)
    reader.peek() >> JsonReader.Token.END_DOCUMENT

    when:
    adapter.fromJson(reader)

    then:
    def ex = thrown(IllegalStateException)
    ex.message.contains("Unexpected token")
  }

  void 'test fromJson with empty objects and arrays'() {
    when:
    def result = adapter.fromJson(createReader(json))

    then:
    result == expected

    where:
    json    | expected
    '{}'    | [:]
    '[]'    | []
  }

  void 'test fromJson with complex nested structure'() {
    given:
    def json = '''
    {
      "rules": [
        {
          "id": "test-rule",
          "enabled": true,
          "priority": 100,
          "threshold": 0.95,
          "metadata": null,
          "tags": ["security", "waf"]
        }
      ],
      "version": "2.1",
      "active": false
    }
    '''

    when:
    def result = adapter.fromJson(createReader(json))

    then:
    result instanceof Map
    result.rules instanceof List
    result.rules[0].id == "test-rule"
    result.rules[0].enabled == true
    result.rules[0].priority == 100L
    result.rules[0].threshold == 0.95d
    result.rules[0].metadata == null
    result.rules[0].tags == ["security", "waf"]
    result.version == "2.1"
    result.active == false
  }

  private JsonReader createReader(String json) {
    def buffer = new Buffer()
    buffer.writeUtf8(json)
    return JsonReader.of(buffer)
  }
}
