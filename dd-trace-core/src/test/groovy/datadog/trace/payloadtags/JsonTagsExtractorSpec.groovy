package datadog.trace.payloadtags

import com.squareup.moshi.JsonWriter
import okio.Buffer
import spock.lang.Specification

class JsonTagsExtractorSpec extends Specification {

  //  def "expand, redact, traverse"() {
  //    RedactionJsonPaths redactionPaths = new RedactionJsonPaths.Builder()
  //      .addRedactionJsonPaths(['$.MessageAttributes.*.StringValue'])
  //      .addRedactionJsonPaths(['$.Message.password'])
  //      .build()
  //
  //    JsonTagsExtractor jsonTagsExtractor = new JsonTagsExtractor.Builder()
  //      .redactionPaths(redactionPaths)
  //      .build()
  //
  //    String inner = "{ 'a': 1.15, 'password': 'my-secret-password' }"
  //
  //    def json = """{
  //          "Message": "${inner}",
  //          "MessageAttributes": {
  //            "baz": { "DataType": "String", "StringValue": "bar" },
  //            "keyOne": { "DataType": "Number", "Value": 42 },
  //            "keyTwo": { "DataType": "String", "StringValue": "keyTwo" }
  //          }
  //        }"""
  //
  //    expect:
  //    extractTags(jsonTagsExtractor, json, "dd") == [
  //      "dd.Message.a"                           : "1.15",
  //      "dd.Message.password"                    : "redacted",
  //      "dd.MessageAttributes.baz.DataType"      : "String",
  //      "dd.MessageAttributes.baz.StringValue"   : "redacted",
  //      "dd.MessageAttributes.keyOne.DataType"   : "Number",
  //      "dd.MessageAttributes.keyOne.Value"      : "42",
  //      "dd.MessageAttributes.keyTwo.DataType"   : "String",
  //      "dd.MessageAttributes.keyTwo.StringValue": "redacted"
  //    ]
  //  }

