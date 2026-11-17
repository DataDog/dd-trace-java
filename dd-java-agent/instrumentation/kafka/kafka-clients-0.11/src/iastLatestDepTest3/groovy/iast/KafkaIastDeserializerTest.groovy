package iast

import com.datadog.iast.propagation.PropagationModuleImpl
import com.datadog.iast.test.IastRequestTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteBufferDeserializer
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.kafka.support.serializer.JsonDeserializer

import java.nio.ByteBuffer

import static org.hamcrest.CoreMatchers.instanceOf
import static org.hamcrest.core.IsEqual.equalTo

class KafkaIastDeserializerTest extends IastRequestTestRunner {

  private static final String PAYLOAD_STRING = "Hello World!"
  private static final int PAYLOAD_LENGTH = PAYLOAD_STRING.bytes.length

  private static final int BUFF_OFFSET = 10

  void 'test string deserializer: #test'() {
    given:
    final origin = test.origin
    final propagationModule = new PropagationModuleImpl()
    InstrumentationBridge.registerIastModule(propagationModule)

    and:
    final payload = PAYLOAD_STRING.bytes
    final deserializer = new StringDeserializer()

    when:
    runUnderIastTrace {
      deserializer.configure([:], origin == SourceTypes.KAFKA_MESSAGE_KEY)
      test.method.deserialize(deserializer, "test", payload)
    }

    then:
    final to = finReqTaintedObjects
    to.hasTaintedObject {
      value(PAYLOAD_STRING)
      range(0, PAYLOAD_LENGTH, source(origin))
    }

    where:
    test << testSuite()
  }

  void 'test byte array deserializer: #test'() {
    given:
    final origin = test.origin
    final propagationModule = new PropagationModuleImpl()
    InstrumentationBridge.registerIastModule(propagationModule)

    and:
    final payload = PAYLOAD_STRING.bytes
    final deserializer = new ByteArrayDeserializer()

    when:
    runUnderIastTrace {
      deserializer.configure([:], origin == SourceTypes.KAFKA_MESSAGE_KEY)
      test.method.deserialize(deserializer, "test", payload)
    }

    then:
    final to = finReqTaintedObjects
    to.hasTaintedObject {
      value(equalTo(payload))
      range(0, Integer.MAX_VALUE, source(origin))
    }

    where:
    test << testSuite()
  }

  void 'test byte buffer deserializer: #test'() {
    given:
    final origin = test.origin
    final propagationModule = new PropagationModuleImpl()
    InstrumentationBridge.registerIastModule(propagationModule)

    and:
    final payload = PAYLOAD_STRING.bytes
    final deserializer = new ByteBufferDeserializer()

    when:
    runUnderIastTrace {
      deserializer.configure([:], origin == SourceTypes.KAFKA_MESSAGE_KEY)
      test.method.deserialize(deserializer, "test", payload)
    }

    then:
    final to = finReqTaintedObjects
    to.hasTaintedObject {
      value(instanceOf(ByteBuffer))
      range(test.method.start, test.method.length, source(origin))
    }

    where:
    test << testSuite()
  }

  void 'test json deserialization: #test'() {
    given:
    final origin = test.origin
    final propagationModule = new PropagationModuleImpl()
    InstrumentationBridge.registerIastModule(propagationModule)

    and:
    final json = '{ "name": "Mr Bean" }'
    final payload = json.bytes
    final deserializer = new JsonDeserializer(TestBean)

    when:
    runUnderIastTrace {
      deserializer.configure([:], origin == SourceTypes.KAFKA_MESSAGE_KEY)
      test.method.deserialize(deserializer, 'test', payload)
    }

    then:
    final to = finReqTaintedObjects
    to.hasTaintedObject {
      value(instanceOf(TestBean))
      range(0, Integer.MAX_VALUE, source(origin as byte))
    }
    to.hasTaintedObject {
      value('Mr Bean')
      range(0, 7, source(origin as byte, 'name', 'Mr Bean'))
    }

    where:
    test << testSuite()
  }

  private static List<Suite> testSuite() {
    return [SourceTypes.KAFKA_MESSAGE_KEY, SourceTypes.KAFKA_MESSAGE_VALUE].collectMany { origin ->
      return [
        new Suite(origin: origin, method: Method.DEFAULT),
        new Suite(origin: origin, method: Method.WITH_HEADERS),
        new Suite(origin: origin, method: Method.WITH_BYTE_BUFFER),
        new Suite(origin: origin, method: Method.WITH_BYTE_BUFFER_OFFSET)
      ]
    }
  }

  enum Method {
    DEFAULT(0, Integer.MAX_VALUE){
      @Override
      <T> T deserialize(Deserializer<T> deserializer, String topic, byte[] payload) {
        return deserializer.deserialize(topic, payload)
      }
    },
    WITH_HEADERS(0, Integer.MAX_VALUE){
      @Override
      <T> T deserialize(Deserializer<T> deserializer, String topic, byte[] payload) {
        return deserializer.deserialize(topic, new RecordHeaders(), payload)
      }
    },
    WITH_BYTE_BUFFER(0, PAYLOAD_LENGTH){
      @SuppressWarnings('GroovyAssignabilityCheck')
      @Override
      <T> T deserialize(Deserializer<T> deserializer, String topic, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(payload.length)
        buffer.put(payload)
        buffer.position(0)
        return deserializer.deserialize(topic, new RecordHeaders(), buffer)
      }
    },
    WITH_BYTE_BUFFER_OFFSET(BUFF_OFFSET, PAYLOAD_LENGTH){
      @SuppressWarnings('GroovyAssignabilityCheck')
      @Override
      <T> T deserialize(Deserializer<T> deserializer, String topic, byte[] payload) {
        final byte[] buffer = new byte[payload.length  + BUFF_OFFSET]
        System.arraycopy(payload, 0, buffer, BUFF_OFFSET, payload.length)
        return deserializer.deserialize(topic, new RecordHeaders(), ByteBuffer.wrap(buffer, BUFF_OFFSET, payload.length))
      }
    }

    // start/length of the taint range expected on the ByteBuffer result of deserialization;
    // a pass-through deserializer keeps the precise range tainted beforehand, otherwise a
    // brand-new ByteBuffer is wrapped around the result and gets a fresh, full-object range
    final int start
    final int length

    Method(int start, int length) {
      this.start = start
      this.length = length
    }

    abstract <T> T deserialize(Deserializer<T> deserializer, String topic, byte[] payload)
  }

  static class Suite {
    byte origin
    Method method

    @Override
    String toString() {
      return "${method.name()}: ${SourceTypes.toString(origin)}"
    }
  }

  static class TestBean {
    String name
  }
}
