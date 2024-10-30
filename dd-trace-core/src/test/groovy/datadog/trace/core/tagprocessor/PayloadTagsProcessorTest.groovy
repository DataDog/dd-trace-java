package datadog.trace.core.tagprocessor

import datadog.trace.payloadtags.PayloadTagsData
import datadog.trace.payloadtags.json.PathCursor
import datadog.trace.test.util.DDSpecification
import datadog.trace.api.Config

class PayloadTagsProcessorTest extends DDSpecification {

  PathCursor pc() {
    new PathCursor(10)
  }

  def "disabled by default"() {
    expect:
    !PayloadTagsProcessor.create(Config.get())
  }

  def "enabled with default limits"() {
    setup:
    injectSysConfig("trace.cloud.request.payload.tagging", "all")
    injectSysConfig("trace.cloud.response.payload.tagging", "all")

    when:
    def ptp = PayloadTagsProcessor.create(Config.get())

    then:
    ptp != null
    ptp.depthLimit == 10
    ptp.maxTags == 758
  }

  def "enabled with custom limits"() {
    setup:
    injectSysConfig("trace.cloud.request.payload.tagging", "\$")
    injectSysConfig("trace.cloud.response.payload.tagging", "\$")
    injectSysConfig("trace.cloud.payload.tagging.max-depth", "7")
    injectSysConfig("trace.cloud.payload.tagging.max-tags", "42")

    when:
    def ptp = PayloadTagsProcessor.create(Config.get())

    then:
    ptp.depthLimit == 7
    ptp.maxTags == 42
  }

  //  def "test when enabled"() {
  //    setup:
  //    injectSysConfig("trace.cloud.request.payload.tagging", "all")
  //    injectSysConfig("trace.cloud.response.payload.tagging", "all")
  //
  //    when:
  //    PayloadTagsData requestData = new PayloadTagsData()
  //      .add(pc().push("foo").toPath(), "bar")
  //      .add(pc().push("bar").push(0).toPath(), "{ 'a': 1.15, 'password': 'my-secret-password' }")
  //      .add(pc().push("baz").push("Value").toPath(), null)
  //
  //    PayloadTagsData responseData = new PayloadTagsData()
  //      .add(pc().push("foo").toPath(), "bar")
  //
  //    Map<String, Object> tags = [
  //      "aws.request.body" : requestData,
  //      "aws.response.body": responseData,
  //    ]
  //
  //    PayloadTagsProcessor.create(Config.get())
  //      .processTags(tags, null)
  //
  //    then:
  //    tags == [
  //      "aws.request.body.foo"           : "bar",
  //      "aws.request.body.bar.0.a"       : 1.15d,
  //      "aws.request.body.bar.0.password": "my-secret-password",
  //      "aws.request.body.baz.Value"     : null,
  //      "aws.response.body.foo"          : "bar"
  //    ]
  //  }

  def "test with  default redaction rules"() {
    setup:
    injectSysConfig("trace.cloud.request.payload.tagging", "all")
    injectSysConfig("trace.cloud.response.payload.tagging", "all")

    when:
    PayloadTagsData requestData = new PayloadTagsData()
      .add(pc().push("phoneNumber").toPath(), "+15555555555")

    PayloadTagsData responseData = new PayloadTagsData()
      .add(pc().push("phoneNumbers").push(0).toPath(), "+15555555555")

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

  def "test with custom redaction rules"() {
    setup:
    injectSysConfig("trace.cloud.request.payload.tagging", "\$.customField")
    injectSysConfig("trace.cloud.response.payload.tagging", "\$.foo.bar[1]")

    when:
    PayloadTagsData requestData = new PayloadTagsData()
      .add(pc().push("customField").toPath(), "custom-field-value")
      .add(pc().push("customField2").toPath(), "custom-field-value")

    PayloadTagsData responseData = new PayloadTagsData()
      .add(pc().push("foo").push("bar").push(0).toPath(), "foobar")
      .add(pc().push("foo").push("bar").push(1).toPath(), "foobar2")

    Map<String, Object> tags = [
      "aws.request.body" : requestData,
      "aws.response.body": responseData,
    ]

    PayloadTagsProcessor.create(Config.get())
      .processTags(tags, null)

    then:
    tags == [
      "aws.request.body.customField" : "redacted",
      "aws.request.body.customField2": "custom-field-value",
      "aws.response.body.foo.bar.0"  : "foobar",
      "aws.response.body.foo.bar.1"  : "redacted"
    ]
  }

  def "collect primitive values"() {
    setup:
    injectSysConfig("trace.cloud.request.payload.tagging", "all")
    injectSysConfig("trace.cloud.response.payload.tagging", "all")

    when:
    PayloadTagsData requestData = new PayloadTagsData()
      .add(pc().push("a").toPath(), 1)
      .add(pc().push("b").toPath(), 2.0f)
      .add(pc().push("c").toPath(), "string")
      .add(pc().push("d").toPath(), true)
      .add(pc().push("e").toPath(), false)
      .add(pc().push("f").toPath(), null)
      .add(pc().push("g").toPath(), Integer.MAX_VALUE.toLong())
      .add(pc().push("h").toPath(), Integer.MAX_VALUE.toLong() + 1)


    def tags = ["aws.request.body": requestData]

    PayloadTagsProcessor.create(Config.get())
      .processTags(tags, null)

    then:
    tags == [
      "aws.request.body.a": 1,
      "aws.request.body.b": 2.0d,
      "aws.request.body.c": "string",
      "aws.request.body.d": true,
      "aws.request.body.e": false,
      "aws.request.body.f": null,
      "aws.request.body.g": Integer.MAX_VALUE,
      "aws.request.body.h": Integer.MAX_VALUE.toLong() + 1,
    ]
  }

  //    def "parse inner json primitive values"() {
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
