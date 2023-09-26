package datadog.telemetry

import datadog.telemetry.api.ConfigChange
import datadog.telemetry.api.RequestType
import okhttp3.RequestBody
import okio.Buffer
import spock.lang.Specification

/**
 * This test only verifies non-functional specifics that are not covered in TelemetryServiceSpecification
 */
class TelemetryRequestBodySpecification extends Specification {

  def 'throw SerializationException in case of JSON nesting problem'() {
    setup:
    def req = new TelemetryRequestBody(RequestType.APP_STARTED)

    when:
    req.beginRequest(false)
    req.beginRequest(false)

    then:
    TelemetryRequestBody.SerializationException ex = thrown()
    ex.message == "Failed serializing Telemetry begin-request part!"
    ex.cause != null
  }

  def 'throw SerializationException in case of more than one top-level JSON value'() {
    setup:
    def req = new TelemetryRequestBody(RequestType.APP_STARTED)

    when:
    req.beginRequest(false)
    req.endRequest()
    req.beginRequest(false)

    then:
    TelemetryRequestBody.SerializationException ex = thrown()
    ex.message == "Failed serializing Telemetry begin-request part!"
    ex.cause != null
  }

  def 'writeConfig must support values of Boolean, String, Integer, Double, Map<String, Object>'() {
    setup:
    TelemetryRequestBody req = new TelemetryRequestBody(RequestType.APP_CLIENT_CONFIGURATION_CHANGE)
    Map<String, Object> map = new HashMap<>()
    map.put("key1", "value1")
    map.put("key2", Double.parseDouble("432.32"))
    map.put("key3", 324)

    when:
    req.beginRequest(false)
    // exclude request header to simplify assertion
    drainToString(req)

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
    drainToString(req) == ',"configuration":[{"name":"string","value":"bar","origin":"unknown"},{"name":"int","value":2342,"origin":"unknown"},{"name":"double","value":123.456,"origin":"unknown"},{"name":"map","value":{"key1":"value1","key2":432.32,"key3":324},"origin":"unknown"}]'
  }

  def 'add debug flag'() {
    setup:
    TelemetryRequestBody req = new TelemetryRequestBody(RequestType.APP_STARTED)

    when:
    req.beginRequest(true)
    req.endRequest()

    then:
    drainToString(req).contains("\"debug\":true")
  }

  String drainToString(RequestBody body) {
    Buffer buf = new Buffer()
    body.writeTo(buf)
    byte[] bytes = new byte[buf.size()]
    buf.read(bytes)
    return new String(bytes)
  }
}
