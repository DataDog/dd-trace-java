package com.datadog.appsec.config

import spock.lang.Specification

class AppSecSCAConfigDeserializerTest extends Specification {

  def "deserializes valid JSON byte array"() {
    given:
    def json = '''
      {
        "enabled": true,
        "instrumentation_targets": [
          {
            "class_name": "org/springframework/web/client/RestTemplate",
            "method_name": "execute"
          }
        ]
      }
    '''
    def bytes = json.bytes

    when:
    def config = AppSecSCAConfigDeserializer.INSTANCE.deserialize(bytes)

    then:
    config != null
    config.enabled == true
    config.instrumentationTargets.size() == 1
    config.instrumentationTargets[0].className == "org/springframework/web/client/RestTemplate"
    config.instrumentationTargets[0].methodName == "execute"
  }

  def "returns null for null content"() {
    when:
    def config = AppSecSCAConfigDeserializer.INSTANCE.deserialize(null)

    then:
    config == null
  }

  def "returns null for empty byte array"() {
    when:
    def config = AppSecSCAConfigDeserializer.INSTANCE.deserialize(new byte[0])

    then:
    config == null
  }

  def "deserializes minimal configuration"() {
    given:
    def json = '{"enabled": false}'
    def bytes = json.bytes

    when:
    def config = AppSecSCAConfigDeserializer.INSTANCE.deserialize(bytes)

    then:
    config != null
    config.enabled == false
    config.instrumentationTargets == null
  }

  def "handles multiple instrumentation targets"() {
    given:
    def json = '''
      {
        "enabled": true,
        "instrumentation_targets": [
          {
            "class_name": "com/example/Class1",
            "method_name": "method1"
          },
          {
            "class_name": "com/example/Class2",
            "method_name": "method2"
          },
          {
            "class_name": "com/example/Class3",
            "method_name": "method3"
          }
        ]
      }
    '''
    def bytes = json.bytes

    when:
    def config = AppSecSCAConfigDeserializer.INSTANCE.deserialize(bytes)

    then:
    config != null
    config.enabled == true
    config.instrumentationTargets.size() == 3

    config.instrumentationTargets[0].className == "com/example/Class1"
    config.instrumentationTargets[0].methodName == "method1"

    config.instrumentationTargets[1].className == "com/example/Class2"
    config.instrumentationTargets[1].methodName == "method2"

    config.instrumentationTargets[2].className == "com/example/Class3"
    config.instrumentationTargets[2].methodName == "method3"
  }

  def "INSTANCE is a singleton"() {
    expect:
    AppSecSCAConfigDeserializer.INSTANCE === AppSecSCAConfigDeserializer.INSTANCE
  }
}
