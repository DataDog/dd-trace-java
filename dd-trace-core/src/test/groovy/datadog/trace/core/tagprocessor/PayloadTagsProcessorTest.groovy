package datadog.trace.core.tagprocessor

import datadog.trace.payloadtags.PathCursor
import datadog.trace.payloadtags.PayloadTagsData
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

  def "test when enabled"() {
    setup:
    injectSysConfig("trace.cloud.request.payload.tagging", "all")
    injectSysConfig("trace.cloud.response.payload.tagging", "all")

    when:
    PayloadTagsData requestData = new PayloadTagsData()
      .append(pc().push("foo").withValue("bar"))
      .append(pc().push("bar").push(0).withValue("{ 'a': 1.15, 'password': 'my-secret-password' }"))
      .append(pc().push("baz").push("Value"))

    PayloadTagsData responseData = new PayloadTagsData()
      .append(pc().push("foo").withValue("bar"))

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
      .append(pc().push("phoneNumber").withValue("+15555555555"))

    PayloadTagsData responseData = new PayloadTagsData()
      .append(pc().push("phoneNumbers").push(0).withValue("+15555555555"))

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

  def "test some custom redaction rules"() {
    setup:
    injectSysConfig("trace.cloud.request.payload.tagging", "\$.customField")
    injectSysConfig("trace.cloud.response.payload.tagging", "\$.foo.bar[1]")

    when:
    PayloadTagsData requestData = new PayloadTagsData()
      .append(pc().push("customField").withValue("custom-field-value"))
      .append(pc().push("customField2").withValue("custom-field-value"))

    PayloadTagsData responseData = new PayloadTagsData()
      .append(pc().push("foo").push("bar").push(0).withValue("foobar"))
      .append(pc().push("foo").push("bar").push(1).withValue("foobar2"))

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
      .append(pc().push("a").withValue(1))
      .append(pc().push("b").withValue(2.0f))
      .append(pc().push("c").withValue("string"))
      .append(pc().push("d").withValue(true))
      .append(pc().push("e").withValue(false))
      .append(pc().push("f").withValue(null))


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
      "aws.request.body.f": "null", // collect a null as a string to be a valid tag value
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
