package datadog.trace.payloadtags

import spock.lang.Specification

class JsonTagsCollectorTest extends Specification {

  def "expand, redact, traverse"() {
    JsonTagsCollector jsonToTags = new JsonTagsCollector.Builder()
      .parseRedactionRules(['$.MessageAttributes.*.StringValue', '$.Message.password'])
      .build()

    String inner = "{ 'a': 1.15, 'password': 'my-secret-password' }"

    def json = """{
          "Message": "${inner}",
          "MessageAttributes": {
            "baz": { "DataType": "String", "StringValue": "bar" },
            "keyOne": { "DataType": "Number", "Value": 42 },
            "keyTwo": { "DataType": "String", "StringValue": "keyTwo" }
          }
        }"""

    expect:
    process(jsonToTags, json, "dd") == [
      "dd.Message.a":"1.15",
      "dd.Message.password":"redacted",
      "dd.MessageAttributes.baz.DataType":"String",
      "dd.MessageAttributes.baz.StringValue":"redacted",
      "dd.MessageAttributes.keyOne.DataType":"Number",
      "dd.MessageAttributes.keyOne.Value":"42",
      "dd.MessageAttributes.keyTwo.DataType":"String",
      "dd.MessageAttributes.keyTwo.StringValue":"redacted"
    ]
  }

  def "traverse primitive values"() {
    JsonTagsCollector jsonToTags = new JsonTagsCollector.Builder().build()

    def json = """{
      "a": 1,
      "b": 2.0,
      "c": "string",
      "d": true,
      "e": false,
      "f": null
    }"""

    expect:
    process(jsonToTags, json, "") == [
      ".a": "1",
      ".b": "2.0",
      ".c": "string",
      ".d": true,
      ".e": false,
      ".f": "null"
    ]
  }

  def "traverse empty types"() {
    JsonTagsCollector jsonToTags = new JsonTagsCollector.Builder().build()

    def json = """{
      "foo": {},
      "bar": [],
    }"""

    expect:
    process(jsonToTags, json, "") == [:]
  }

  def "traverse nested arrays"() {
    JsonTagsCollector jsonToTags = new JsonTagsCollector.Builder().build()

    def json = """{
      "a": [[ 1 ], [ 2, 3 ]]
    }"""

    expect:
    process(jsonToTags, json, "") == [
      ".a.0.0": "1",
      ".a.1.0": "2",
      ".a.1.1": "3",
    ]
  }

  def "traverse nested objects"() {
    JsonTagsCollector jsonToTags = new JsonTagsCollector.Builder().build()

    def json = """{
      "a": { "b": { "c": { "d": "e" } } }
    }"""

    expect:
    process(jsonToTags, json, "") == [
      ".a.b.c.d": "e",
    ]
  }

  def "traverse nested mixed objects and arrays"() {
    JsonTagsCollector jsonToTags = new JsonTagsCollector.Builder().build()

    def json = """{
      "a": [ "b", { "c": [ { "d": "e"} ] } ]
    }"""

    expect:
    process(jsonToTags, json, "") == [
      ".a.0": "b",
      ".a.1.c.0.d": "e",
    ]
  }

  def "limit number of tags including inner json"() {
    JsonTagsCollector jsonToTags = new JsonTagsCollector.Builder()
      .limitTags(5)
      .build()

    def json = """{
      "a": [1, 2],
      "b": { "foo": 3 },
      "c": '[10,20,30,40]',
      "d": 4,
      "e": 5,
      "f": 6,
      "g": 7,
      "h": 8,
      "i": 9
    }"""

    expect:
    process(jsonToTags, json, "") == [
      ".a.0": "1",
      ".a.1": "2",
      ".b.foo": "3",
      ".c.0": "10",
      ".c.1": "20",
      "_dd.payload_tags_incomplete": true
    ]
  }

  def "limit depth of traversal"() {
    JsonTagsCollector jsonToTags = new JsonTagsCollector.Builder()
      .limitDeepness(3)
      .build()

    def json = """{
      "a": {
        "b": '{ "c": "1", "d": { "e": "2" } }',
        "f": {
          "g": '{ "k": "3" }',
          "j": 4,
          "l": { "m": 5 }
        },
        "k": 6,
        "n": '[1,2,3]',
        "o": ['a', { "foo": "bar" }, ['c'], 'z']
      }
    }"""

    expect:
    process(jsonToTags, json, "") == [
      ".a.b.c": "1",
      ".a.f.j": "4",
      ".a.k": "6",
      ".a.n.0": "1",
      ".a.n.1": "2",
      ".a.n.2": "3",
      ".a.o.0": "a",
      ".a.o.3": "z",
    ]
  }

  def "escape dots in property names"() {
    JsonTagsCollector jsonToTags = new JsonTagsCollector.Builder().build()

    def json = """{
      "a.b": 1,
      "c.d": 2
    }"""

    expect:
    process(jsonToTags, json, "") == [
      ".a\\.b": "1",
      ".c\\.d": "2",
    ]
  }

  def "prefix tags"() {
    JsonTagsCollector jsonToTags = new JsonTagsCollector.Builder()
      .build()

    def json = """{
      "a": 1,
      "b": 2
    }"""

    expect:
    process(jsonToTags, json, "prefix") == [
      "prefix.a": "1",
      "prefix.b": "2",
    ]
  }

  def "ignore missing expansion and redaction paths"() {
    JsonTagsCollector jsonToTags = new JsonTagsCollector.Builder()
      .parseRedactionRules(['$.MessageAttributes.*.StringValue', '$.Message.password'])
      .build()

    def json = """{
          "foo": "bar"
        }"""

    expect:
    process(jsonToTags, json, "") == [
      ".foo":"bar",
    ]
  }

  def "handle expansion parse errors"() {
    JsonTagsCollector jsonToTags = new JsonTagsCollector.Builder()
      .build()

    def json = """{
          "Message": "${invalidInnerJson}"
        }"""

    expect:
    process(jsonToTags, json, "") == [
      ".Message":"${invalidInnerJson}",
    ]

    where:
    invalidInnerJson << [
      "{ 'a: 1.15, 'password': 'my-secret-password' }",
      "112",
      // not an json object or an array
      "true"
    ]
  }

  def "skip invalid rules"() {
    def invalidRuleWithLeadingSpace = '$$.Message'
    JsonTagsCollector jsonToTags = new JsonTagsCollector.Builder()
      .parseRedactionRules([invalidRuleWithLeadingSpace])
      .build()

    String inner = "{ 'a: 1.15, 'password': 'my-secret-password' }"

    def json = """{
          "Message": "${inner}"
        }"""

    expect: "Message attribute neither expanded nor redacted because of invalid rules"
    process(jsonToTags, json, "") == [".Message":"{ 'a: 1.15, 'password': 'my-secret-password' }"]
  }

  def "ignore invalid json, return an empty tag map"() {
    JsonTagsCollector jsonToTags = new JsonTagsCollector.Builder().build()

    expect:
    process(jsonToTags, invalidJson, "") == [:]

    where:
    invalidJson << [
      "{",
      "}",
      "[",
      "]",
      //      "null",
      "{ 'a: 1.15,",
      //      "body", // expect an object not a string
    ]
  }

  Map<String, Object> process(JsonTagsCollector jsonToTags, String str, String tagPrefix) {
    try (InputStream is = new ByteArrayInputStream(str.getBytes())) {
      return jsonToTags.process(is, tagPrefix)
    }
  }
}
