package iast

import com.datadog.iast.propagation.PropagationModuleImpl
import com.datadog.iast.test.IastRequestTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteBufferDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.kafka.support.serializer.JsonDeserializer

import java.nio.ByteBuffer

import static org.hamcrest.CoreMatchers.equalTo
import static org.hamcrest.CoreMatchers.instanceOf

import spock.lang.Ignore

class KafkaIastDeserializerForkedTest extends IastRequestTestRunner {

  void 'test string deserializer'() {
    given:
    final propagationModule = new PropagationModuleImpl()
    InstrumentationBridge.registerIastModule(propagationModule)

    and:
    final payload = "Hello World!".bytes
    final deserializer = new StringDeserializer()

    when:
    runUnderIastTrace {
      deserializer.configure([:], origin == SourceTypes.KAFKA_MESSAGE_KEY)
      deserializer.deserialize("test", payload)
    }

    then:
    final to = finReqTaintedObjects
    to.hasTaintedObject {
      value('Hello World!')
      range(0, 12, source(origin as byte))
    }

    where:
    origin                          | _
    SourceTypes.KAFKA_MESSAGE_KEY   | _
    SourceTypes.KAFKA_MESSAGE_VALUE | _
  }

  void 'test byte array deserializer'() {
    given:
    final propagationModule = new PropagationModuleImpl()
    InstrumentationBridge.registerIastModule(propagationModule)

    and:
    final payload = "Hello World!".bytes
    final deserializer = new ByteArrayDeserializer()

    when:
    runUnderIastTrace {
      deserializer.configure([:], origin == SourceTypes.KAFKA_MESSAGE_KEY)
      deserializer.deserialize("test", payload)
    }

    then:
    final to = finReqTaintedObjects
    to.hasTaintedObject {
      value(equalTo(payload))
      range(0, Integer.MAX_VALUE, source(origin as byte))
    }

    where:
    origin                          | _
    SourceTypes.KAFKA_MESSAGE_KEY   | _
    SourceTypes.KAFKA_MESSAGE_VALUE | _
  }

  void 'test byte buffer deserializer'() {
    given:
    final propagationModule = new PropagationModuleImpl()
    InstrumentationBridge.registerIastModule(propagationModule)

    and:
    final payload = "Hello World!".bytes
    final deserializer = new ByteBufferDeserializer()

    when:
    runUnderIastTrace {
      deserializer.configure([:], origin == SourceTypes.KAFKA_MESSAGE_KEY)
      deserializer.deserialize("test", payload)
    }

    then:
    final to = finReqTaintedObjects
    to.hasTaintedObject {
      value(instanceOf(ByteBuffer))
      range(0, Integer.MAX_VALUE, source(origin as byte))
    }

    where:
    origin                          | _
    SourceTypes.KAFKA_MESSAGE_KEY   | _
    SourceTypes.KAFKA_MESSAGE_VALUE | _
  }

  @Ignore("Not working under Groovy 4")
  void 'test json deserialization'() {
    given:
    final propagationModule = new PropagationModuleImpl()
    InstrumentationBridge.registerIastModule(propagationModule)

    and:
    final json = '{ "name": "Mr Bean" }'
    final payload = json.bytes
    final deserializer = new JsonDeserializer(TestBean)

    when:
    runUnderIastTrace {
      deserializer.configure([:], origin == SourceTypes.KAFKA_MESSAGE_KEY)
      deserializer.deserialize('test', payload)
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
    origin                          | _
    SourceTypes.KAFKA_MESSAGE_KEY   | _
    SourceTypes.KAFKA_MESSAGE_VALUE | _
  }

  static class TestBean {
    String name
  }
}
