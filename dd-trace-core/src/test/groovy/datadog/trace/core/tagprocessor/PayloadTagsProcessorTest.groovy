package datadog.trace.core.tagprocessor

import datadog.trace.payloadtags.PathCursor
import datadog.trace.payloadtags.PayloadPathData
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
    PayloadPathData requestData = new PayloadPathData()
      .append(pc().push("foo").attachValue("bar"))
      .append(pc().push("bar").push(0).attachValue("{ 'a': 1.15, 'password': 'my-secret-password' }"))
      .append(pc().push("baz").push("Value"))

    PayloadPathData responseData = new PayloadPathData()
      .append(pc().push("foo").attachValue("bar"))

    Map<String, Object> tags = [
      "aws.request.body": requestData,
      "aws.response.body": responseData,
    ]

    new PayloadTagsProcessor(Config.get())
      .processTags(tags, null)

    then:
    tags == [
      "aws.request.body.foo": "bar",
      "aws.request.body.bar.0.a": "1.15",
      "aws.request.body.bar.0.password": "my-secret-password",
      "aws.request.body.baz.Value": "null",
      "aws.response.body.foo": "bar"
    ]
  }

  def "test some default redaction rules"() {
    setup:
    injectSysConfig("trace.cloud.request.payload.tagging", "all")
    injectSysConfig("trace.cloud.response.payload.tagging", "all")

    when:
    PayloadPathData requestData = new PayloadPathData()
      .append(pc().push("phoneNumber").attachValue("+15555555555"))

    PayloadPathData responseData = new PayloadPathData()
      .append(pc().push("phoneNumbers").push(0).attachValue("+15555555555"))

    Map<String, Object> tags = [
      "aws.request.body": requestData,
      "aws.response.body": responseData,
    ]

    new PayloadTagsProcessor(Config.get())
      .processTags(tags, null)

    then:
    tags == [
      "aws.request.body.phoneNumber": "redacted",
      "aws.response.body.phoneNumbers.0": "redacted"
    ]
  }

  //TODO test other types, e.g. InputStream, embedded json, etc.

}
