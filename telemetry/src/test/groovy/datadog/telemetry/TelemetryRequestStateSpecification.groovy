package datadog.telemetry

import datadog.telemetry.api.ConfigChange
import datadog.telemetry.api.RequestType
import okhttp3.HttpUrl
import okhttp3.Request
import okio.Buffer
import spock.lang.Specification

/**
 * This test only verifies non-functional specifics that are not covered in TelemetryServiceSpecification
 */
class TelemetryRequestStateSpecification extends Specification {
  final HttpUrl url = HttpUrl.get("https://example.com")

  def 'throw SerializationException in case of JSON nesting problem'() {
    setup:
    def req = new TelemetryRequestState(RequestType.APP_STARTED)

    when:
    req.beginRequest(false)
    req.beginRequest(false)

    then:
    TelemetryRequestState.SerializationException ex = thrown()
    ex.message == "Failed serializing Telemetry begin-request part!"
    ex.cause != null
  }

  def 'throw SerializationException in case of more than one top-level JSON value'() {
    setup:
    def req = new TelemetryRequestState(RequestType.APP_STARTED)

    when:
    req.beginRequest(false)
    req.endRequest(url)
    req.beginRequest(false)

    then:
    TelemetryRequestState.SerializationException ex = thrown()
    ex.message == "Failed serializing Telemetry begin-request part!"
    ex.cause != null
  }

  def 'writeConfig must support values of Boolean, String, Integer, Double, Map<String, Object>'() {
    setup:
    TelemetryRequestState req = new TelemetryRequestState(RequestType.APP_CLIENT_CONFIGURATION_CHANGE)
    Map<String, Object> map = new HashMap<>()
    map.put("key1", "value1")
    map.put("key2", Double.parseDouble("432.32"))
    map.put("key3", 324)

    when:
    req.beginRequest(false)
    // exclude request header to simplify assertion
    drainToString(req.request(url))

    then:
    req.beginConfiguration()
    [
      new ConfigChange("string", "bar"),
      new ConfigChange("int", 2342),
      new ConfigChange("double", Double.valueOf("123.456")),
      new ConfigChange("map", map)
    ].forEach { cc -> req.writeConfiguration(cc) }
    req.endConfiguration()

    then:
    drainToString(req.endRequest(url)) == ',"configuration":[{"name":"string","value":"bar","origin":"unknown"},{"name":"int","value":2342,"origin":"unknown"},{"name":"double","value":123.456,"origin":"unknown"},{"name":"map","value":{"key1":"value1","key2":432.32,"key3":324},"origin":"unknown"}]}'
  }

  def 'add debug flag'() {
    setup:
    TelemetryRequestState req = new TelemetryRequestState(RequestType.APP_STARTED)

    when:
    req.beginRequest(true)
    def request = req.endRequest(url)

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
