package datadog.trace.payloadtags

import spock.lang.Specification

class JsonToTagsTest extends Specification {

  def "expand, redact, traverse"() {
    JsonToTags jsonToTags = new JsonToTags.Builder()
      .parseExpansionRules(['$.Message'])
      .parseRedactionRules(['$.MessageAttributes.*.StringValue', '$.Message.password'])
      .tagPrefix("dd")
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
    jsonToTags.process(json) == [
      "dd.Message.a":1.15,
      "dd.Message.password":"redacted",
      "dd.MessageAttributes.baz.DataType":"String",
      "dd.MessageAttributes.baz.StringValue":"redacted",
      "dd.MessageAttributes.keyOne.DataType":"Number",
      "dd.MessageAttributes.keyOne.Value":42,
      "dd.MessageAttributes.keyTwo.DataType":"String",
      "dd.MessageAttributes.keyTwo.StringValue":"redacted"
    ]
  }

  def "traverse primitive types"() {
    JsonToTags jsonToTags = new JsonToTags.Builder().build()

    def json = """{
      "a": 1,
      "b": 2.0,
      "c": "string",
      "d": true,
      "e": false,
      "f": null
    }"""

    expect:
    jsonToTags.process(json) == [
      ".a": 1,
      ".b": 2.0,
      ".c": "string",
      ".d": true,
      ".e": false,
      ".f": null
    ]
  }

  def "traverse empty types"() {
    JsonToTags jsonToTags = new JsonToTags.Builder().build()

    def json = """{
      "foo": {},
      "bar": [],
    }"""

    expect:
    jsonToTags.process(json) == [:]
  }

  def "traverse nested arrays"() {
    JsonToTags jsonToTags = new JsonToTags.Builder().build()

    def json = """{
      "a": [[ 1 ], [ 2, 3 ]],
    }"""

    expect:
    jsonToTags.process(json) == [
      ".a.0.0": 1,
      ".a.1.0": 2,
      ".a.1.1": 3,
    ]
  }

  def "traverse nested objects"() {
    JsonToTags jsonToTags = new JsonToTags.Builder().build()

    def json = """{
      "a": { "b": { "c": { "d": "e" } } },
    }"""

    expect:
    jsonToTags.process(json) == [
      ".a.b.c.d": "e",
    ]
  }

  def "traverse nested mixed objects and arrays"() {
    JsonToTags jsonToTags = new JsonToTags.Builder().build()

    def json = """{
      "a": [ "b", { "c": [ { "d": "e"} ] } ],
    }"""

    expect:
    jsonToTags.process(json) == [
      ".a.0": "b",
      ".a.1.c.0.d": "e",
    ]
  }

  def "limit number of tags"() {
    JsonToTags jsonToTags = new JsonToTags.Builder()
      .limitTags(5)
      .build()

    def json = """{
      "a": 1,
      "b": 2,
      "c": 3,
      "d": 4,
      "e": 5,
      "f": 6,
      "g": 7,
      "h": 8,
      "i": 9,
    }"""

    expect:
    jsonToTags.process(json) == [
      ".a": 1,
      ".b": 2,
      ".c": 3,
      ".d": 4,
      ".e": 5,
      "_dd.payload_tags_incomplete": true
    ]
  }

  def "escape dots in property names"() {
    JsonToTags jsonToTags = new JsonToTags.Builder().build()

    def json = """{
      "a.b": 1,
      "c.d": 2,
    }"""

    expect:
    jsonToTags.process(json) == [
      ".a\\.b": 1,
      ".c\\.d": 2,
    ]
  }

  def "limit by deepness"() {
    JsonToTags jsonToTags = new JsonToTags.Builder()
      .limitDeepness(3)
      .build()

    def json = """{
      "a": {
        "b": {
          "c": {
            "d": 1
          },
          "e": 2
        }
      }
    }"""

    expect:
    jsonToTags.process(json) == [
      ".a.b.e": 2
    ]
  }

  def "prefix tags"() {
    JsonToTags jsonToTags = new JsonToTags.Builder()
      .tagPrefix("prefix")
      .build()

    def json = """{
      "a": 1,
      "b": 2,
    }"""

    expect:
    jsonToTags.process(json) == [
      "prefix.a": 1,
      "prefix.b": 2,
    ]
  }

  def "ignore missing expansion and redaction paths"() {
    JsonToTags jsonToTags = new JsonToTags.Builder()
      .parseExpansionRules(['$.Message'])
      .parseRedactionRules(['$.MessageAttributes.*.StringValue', '$.Message.password'])
      .build()

    def json = """{
          "foo": "bar"
        }"""

    expect:
    jsonToTags.process(json) == [
      ".foo":"bar",
    ]
  }

  def "handle expansion parse errors"() {
    JsonToTags jsonToTags = new JsonToTags.Builder()
      .parseExpansionRules(['$.Message'])
      .build()

    def json = """{
          "Message": "${invalidInnerJson}",
        }"""

    expect:
    jsonToTags.process(json) == [
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
    def invalidRuleWithLeadingSpace = ' $.Message'
    JsonToTags jsonToTags = new JsonToTags.Builder()
      .parseExpansionRules([invalidRuleWithLeadingSpace])
      .parseRedactionRules([invalidRuleWithLeadingSpace])
      .build()

    String inner = "{ 'a: 1.15, 'password': 'my-secret-password' }"

    def json = """{
          "Message": "${inner}",
        }"""

    expect: "Message attribute neither expanded nor redacted because of invalid rules"
    jsonToTags.process(json) == [".Message":"{ 'a: 1.15, 'password': 'my-secret-password' }"]
  }

  def "ignore invalid json, return an empty tag map"() {
    JsonToTags jsonToTags = new JsonToTags.Builder().build()

    expect:
    jsonToTags.process(invalidJson) == [:]

    where:
    invalidJson << [
      "{",
      "}",
      "[",
      "]",
      "null",
      "{ 'a: 1.15,",
      "body", // expect an object not a string
    ]
  }
}