  //  def "traverse primitive values"() {
  //    JsonTagsExtractor jsonTagsExtractor = new JsonTagsExtractor.Builder().build()
  //
  //    def json = """{
  //      "a": 1,
  //      "b": 2.0,
  //      "c": "string",
  //      "d": true,
  //      "e": false,
  //      "f": null
  //    }"""
  //
  //    expect:
  //    extractTags(jsonTagsExtractor, json, "") == [
  //      ".a": "1",
  //      ".b": "2.0",
  //      ".c": "string",
  //      ".d": true,
  //      ".e": false,
  //      ".f": "null"
  //    ]
  //  }
  //
  //  def "redact all types of value"() {
  //    RedactionJsonPaths redactionPaths = new RedactionJsonPaths.Builder()
  //      .addRedactionJsonPaths(['$.*'])
  //      .build()
  //
  //    JsonTagsExtractor jsonTagsExtractor = new JsonTagsExtractor.Builder()
  //      .redactionPaths(redactionPaths)
  //      .build()
  //
  //    def json = """{
  //      "a": 1,
  //      "b": 2.0,
  //      "c": "string",
  //      "d": true,
  //      "e": false,
  //      "f": null,
  //      "g": [1, 2, 3],
  //      "h": { "foo": "bar" }
  //    }"""
  //
  //    expect:
  //    extractTags(jsonTagsExtractor, json, "") == [
  //      ".a": "redacted",
  //      ".b": "redacted",
  //      ".c": "redacted",
  //      ".d": "redacted",
  //      ".e": "redacted",
  //      ".f": "redacted",
  //      ".g": "redacted",
  //      ".h": "redacted"
  //    ]
  //  }
  //
  //  def "traverse empty types"() {
  //    JsonTagsExtractor jsonTagsExtractor = new JsonTagsExtractor.Builder().build()
  //
  //    def json = """{
  //      "foo": {},
  //      "bar": [],
  //    }"""
  //
  //    expect:
  //    extractTags(jsonTagsExtractor, json, "") == [:]
  //  }
  //
  //  def "traverse nested arrays"() {
  //    JsonTagsExtractor jsonTagsExtractor = new JsonTagsExtractor.Builder().build()
  //
  //    def json = """{
  //      "a": [[ 1 ], [ 2, 3 ]]
  //    }"""
  //
  //    expect:
  //    extractTags(jsonTagsExtractor, json, "") == [
  //      ".a.0.0": "1",
  //      ".a.1.0": "2",
  //      ".a.1.1": "3",
  //    ]
  //  }
  //
  //  def "traverse nested objects"() {
  //    JsonTagsExtractor jsonTagsExtractor = new JsonTagsExtractor.Builder().build()
  //
  //    def json = """{
  //      "a": { "b": { "c": { "d": "e" } } }
  //    }"""
  //
  //    expect:
  //    extractTags(jsonTagsExtractor, json, "") == [
  //      ".a.b.c.d": "e",
  //    ]
  //  }
  //
  //  def "traverse nested mixed objects and arrays"() {
  //    JsonTagsExtractor jsonTagsExtractor = new JsonTagsExtractor.Builder().build()
  //
  //    def json = """{
  //      "a": [ "b", { "c": [ { "d": "e"} ] } ]
  //    }"""
  //
  //    expect:
  //    extractTags(jsonTagsExtractor, json, "") == [
  //      ".a.0"      : "b",
  //      ".a.1.c.0.d": "e",
  //    ]
  //  }
  //
  //  def "skip traversing nested objects and arrays"() {
  //    RedactionJsonPaths redactionPaths = new RedactionJsonPaths.Builder()
  //      .addRedactionJsonPaths(['$.a[1]', '$.a[2]'])
  //      .build()
  //
  //    JsonTagsExtractor jsonTagsExtractor = new JsonTagsExtractor.Builder()
  //      .redactionPaths(redactionPaths)
  //      .build()
  //
  //    def json = """{
  //      "a": [ "b", { "c": 1 }, [ 2, 3 ] ]
  //    }"""
  //
  //    expect:
  //    extractTags(jsonTagsExtractor, json, "") == [
  //      ".a.0": "b",
  //      ".a.1": "redacted",
  //      ".a.2": "redacted",
  //    ]
  //  }
  //
  //  def "limit number of tags including embedded json"() {
  //    JsonTagsExtractor jsonTagsExtractor = new JsonTagsExtractor.Builder()
  //      .tagsLimit(5)
  //      .build()
  //
  //    def json = """{
  //      "a": [1, 2],
  //      "b": { "foo": 3 },
  //      "c": '[10,20,30,40]',
  //      "d": 4,
  //      "e": 5,
  //      "f": 6,
  //      "g": 7,
  //      "h": 8,
  //      "i": 9
  //    }"""
  //
  //    expect:
  //    extractTags(jsonTagsExtractor, json, "") == [
  //      ".a.0"                       : "1",
  //      ".a.1"                       : "2",
  //      ".b.foo"                     : "3",
  //      ".c.0"                       : "10",
  //      ".c.1"                       : "20",
  //      "_dd.payload_tags_incomplete": true
  //    ]
  //  }
  //
  //  def "limit depth of traversal"() {
  //    JsonTagsExtractor jsonTagsExtractor = new JsonTagsExtractor.Builder()
  //      .depthLimit(3)
  //      .build()
  //
  //    def json = """{
  //      "a": {
  //        "b": '{ "c": "1", "d": { "e": "2" } }',
  //        "f": {
  //          "g": '{ "k": "3" }',
  //          "j": 4,
  //          "l": { "m": 5 }
  //        },
  //        "k": 6,
  //        "n": '[1,2,3]',
  //        "o": ['a', { "foo": "bar" }, ['c'], 'z']
  //      }
  //    }"""
  //
  //    expect:
  //    extractTags(jsonTagsExtractor, json, "") == [
  //      ".a.b.c": "1",
  //      ".a.f.j": "4",
  //      ".a.k"  : "6",
  //      ".a.n.0": "1",
  //      ".a.n.1": "2",
  //      ".a.n.2": "3",
  //      ".a.o.0": "a",
  //      ".a.o.3": "z",
  //    ]
  //  }
  //
  //  def "escape dots in property names"() {
  //    JsonTagsExtractor jsonTagsExtractor = new JsonTagsExtractor.Builder().build()
  //
  //    def json = """{
  //      "a.b": 1,
  //      "c.d": 2
  //    }"""
  //
  //    expect:
  //    extractTags(jsonTagsExtractor, json, "") == [
  //      ".a\\.b": "1",
  //      ".c\\.d": "2",
  //    ]
  //  }
  //
  //  def "prefix tags"() {
  //    JsonTagsExtractor jsonTagsExtractor = new JsonTagsExtractor.Builder()
  //      .build()
  //
  //    def json = """{
  //      "a": 1,
  //      "b": 2
  //    }"""
  //
  //    expect:
  //    extractTags(jsonTagsExtractor, json, "prefix") == [
  //      "prefix.a": "1",
  //      "prefix.b": "2",
  //    ]
  //  }
  //
  //  def "ignore missing redaction paths"() {
  //    RedactionJsonPaths redactionPaths = new RedactionJsonPaths.Builder()
  //      .addRedactionJsonPaths(['$.MessageAttributes.*.StringValue', '$.Message.password'])
  //      .build()
  //
  //    JsonTagsExtractor jsonTagsExtractor = new JsonTagsExtractor.Builder()
  //      .redactionPaths(redactionPaths)
  //      .build()
  //
  //    def json = """{
  //          "foo": "bar"
  //        }"""
  //
  //    expect:
  //    extractTags(jsonTagsExtractor, json, "") == [
  //      ".foo": "bar",
  //    ]
  //  }
  //
  //
  //  def "expand inner json object"() {
  //    JsonTagsExtractor jsonTagsExtractor = new JsonTagsExtractor.Builder().build()
  //
  //    String json = """{
  //          "a": 123,
  //          "Message": [ ${inner} ],
  //          "b": true
  //        }"""
  //
  //    Map map = extractTags(jsonTagsExtractor, json, "dd")
  //
  //    expect:
  //    map == ['dd.a': '123', 'dd.Message.0.a': '1.15', 'dd.Message.0.password': 'my-secret-password', 'dd.b': true]
  //
  //    where:
  //    inner << [
  //      "\"{ 'a': 1.15, 'password': 'my-secret-password' }\"",
  //      '"{ \\"a\\": 1.15, \\"password\\": \\"my-secret-password\\" }"',
  //      "'{ \"a\": 1.15, \"password\": \"my-secret-password\" }'",
  //      '''"{ \\"a\\": 1.15, \\"password\\": \\"my-secret-password\\" }"'''
  //    ]
  //  }
  //
  //  def "expand inner json array"() {
  //    JsonTagsExtractor jsonTagsExtractor = new JsonTagsExtractor.Builder().build()
  //
  //    String json = """{
  //          "a": 123,
  //          "Message": { "0": ${inner} },
  //          "b": true
  //        }"""
  //
  //    Map map = extractTags(jsonTagsExtractor, json, "dd")
  //
  //    expect:
  //    map == ['dd.a': '123', 'dd.Message.0.a': '1.15', 'dd.Message.0.password': 'my-secret-password', 'dd.b': true]
  //
  //    where:
  //    inner << [
  //      "\"{ 'a': 1.15, 'password': 'my-secret-password' }\"",
  //      '"{ \\"a\\": 1.15, \\"password\\": \\"my-secret-password\\" }"',
  //      "'{ \"a\": 1.15, \"password\": \"my-secret-password\" }'",
  //      '''"{ \\"a\\": 1.15, \\"password\\": \\"my-secret-password\\" }"'''
  //    ]
  //  }
  //
  //  def "expand inner serialized json"() {
  //    JsonTagsExtractor jsonTagsExtractor = new JsonTagsExtractor.Builder().build()
  //
  //    Buffer b1 = new Buffer()
  //    JsonWriter.of(b1)
  //      .beginObject()
  //      .name("a").value(1.15)
  //      .name("password").value("my-secret-password")
  //      .endObject()
  //      .close()
  //
  //    Buffer b2 = new Buffer()
  //    JsonWriter.of(b2)
  //      .beginObject()
  //      .name("a").value(123)
  //      .name("Message").value(b1.readUtf8())
  //      .name("b").value(true)
  //      .endObject()
  //      .close()
  //
  //    String json = b2.readUtf8()
  //
  //    Map map = extractTags(jsonTagsExtractor, json, "dd")
  //
  //    expect:
  //    map == ['dd.a': '123', 'dd.Message.a': '1.15', 'dd.Message.password': 'my-secret-password', 'dd.b': true]
  //  }
  //
  //  def "expand inner json within inner json"() {
  //    JsonTagsExtractor jsonTagsExtractor = new JsonTagsExtractor.Builder()
  //      .build()
  //
  //    Buffer b0 = new Buffer()
  //    JsonWriter.of(b0)
  //      .beginObject()
  //      .name("a").value(1.15)
  //      .name("password").value("my-secret-password")
  //      .endObject()
  //      .close()
  //
  //    Buffer b1 = new Buffer()
  //    JsonWriter.of(b1)
  //      .beginObject()
  //      .name("id").value(45)
  //      .name("user").value(b0.readUtf8())
  //      .endObject()
  //      .close()
  //
  //    Buffer b2 = new Buffer()
  //    JsonWriter.of(b2)
  //      .beginObject()
  //      .name("a").value(123)
  //      .name("Message").value(b1.readUtf8())
  //      .name("b").value(true)
  //      .endObject()
  //      .close()
  //
  //    String json = b2.readUtf8()
  //
  //    Map map = extractTags(jsonTagsExtractor, json, "dd")
  //
  //    expect:
  //    map == ['dd.a': '123', 'dd.Message.id': '45', 'dd.Message.user.a': '1.15', 'dd.Message.user.password': 'my-secret-password', 'dd.b': true]
  //  }
  //
  //  def "handle expansion parse errors"() {
  //    JsonTagsExtractor jsonTagsExtractor = new JsonTagsExtractor.Builder()
  //      .build()
  //
  //    def json = """{
  //          "Message": "${invalidInnerJson}"
  //        }"""
  //
  //    expect: "invalid json value collected as a string"
  //    extractTags(jsonTagsExtractor, json, "") == [
  //      ".Message": "${invalidInnerJson}",
  //    ]
  //
  //    where:
  //    invalidInnerJson                                 | desc
  //    "{ 'a: 1.15, 'password': 'my-secret-password' }" | "missing single quote after 'a"
  //    "112"                                            | "not an object or array"
  //    "true"                                           | "not an object or array"
  //    "{ 'a': 1.15"                                    | "incomplete json"
  //  }
  //
  //  def "skip expanding an inner json if can't parse it, keep already parsed, as well as un-parsed value"() {
  //    JsonTagsExtractor jsonTagsExtractor = new JsonTagsExtractor.Builder()
  //      .build()
  //
  //    String json = """{
  //          "a": 123,
  //          "Message": "$inner",
  //          "b": true
  //        }"""
  //
  //    expect:
  //    extractTags(jsonTagsExtractor, json, "dd") == ['dd.a': '123', 'dd.Message': inner, 'dd.b': true] + parsed
  //
  //    where:
  //    inner                                              | desc                               | parsed
  //    "{{ 'a': 1.15, 'password': 'my-secret-password' }" | "double opening curly braces"      | [:]
  //    "{ 'a': 1.15, 'password': 'my-secret-password' "   | "missing closing curly brace"      | [:]
  //    "{ 'a': 1.15, 'password': 'my-secret-password' }}" | "double curly braces in the end"   | ['dd.Message.a': '1.15', 'dd.Message.password': 'my-secret-password']
  //    "[ 'a': 1.15, 'password': 'my-secret-password' "   | "missing closing bracket"          | [:]
  //    "[[ 'a': 1.15, 'password': 'my-secret-password' "  | "double opening bracket"           | [:]
  //    "[ 'a': 1.15, 'password': 'my-secret-password' ]"  | "brackets instead of curly-braces" | ['dd.Message.0': 'a']
  //  }
  //
  //  def "parse and traverse escaped json"() {
  //    RedactionJsonPaths redactionPaths = new RedactionJsonPaths.Builder()
  //      .addRedactionJsonPaths(['$.password'])
  //      .build()
  //
  //    JsonTagsExtractor jsonTagsExtractor = new JsonTagsExtractor.Builder()
  //      .redactionPaths(redactionPaths)
  //      .build()
  //
  //    def json = "{ \"a\": 1.15, \"password\": \"my-secret-password\" }"
  //
  //    expect:
  //    extractTags(jsonTagsExtractor, json, "") == ['.a': '1.15', '.password': 'redacted']
  //  }
  //
  //
  //  def "skip invalid rules"() {
  //    def invalidRuleWithLeadingSpace = '$$.Message'
  //
  //    RedactionJsonPaths redactionPaths = new RedactionJsonPaths.Builder()
  //      .addRedactionJsonPaths([invalidRuleWithLeadingSpace])
  //      .build()
  //
  //    JsonTagsExtractor jsonTagsExtractor = new JsonTagsExtractor.Builder()
  //      .redactionPaths(redactionPaths)
  //      .build()
  //
  //    String inner = "{ 'a: 1.15, 'password': 'my-secret-password' }"
  //
  //    def json = """{
  //          "Message": "${inner}"
  //        }"""
  //
  //    expect: "Message attribute isn't redacted because of invalid rules"
  //    extractTags(jsonTagsExtractor, json, "") == [".Message": "{ 'a: 1.15, 'password': 'my-secret-password' }"]
  //  }
  //
  //  def "ignore invalid json, return an empty tag map"() {
  //    JsonTagsExtractor jsonTagsExtractor = new JsonTagsExtractor.Builder().build()
  //
  //    expect:
  //    extractTags(jsonTagsExtractor, invalidJson, "") == [:]
  //
  //    where:
  //    invalidJson << ["{", "}", "[", "]", "{ 'a: 1.15,", "null", "string", "1.0"]
  //  }

  //  Map<String, Object> extractTags(JsonTagsExtractor jsonTagsExtractor, String str, String tagPrefix) {
  //    try (InputStream is = new ByteArrayInputStream(str.getBytes())) {
  //      return jsonTagsExtractor.extractTags(is, tagPrefix)
  //    }
  //  }
}
