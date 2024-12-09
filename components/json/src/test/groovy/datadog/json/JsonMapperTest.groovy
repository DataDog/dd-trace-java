package datadog.json

import spock.lang.Specification

import static java.lang.Math.PI

class JsonMapperTest extends Specification {

  def "test mapping to JSON object: #input"() {
    when:
    String json = JsonMapper.toJson((Map) input)

    then:
    json == expected

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

  def "test mapping iterable to JSON array: #input"() {
    when:
    String json = JsonMapper.toJson(input as Collection<String>)

    then:
    json == expected

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

    where:
    input                  | expected
    null                   | "[]"
    []                     | "[]"
    ['value1']             | "[\"value1\"]"
    ['value1', 'value2']   | "[\"value1\",\"value2\"]"
    ['va"lu"e1', 'value2'] | "[\"va\\\"lu\\\"e1\",\"value2\"]"
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
