package com.datadog.appsec.config

import com.squareup.moshi.Moshi
import spock.lang.Specification

class AppSecSCAConfigTest extends Specification {

  def "deserializes valid SCA config with instrumentation targets"() {
    given:
    def json = '''
      {
        "enabled": true,
        "instrumentation_targets": [
          {
            "class_name": "org/springframework/web/client/RestTemplate",
            "method_name": "execute"
          },
          {
            "class_name": "com/fasterxml/jackson/databind/ObjectMapper",
            "method_name": "readValue"
          }
        ]
      }
    '''

    when:
    def adapter = new Moshi.Builder().build().adapter(AppSecSCAConfig)
    def config = adapter.fromJson(json)

    then:
    config != null
    config.enabled == true
    config.instrumentationTargets != null
    config.instrumentationTargets.size() == 2

    config.instrumentationTargets[0].className == "org/springframework/web/client/RestTemplate"
    config.instrumentationTargets[0].methodName == "execute"

    config.instrumentationTargets[1].className == "com/fasterxml/jackson/databind/ObjectMapper"
    config.instrumentationTargets[1].methodName == "readValue"
  }

  def "deserializes SCA config with enabled false"() {
    given:
    def json = '''
      {
        "enabled": false,
        "instrumentation_targets": []
      }
    '''

    when:
    def adapter = new Moshi.Builder().build().adapter(AppSecSCAConfig)
    def config = adapter.fromJson(json)

    then:
    config != null
    config.enabled == false
    config.instrumentationTargets != null
    config.instrumentationTargets.isEmpty()
  }

  def "deserializes minimal SCA config"() {
    given:
    def json = '''
      {
        "enabled": true
      }
    '''

    when:
    def adapter = new Moshi.Builder().build().adapter(AppSecSCAConfig)
    def config = adapter.fromJson(json)

    then:
    config != null
    config.enabled == true
    config.instrumentationTargets == null
  }

  def "handles empty JSON object"() {
    given:
    def json = '{}'

    when:
    def adapter = new Moshi.Builder().build().adapter(AppSecSCAConfig)
    def config = adapter.fromJson(json)

    then:
    config != null
    config.enabled == null
    config.instrumentationTargets == null
  }

  def "deserializes InstrumentationTarget correctly"() {
    given:
    def json = '''
      {
        "class_name": "java/io/File",
        "method_name": "<init>"
      }
    '''

    when:
    def adapter = new Moshi.Builder().build().adapter(AppSecSCAConfig.InstrumentationTarget)
    def target = adapter.fromJson(json)

    then:
    target != null
    target.className == "java/io/File"
    target.methodName == "<init>"
  }
}