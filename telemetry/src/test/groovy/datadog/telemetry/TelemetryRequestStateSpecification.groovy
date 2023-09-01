package datadog.telemetry

import datadog.telemetry.api.RequestType
import datadog.trace.api.ConfigOrigin
import datadog.trace.api.ConfigSetting
import okhttp3.HttpUrl
import okhttp3.Request
import okio.Buffer
import spock.lang.Specification

/**
 * This test only verifies non-functional specifics that are not covered in TelemetryServiceSpecification
 */
class TelemetryRequestStateSpecification extends Specification {
  final HttpUrl httpUrl = HttpUrl.get("https://example.com")

  def 'throw SerializationException in case of JSON nesting problem'() {
    setup:
    def b = new TelemetryRequestState(RequestType.APP_STARTED, httpUrl)

    when:
    b.beginRequest()
    b.beginRequest()

    then:
    TelemetryRequestState.SerializationException ex = thrown()
    ex.message == "Failed serializing Telemetry begin-request part!"
    ex.cause != null
  }

  def 'throw SerializationException in case of more than one top-level JSON value'() {
    setup:
    def b = new TelemetryRequestState(RequestType.APP_STARTED, httpUrl)

    when:
    b.beginRequest()
    b.endRequest()
    b.beginRequest()

    then:
    TelemetryRequestState.SerializationException ex = thrown()
    ex.message == "Failed serializing Telemetry begin-request part!"
    ex.cause != null
  }

  def 'writeConfig must support values of Boolean, String, Integer, Double, Map<String, Object>'() {
    setup:
    TelemetryRequestState rb = new TelemetryRequestState(RequestType.APP_CLIENT_CONFIGURATION_CHANGE, httpUrl)
    Map<String, Object> map = new HashMap<>()
    map.put("key1", "value1")
    map.put("key2", Double.parseDouble("432.32"))
    map.put("key3", 324)

    when:
    rb.beginRequest()
    // exclude request header to simplify assertion
    drainToString(rb.request())

    then:
    rb.beginConfiguration()
    [
      new ConfigSetting("string", "bar", ConfigOrigin.REMOTE),
      new ConfigSetting("int", 2342, ConfigOrigin.DEFAULT),
      new ConfigSetting("double", Double.valueOf("123.456"), ConfigOrigin.ENV),
      new ConfigSetting("map", map, ConfigOrigin.JVM_PROP)
    ].forEach { cc -> rb.writeConfiguration(cc) }
    rb.endConfiguration()

    then:
    drainToString(rb.request()) == ',"configuration":[{"name":"string","value":"bar","origin":"remote_config"},{"name":"int","value":2342,"origin":"default"},{"name":"double","value":123.456,"origin":"env_var"},{"name":"map","value":{"key1":"value1","key2":432.32,"key3":324},"origin":"jvm.prop"}]'
  }

  def 'add debug flag'() {
    setup:
    TelemetryRequestState rb = new TelemetryRequestState(RequestType.APP_STARTED, httpUrl, true)

    when:
    rb.beginRequest()
    def request = rb.endRequest()

    then:
    drainToString(request).contains("\"debug\":true")
  }

  String drainToString(Request req) {
    Buffer buf = new Buffer()
    req.body().writeTo(buf)
    byte[] bytes = new byte[buf.size()]
    buf.read(bytes)
    return new String(bytes)
  }
}
