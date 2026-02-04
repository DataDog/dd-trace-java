package datadog.telemetry


import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import datadog.telemetry.api.RequestType
import datadog.trace.api.ConfigOrigin
import datadog.trace.api.ConfigSetting
import datadog.trace.api.ProcessTags
import datadog.trace.api.telemetry.ProductChange
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.config.GeneralConfig.EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED
import static datadog.trace.api.telemetry.ProductChange.ProductType.APPSEC
import static datadog.trace.api.telemetry.ProductChange.ProductType.DYNAMIC_INSTRUMENTATION
import static datadog.trace.api.telemetry.ProductChange.ProductType.PROFILER

/**
 * This test only verifies non-functional specifics that are not covered in TelemetryServiceSpecification
 */
class TelemetryRequestBodySpecification extends DDSpecification {

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

  def 'writeConfig must support values of Boolean, String, Number, and null'() {
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
      ConfigSetting.of("string", "bar", ConfigOrigin.REMOTE),
      ConfigSetting.of("int", 2342, ConfigOrigin.DEFAULT),
      ConfigSetting.of("double", Double.valueOf("123.456"), ConfigOrigin.ENV),
      ConfigSetting.of("map", map, ConfigOrigin.JVM_PROP),
      ConfigSetting.of("list", Arrays.asList("1", "2", 3), ConfigOrigin.DEFAULT),
      // make sure null values are serialized
      ConfigSetting.of("null", null, ConfigOrigin.DEFAULT)
    ].forEach { cc -> req.writeConfiguration(cc) }
    req.endConfiguration()

    then:
    drainToString(req) == ',"configuration":[' +
      '{"name":"string","value":"bar","origin":"remote_config","seq_id":0},' +
      '{"name":"int","value":"2342","origin":"default","seq_id":0},' +
      '{"name":"double","value":"123.456","origin":"env_var","seq_id":0},' +
      '{"name":"map","value":"key1:value1,key2:432.32,key3:324","origin":"jvm_prop","seq_id":0},' +
      '{"name":"list","value":"1,2,3","origin":"default","seq_id":0},' +
      '{"name":"null","value":null,"origin":"default","seq_id":0}]'
  }

  def 'use snake_case for setting keys'() {
    setup:
    TelemetryRequestBody req = new TelemetryRequestBody(RequestType.APP_CLIENT_CONFIGURATION_CHANGE)

    when:
    req.beginRequest(false)
    // exclude request header to simplify assertion
    drainToString(req)

    then:
    req.beginConfiguration()
    req.writeConfiguration(ConfigSetting.of("this.is.a.key", "value", ConfigOrigin.REMOTE))
    req.endConfiguration()

    then:
    drainToString(req) == ',"configuration":[{"name":"this_is_a_key","value":"value","origin":"remote_config","seq_id":0}]'
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

  void 'test writeProducts'() {
    setup:
    TelemetryRequestBody req = new TelemetryRequestBody(RequestType.APP_PRODUCT_CHANGE)
    final products = new HashMap<ProductChange.ProductType, Boolean>()
    if (appsecChange) {
      products.put(APPSEC, appsecEnabled)
    }
    if (profilerChange) {
      products.put(PROFILER, profilerEnabled)
    }
    if (dynamicInstrumentationChange) {
      products.put(DYNAMIC_INSTRUMENTATION, dynamicInstrumentationEnabled)
    }

    when:
    req.beginRequest(false)
    req.writeProducts(products)
    req.endRequest()

    then:
    final result = drainToString(req)
    result.contains("\"appsec\":{\"enabled\":${appsecEnabled}}") == appsecChange
    result.contains("\"profiler\":{\"enabled\":${profilerEnabled}}") == profilerChange
    result.contains("\"dynamic_instrumentation\":{\"enabled\":${dynamicInstrumentationEnabled}}") == dynamicInstrumentationChange

    where:
    appsecChange | profilerChange | dynamicInstrumentationChange | appsecEnabled | profilerEnabled | dynamicInstrumentationEnabled
    true         | true           | true                         | true          | true            | true
    true         | true           | true                         | false         | false           | false
    false        | false          | false                        | true          | true            | true
    false        | true           | true                         | true          | true            | true
    true         | false          | true                         | true          | true            | true
    true         | true           | false                        | true          | true            | true
  }

  String drainToString(TelemetryRequestBody body) {
    return new String(body.toByteArray())
  }

  def 'Should propagate process tags when enabled #processTagsEnabled'() {
    setup:
    injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "$processTagsEnabled")
    ProcessTags.reset()
    TelemetryRequestBody req = new TelemetryRequestBody(RequestType.APP_STARTED)

    when:
    req.beginRequest(true)
    req.endRequest()

    then:
    def type = Types.newParameterizedType(Map, String, Object)
    def adapter = new Moshi.Builder().build().adapter(type)
    def parsed = (Map<String, Object>)adapter.fromJson(drainToString(req))
    def parsedTags = ((Map<String, Object>)parsed.get("application")).get("process_tags")
    if (processTagsEnabled) {
      assert parsedTags == ProcessTags.tagsForSerialization.toString()
    } else {
      assert parsedTags == null
    }

    cleanup:
    injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "false")
    ProcessTags.reset()

    where:
    processTagsEnabled << [true, false]
  }
}
