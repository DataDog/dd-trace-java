package datadog.json

import spock.lang.Specification

import static java.lang.Math.PI
import static java.util.Collections.emptyMap

class JsonMapperTest extends Specification {

  def "test mapping to JSON object: #input"() {
    setup:
    def parsedExpected = input == null ? emptyMap() : input.clone()
    parsedExpected.collect {
      it -> {
        if (it.value instanceof UnsupportedType) {
          it.value = it.value.toString()
        } else if (it.value instanceof Float) {
          it.value = new Double(it.value)
        }

        it
      }
    }

    when:
    String json = JsonMapper.toJson((Map) input)

    then:
    json == expected

    when:
    def parsed = JsonMapper.fromJsonToMap(json)

    then:
    if (input == null) {
      parsed == [:]
    } else {
      parsed.size() == input.size()
      input.each {
        assert parsed.containsKey(it.key)
        if (it.value instanceof UnsupportedType) {
          assert parsed.get(it.key) == it.value.toString()
        } else if (it.value instanceof Float) {
          assert parsed.get(it.key) instanceof Double
          assert (parsed.get(it.key) - it.value) < 0.001
        } else {
          assert parsed.get(it.key) == it.value
        }
      }
    }

    where:
    input                                   | expected
    null                                    | '{}'
    new HashMap<>()                         | '{}'
    ['key1': 'value1']                      | '{"key1":"value1"}'
    ['key1': 'value1', 'key2': 'value2']    | '{"key1":"value1","key2":"value2"}'
    ['key1': 'va"lu"e1', 'ke"y2': 'value2'] | '{"key1":"va\\"lu\\"e1","ke\\"y2":"value2"}'
    ['key1': null, 'key2': 'bar', 'key3': 3, 'key4': 3456789123L, 'key5': 3.142f, 'key6': PI, 'key7': true, 'key8': new UnsupportedType()] | '{"key1":null,"key2":"bar","key3":3,"key4":3456789123,"key5":3.142,"key6":3.141592653589793,"key7":true,"key8":"toString"}'
  }

  private class UnsupportedType {
    @Override
    String toString() {
      'toString'
    }
  }

  def "test mapping to Map from empty JSON object"() {
    when:
    def parsed = JsonMapper.fromJsonToMap(json)

    then:
    parsed == [:]

    where:
    json << [null, 'null', '', '{}']
  }

  def "test mapping to Map from non-object JSON"() {
    when:
    JsonMapper.fromJsonToMap(json)

    then:
    thrown(IOException)

    where:
    json << ['1', '[1, 2]']
  }

  def "test mapping iterable to JSON array: #input"() {
    when:
    String json = JsonMapper.toJson(input as Collection<String>)

    then:
    json == expected

    when:
    def parsed = JsonMapper.fromJsonToList(json)

    then:
    parsed == (input?:[])

    where:
    input                  | expected
    null                   | "[]"
    new ArrayList<>()      | "[]"
    ['value1']             | "[\"value1\"]"
    ['value1', 'value2']   | "[\"value1\",\"value2\"]"
    ['va"lu"e1', 'value2'] | "[\"va\\\"lu\\\"e1\",\"value2\"]"
  }

  def "test mapping array to JSON array: #input"() {
    when:
    String json = JsonMapper.toJson((String[]) input)

    then:
    json == expected

    when:
    def parsed = JsonMapper.fromJsonToList(json).toArray(new String[0])

    then:
    parsed == (String[]) (input?:[])

    where:
    input                  | expected
    null                   | "[]"
    []                     | "[]"
    ['value1']             | "[\"value1\"]"
    ['value1', 'value2']   | "[\"value1\",\"value2\"]"
    ['va"lu"e1', 'value2'] | "[\"va\\\"lu\\\"e1\",\"value2\"]"
  }

  def "test mapping to List from empty JSON object"() {
    when:
    def parsed = JsonMapper.fromJsonToList(json)

    then:
    parsed == []

    where:
    json << [null, 'null', '', '[]']
  }

  def "test mapping to JSON string: input"() {
    when:
    String escaped = JsonMapper.toJson((String) string)

    then:
    escaped == expected

    where:
    string                   | expected
    null                     | ""
    ""                       | ""
    ((char) 4096).toString() | '"\\u1000"'
    ((char) 256).toString()  | '"\\u0100"'
    ((char) 128).toString()  | '"\\u0080"'
    "\b"                     | '"\\b"'
    "\t"                     | '"\\t"'
    "\n"                     | '"\\n"'
    "\f"                     | '"\\f"'
    "\r"                     | '"\\r"'
    '"'                      | '"\\\""'
    '/'                      | '"\\/"'
    '\\'                     | '"\\\\"'
    "a"                      | '"a"'
  }
}
