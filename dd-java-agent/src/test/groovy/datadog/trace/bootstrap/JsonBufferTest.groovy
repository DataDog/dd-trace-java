package datadog.trace.agent

import datadog.trace.bootstrap.JsonBuffer
import spock.lang.Specification

class JsonBufferTest extends Specification {
  def "object"() {
    when:
    def jsonBuffer = new JsonBuffer()
    jsonBuffer.beginObject()
    jsonBuffer.name("foo").value("bar")
    jsonBuffer.name("pi").value(3_142)
    jsonBuffer.name("true").value(true)
    jsonBuffer.name("false").value(false)
    jsonBuffer.endObject()

    then:
    jsonBuffer.toString() == '{"foo":"bar","pi":3142,"true":true,"false":false}'
  }

  def "array"() {
    when:
    def jsonBuffer = new JsonBuffer()
    jsonBuffer.beginArray()
    jsonBuffer.value("foo")
    jsonBuffer.value("baz")
    jsonBuffer.value("bar")
    jsonBuffer.value("quux")
    jsonBuffer.endArray()

    then:
    jsonBuffer.toString() == '["foo","baz","bar","quux"]'
  }

  def "escaping"() {
    when:
    def jsonBuffer = new JsonBuffer()
    jsonBuffer.beginArray()
    jsonBuffer.value('"')
    jsonBuffer.value("\\")
    jsonBuffer.value("/")
    jsonBuffer.value("\b")
    jsonBuffer.value("\f")
    jsonBuffer.value("\n")
    jsonBuffer.value("\r")
    jsonBuffer.value("\t")
    jsonBuffer.endArray()

    then:
    jsonBuffer.toString() == '["\\"","\\\\","\\/","\\b","\\f","\\n","\\r","\\t"]'
  }

  def "nesting array in object"() {
    when:
    def jsonBuffer = new JsonBuffer()
    jsonBuffer.beginObject()
    jsonBuffer.name("array")
    jsonBuffer.beginArray()
    jsonBuffer.value("true")
    jsonBuffer.value("false")
    jsonBuffer.endArray()
    jsonBuffer.endObject()

    then:
    jsonBuffer.toString() == '{"array":["true","false"]}'
  }

  def "nesting object in array"() {
    when:
    def jsonBuffer = new JsonBuffer()
    jsonBuffer.beginArray()
    jsonBuffer.beginObject()
    jsonBuffer.name("true").value(true)
    jsonBuffer.endObject()
    jsonBuffer.beginObject()
    jsonBuffer.name("false").value(false)
    jsonBuffer.endObject()
    jsonBuffer.endArray()

    then:
    jsonBuffer.toString() == '[{"true":true},{"false":false}]'
  }

  def "partial object buffer"() {
    when:
    def partialJsonBuffer = new JsonBuffer()
    partialJsonBuffer.name("foo").value("bar")
    partialJsonBuffer.name("quux").value("baz")

    def jsonBuffer = new JsonBuffer()
    jsonBuffer.beginObject()
    jsonBuffer.name("partial").object(partialJsonBuffer)
    jsonBuffer.endObject()

    then:
    jsonBuffer.toString() == '{"partial":{"foo":"bar","quux":"baz"}}'
  }

  def "partial array buffer"() {
    when:
    def partialJsonBuffer = new JsonBuffer()
    partialJsonBuffer.value("foo")
    partialJsonBuffer.value("bar")

    def jsonBuffer = new JsonBuffer()
    jsonBuffer.beginObject()
    jsonBuffer.name("partial").array(partialJsonBuffer)
    jsonBuffer.endObject()

    then:
    jsonBuffer.toString() == '{"partial":["foo","bar"]}'
  }

  def "reset"() {
    when:
    def jsonBuffer = new JsonBuffer()
    jsonBuffer.name("foo").value("quux")

    jsonBuffer.reset()

    jsonBuffer.array("bar", "baz")

    then:
    jsonBuffer.toString() == '["bar","baz"]'
  }
}
