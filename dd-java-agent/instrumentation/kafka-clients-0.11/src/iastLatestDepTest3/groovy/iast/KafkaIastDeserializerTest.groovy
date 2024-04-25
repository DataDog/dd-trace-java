package iast

import com.fasterxml.jackson.core.JsonParser
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.Taintable
import datadog.trace.api.iast.propagation.CodecModule
import datadog.trace.api.iast.propagation.PropagationModule
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteBufferDeserializer
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.kafka.support.serializer.JsonDeserializer

import java.nio.ByteBuffer

import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED

class KafkaIastDeserializerTest extends AgentTestRunner {

  private static final int BUFF_OFFSET = 10

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test string deserializer: #test'() {
    given:
    final source = test.source
    final propagationModule = Mock(PropagationModule)
    final codecModule = Mock(CodecModule)
    [propagationModule, codecModule].each { InstrumentationBridge.registerIastModule(it) }

    and:
    final payload = "Hello World!".bytes
    final deserializer = new StringDeserializer()

    when:
    deserializer.configure([:], source == SourceTypes.KAFKA_MESSAGE_KEY)
    test.method.deserialize(deserializer, "test", payload)

    then:
    switch (test.method) {
      case Method.DEFAULT:
        1 * propagationModule.taintObject(payload, source) // taint byte[]
        1 * codecModule.onStringFromBytes(payload, 0, payload.length, _, _ as String) // taint byte[] => string
        break
      case Method.WITH_HEADERS:
        1 * propagationModule.taintObject(payload, source) // taint byte[]
        1 * codecModule.onStringFromBytes(payload, 0, payload.length, _, _ as String) // taint byte[] => string
        break
      case Method.WITH_BYTE_BUFFER:
        1 * propagationModule.taintObjectRange(_ as ByteBuffer, source, 0, payload.length) // taint ByteBuffer
        1 * propagationModule.taintObjectIfRangeTainted(payload, _ as ByteBuffer, 0, payload.length, false, NOT_MARKED) // taint ByteBuffer => byte[]
        1 * codecModule.onStringFromBytes(payload, 0, payload.length, _, _ as String) // taint byte[] => string
        break
      case Method.WITH_BYTE_BUFFER_OFFSET:
        1 * propagationModule.taintObjectRange(_ as ByteBuffer, source, BUFF_OFFSET, payload.length) // taint ByteBuffer
        1 * propagationModule.taintObjectIfTainted(_ as byte[], _ as ByteBuffer, true, NOT_MARKED) // taint ByteBuffer => byte[]
        1 * codecModule.onStringFromBytes(_ as byte[], BUFF_OFFSET, payload.length, _, _ as String) // taint byte[] => string
        break
    }
    0 * _

