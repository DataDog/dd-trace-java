package datadog.trace.agent

import datadog.trace.bootstrap.JsonBuffer
import spock.lang.Specification

class JsonBufferTest extends Specification {
  def "object"() {
    when:
    def jsonBuffer = new JsonBuffer()
    jsonBuffer.beginObject()
    jsonBuffer.name("foo").value("bar")
    jsonBuffer.name("pi").value(3_142);
    jsonBuffer.name("true").value(true);
    jsonBuffer.name("false").value(false);
    jsonBuffer.endObject();

    then:
    jsonBuffer.toString() == '{"foo":"bar","pi":3142,"true":true,"false":false}'
  }
  
  def "array"() {
    when:
    def jsonBuffer = new JsonBuffer()
    jsonBuffer.beginArray()
    jsonBuffer.value("foo");
    jsonBuffer.value("baz");
    jsonBuffer.value("bar");
    jsonBuffer.value("quux");
    jsonBuffer.endArray();

    then:
    jsonBuffer.toString() == '["foo","baz","bar","quux"]'
  }
  
  def "escaping"() {
    when:
    def jsonBuffer = new JsonBuffer()
    jsonBuffer.beginArray();
    jsonBuffer.value('"');
    jsonBuffer.value("\\");
    jsonBuffer.value("/");
    jsonBuffer.value("\b");
    jsonBuffer.value("\f");
    jsonBuffer.value("\n");
    jsonBuffer.value("\r");
    jsonBuffer.value("\t");
    jsonBuffer.endArray();
    
    then:
    jsonBuffer.toString() == '["\\"","\\\\","\\/","\\b","\\f","\\n","\\r","\\t"]'
  }
}
