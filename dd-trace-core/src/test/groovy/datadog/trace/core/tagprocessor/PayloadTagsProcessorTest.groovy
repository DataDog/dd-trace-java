package datadog.trace.core.tagprocessor

import datadog.trace.payloadtags.PathCursor
import datadog.trace.payloadtags.PayloadTagsData
import datadog.trace.test.util.DDSpecification
import datadog.trace.api.Config

class PayloadTagsProcessorTest extends DDSpecification {

  PathCursor pc() {
    new PathCursor(10)
  }

  def "test when enabled"() {
    setup:
    injectSysConfig("trace.cloud.request.payload.tagging", "all")
    injectSysConfig("trace.cloud.response.payload.tagging", "all")

    when:
    PayloadTagsData requestData = new PayloadTagsData()
      .append(pc().push("foo").attachValue("bar"))
      .append(pc().push("bar").push(0).attachValue("{ 'a': 1.15, 'password': 'my-secret-password' }"))
      .append(pc().push("baz").push("Value"))

    PayloadTagsData responseData = new PayloadTagsData()
      .append(pc().push("foo").attachValue("bar"))

    Map<String, Object> tags = [
      "aws.request.body" : requestData,
      "aws.response.body": responseData,
    ]

    PayloadTagsProcessor.create(Config.get())
      .processTags(tags, null)

    then:
    tags == [
      "aws.request.body.foo"           : "bar",
      "aws.request.body.bar.0.a"       : "1.15",
      "aws.request.body.bar.0.password": "my-secret-password",
      "aws.request.body.baz.Value"     : "null",
      "aws.response.body.foo"          : "bar"
    ]
  }

  def "test some default redaction rules"() {
    setup:
    injectSysConfig("trace.cloud.request.payload.tagging", "all")
    injectSysConfig("trace.cloud.response.payload.tagging", "all")

    when:
    PayloadTagsData requestData = new PayloadTagsData()
      .append(pc().push("phoneNumber").attachValue("+15555555555"))

    PayloadTagsData responseData = new PayloadTagsData()
      .append(pc().push("phoneNumbers").push(0).attachValue("+15555555555"))

    Map<String, Object> tags = [
      "aws.request.body" : requestData,
      "aws.response.body": responseData,
    ]

    PayloadTagsProcessor.create(Config.get())
      .processTags(tags, null)

    then:
    tags == [
      "aws.request.body.phoneNumber"    : "redacted",
      "aws.response.body.phoneNumbers.0": "redacted"
    ]
  }

  def "traverse primitive values"() {
    setup:
    injectSysConfig("trace.cloud.request.payload.tagging", "all")
    injectSysConfig("trace.cloud.response.payload.tagging", "all")

    when:
    PayloadTagsData requestData = new PayloadTagsData()
      .append(pc().push("a").attachValue(1))
      .append(pc().push("b").attachValue(2.0f))
      .append(pc().push("c").attachValue("string"))
      .append(pc().push("d").attachValue(true))
      .append(pc().push("e").attachValue(false))
      .append(pc().push("f").attachValue(null))


    def tags = ["aws.request.body": requestData]

    PayloadTagsProcessor.create(Config.get())
      .processTags(tags, null)

    then:
    tags == [
      "aws.request.body.a": 1,
      "aws.request.body.b": 2.0f,
      "aws.request.body.c": "string",
      "aws.request.body.d": true,
      "aws.request.body.e": false,
      "aws.request.body.f": "null",
    ]
  }

  //    def "traverse inner json primitive values"() {
  //      JsonTagsExtractor jsonTagsExtractor = new JsonTagsExtractor.Builder().build()
  //
  //      def json = """{
  //        "a": 1,
  //        "b": 2.0,
  //        "c": "string",
  //        "d": true,
  //        "e": false,
  //        "f": null
  //      }"""
  //
  //      expect:
  //      extractTags(jsonTagsExtractor, json, "") == [
  //        ".a": "1",
  //        ".b": "2.0",
  //        ".c": "string",
  //        ".d": true,
  //        ".e": false,
  //        ".f": "null"
  //      ]
  //    }

  //TODO test other types, e.g. InputStream

}