    where:
    test << testSuite()
  }

  void 'test byte array deserializer: #test'() {
    given:
    final source = test.source
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)

    and:
    final payload = "Hello World!".bytes
    final deserializer = new ByteArrayDeserializer()

    when:
    deserializer.configure([:], source == SourceTypes.KAFKA_MESSAGE_KEY)
    test.method.deserialize(deserializer, "test", payload)

    then:
    switch (test.method) {
      case Method.DEFAULT:
        1 * propagationModule.taintObject(payload, source) // taint byte[]
        break
      case Method.WITH_HEADERS:
        1 * propagationModule.taintObject(payload, source) // taint byte[]
        break
      case Method.WITH_BYTE_BUFFER:
        1 * propagationModule.taintObjectRange(_ as ByteBuffer, source, 0, payload.length) // taint ByteBuffer
        1 * propagationModule.taintObjectIfRangeTainted(payload, _ as ByteBuffer, 0, payload.length, false, NOT_MARKED) // taint ByteBuffer => byte[]
        break
      case Method.WITH_BYTE_BUFFER_OFFSET:
        1 * propagationModule.taintObjectRange(_ as ByteBuffer, source, BUFF_OFFSET, payload.length) // taint ByteBuffer
        1 * propagationModule.taintObjectIfRangeTainted(payload, _ as ByteBuffer, BUFF_OFFSET, payload.length, false, NOT_MARKED) // taint ByteBuffer => byte[]
        break
    }
    0 * _

    where:
    test << testSuite()
  }

  void 'test byte buffer deserializer: #test'() {
    given:
    final source = test.source
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)

    and:
    final payload = "Hello World!".bytes
    final deserializer = new ByteBufferDeserializer()

    when:
    deserializer.configure([:], source == SourceTypes.KAFKA_MESSAGE_KEY)
    test.method.deserialize(deserializer, "test", payload)

    then:
    switch (test.method) {
      case Method.DEFAULT:
        1 * propagationModule.taintObject(payload, source) // taint byte[]
        1 * propagationModule.taintObjectIfTainted(_ as ByteBuffer, payload, true, NOT_MARKED) // taint byte[] => ByteBuffer
        break
      case Method.WITH_HEADERS:
        1 * propagationModule.taintObject(payload, source) // taint byte[]
        1 * propagationModule.taintObjectIfTainted(_ as ByteBuffer, payload, true, NOT_MARKED) // taint byte[] => ByteBuffer
        break
      case Method.WITH_BYTE_BUFFER:
        1 * propagationModule.taintObjectRange(_ as ByteBuffer, source, 0, payload.length) // taint ByteBuffer
        break
      case Method.WITH_BYTE_BUFFER_OFFSET:
        1 * propagationModule.taintObjectRange(_ as ByteBuffer, source, BUFF_OFFSET, payload.length) // taint ByteBuffer
        break
    }
    0 * _

    where:
    test << testSuite()
  }

  void 'test json deserialization: #test'() {
    given:
    final source = test.source
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)

    and:
    final json = '{ "name": "Mr Bean" }'
    final payload = json.bytes
    final deserializer = new JsonDeserializer(TestBean)

    when:
    deserializer.configure([:], source == SourceTypes.KAFKA_MESSAGE_KEY)
    test.method.deserialize(deserializer, 'test', payload)

    then:
    switch (test.method) {
      case Method.DEFAULT:
        1 * propagationModule.taintObject(payload, source) // taint byte[]
        break
      case Method.WITH_HEADERS:
        1 * propagationModule.taintObject(payload, source) // taint byte[]
        break
      case Method.WITH_BYTE_BUFFER:
        1 * propagationModule.taintObjectRange(_ as ByteBuffer, source, 0, payload.length) // taint ByteBuffer
        1 * propagationModule.taintObjectIfRangeTainted(payload, _ as ByteBuffer, 0, payload.length, false, NOT_MARKED) // taint byte[] => ByteBuffer
        break
      case Method.WITH_BYTE_BUFFER_OFFSET:
        1 * propagationModule.taintObjectRange(_ as ByteBuffer, source, BUFF_OFFSET, payload.length) // taint ByteBuffer
        1 * propagationModule.taintObjectIfRangeTainted(payload, _ as ByteBuffer, BUFF_OFFSET, payload.length, false, NOT_MARKED) // taint byte[] => ByteBuffer
        break
    }
    // taint JSON
    1 * propagationModule.taintObjectIfTainted(_ as JsonParser, payload)
    1 * propagationModule.findSource(_) >> Stub(Taintable.Source) {
      getOrigin() >> source
      getValue() >> json
    }
    1 * propagationModule.taintString(_, 'name', source, 'name', json)
    1 * propagationModule.taintString(_, 'Mr Bean', source, 'name', json)
    0 * _

    where:
    test << testSuite()
  }

  private static List<Suite> testSuite() {
    return [SourceTypes.KAFKA_MESSAGE_KEY, SourceTypes.KAFKA_MESSAGE_VALUE].collectMany { source ->
      return [
        new Suite(source: source, method: Method.DEFAULT),
        new Suite(source: source, method: Method.WITH_HEADERS),
        new Suite(source: source, method: Method.WITH_BYTE_BUFFER),
        new Suite(source: source, method: Method.WITH_BYTE_BUFFER_OFFSET)
      ]
    }
  }

  enum Method {
    DEFAULT{
      @Override
      <T> T deserialize(Deserializer<T> deserializer, String topic, byte[] payload) {
        return deserializer.deserialize(topic, payload)
      }
    },
    WITH_HEADERS{
      @Override
      <T> T deserialize(Deserializer<T> deserializer, String topic, byte[] payload) {
        return deserializer.deserialize(topic, new RecordHeaders(), payload)
      }
    },
    WITH_BYTE_BUFFER{
      @SuppressWarnings('GroovyAssignabilityCheck')
      @Override
      <T> T deserialize(Deserializer<T> deserializer, String topic, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(payload.length)
        buffer.put(payload)
        buffer.position(0)
        return deserializer.deserialize(topic, new RecordHeaders(), buffer)
      }
    },
    WITH_BYTE_BUFFER_OFFSET{
      @SuppressWarnings('GroovyAssignabilityCheck')
      @Override
      <T> T deserialize(Deserializer<T> deserializer, String topic, byte[] payload) {
        final byte[] buffer = new byte[payload.length  + BUFF_OFFSET]
        System.arraycopy(payload, 0, buffer, BUFF_OFFSET, payload.length)
        return deserializer.deserialize(topic, new RecordHeaders(), ByteBuffer.wrap(buffer, BUFF_OFFSET, payload.length))
      }
    }

    abstract <T> T deserialize(Deserializer<T> deserializer, String topic, byte[] payload)
  }

  static class Suite {
    byte source
    Method method

    @Override
    String toString() {
      return "${method.name()}: ${SourceTypes.toString(source)}"
    }
  }

  static class TestBean {
    String name
  }
}
