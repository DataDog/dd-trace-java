package test.hazelcast.v39

import com.hazelcast.core.Message
import com.hazelcast.core.MessageListener
import com.hazelcast.query.Predicates
import datadog.trace.agent.test.checkpoints.CheckpointValidator
import datadog.trace.agent.test.checkpoints.CheckpointValidationMode
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import spock.util.concurrent.BlockingVariable

import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class HazelcastTest extends AbstractHazelcastTest {

  def "map"() {
    setup:
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)

    when:
    def serverMap = h1.getMap(randomName)
    serverMap.put("foo", "bar")

    def clientMap = client.getMap(randomName)
    def result = clientMap.get("foo")

    then:
    result == "bar"

    and: "operations are captured in traces"
    assertTraces(1) {
      hazelcastTrace(it, "Map.get $randomName")
    }
  }

  def "map predicate"() {
    setup:
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)

    when:
    def serverMap = h1.getMap(randomName)
    serverMap.put("foo", "bar")

    def clientMap = client.getMap(randomName)
    def result = clientMap.values(Predicates.equal("__key", "foo"))

    then:
    result as Set == ["bar"] as Set

    and: "operations are captured in traces"
    assertTraces(1) {
      hazelcastTrace(it, "Map.valuesWithPredicate $randomName")
    }
  }

  def "map async"() {
    setup:
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)

    when:
    def serverMap = h1.getMap(randomName)
    serverMap.put("foo", "bar")

    def clientMap = client.getMap(randomName)

    def result = runUnderTrace("test") {
      def future = clientMap.getAsync("foo")
      return future.get(5, TimeUnit.SECONDS)
    }

    then:
    result == "bar"

    and: "operations are captured in traces"
    assertTraces(1) {
      sortSpansByStart()
      trace(2) {
        basicSpan(it, "test")
        hazelcastSpan(it, "Map.get $randomName", false)
      }
    }
  }

  def "multimap"() {
    setup:
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)

    when:
    def serverMultiMap = h1.getMultiMap(randomName)
    serverMultiMap.put("foo", "bar")
    serverMultiMap.put("foo", "baz")

    def clientMultiMap = client.getMultiMap(randomName)
    def result = clientMultiMap.get("foo")

    then:
    result as Set == Arrays.asList("bar", "baz") as Set

    and: "operations are captured in traces"
    assertTraces(1) {
      hazelcastTrace(it, "MultiMap.get $randomName")
    }
  }

  def "queue"() {
    setup:
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)

    when:
    def serverQueue = h1.getQueue(randomName)
    serverQueue.offer("foo")

    def clientQueue = client.getQueue(randomName)
    def result = clientQueue.take()

    then:
    result == "foo"

    and: "operations are captured in traces"
    assertTraces(1) {
      hazelcastTrace(it, "Queue.take $randomName")
    }

    cleanup:
    serverQueue?.destroy()
  }

  def "topic"() {
    given:
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)

    def serverTopic = h1.getTopic(randomName)

    and:
    def clientTopic = client.getTopic(randomName)
    def receivedMessage = new BlockingVariable<Message>(5, TimeUnit.SECONDS)
    def listener = Stub(MessageListener)
    listener.onMessage(_ as Message) >> { Message<String> message -> receivedMessage.set(message) }

    clientTopic.addMessageListener(listener)

    when:
    clientTopic.publish("hello")

    then:
    with(receivedMessage.get()) { Message<String> message ->
      message.messageObject == "hello"
    }

    and: "operations are captured in traces"
    assertTraces(2) {
      hazelcastTrace(it, "Topic.addMessageListener")
      hazelcastTrace(it, "Topic.publish $randomName")
    }

    cleanup:
    serverTopic?.destroy()
    clientTopic?.destroy()
  }

  def "reliable topic"() {
    given:
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)

    def clientTopic = client.getReliableTopic(randomName)
    def receivedMessage = new BlockingVariable<Message>(5, TimeUnit.SECONDS)
    def listener = Stub(MessageListener)
    listener.onMessage(_ as Message) >> { Message<String> message -> receivedMessage.set(message) }

    clientTopic.addMessageListener(listener)

    when:
    clientTopic.publish("hello")

    then:
    with(receivedMessage.get()) { Message<String> message ->
      message.messageObject == "hello"
    }

    and: "operations are captured in traces"
    // warning: operations and order may vary significantly across version
    assertTraces(4) {
      hazelcastTrace(it, "Ringbuffer.tailSequence _hz_rb_$randomName")
      hazelcastTrace(it, "Ringbuffer.capacity _hz_rb_$randomName")
      hazelcastTrace(it, "Ringbuffer.readMany _hz_rb_$randomName")
      hazelcastTrace(it, "Ringbuffer.add _hz_rb_$randomName")
    }
  }

  def "set"() {
    setup:
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)

    when:
    def serverSet = h1.getSet(randomName)
    serverSet.add("foo")

    def clientSet = client.getSet(randomName)
    def result = clientSet.contains("foo")

    then:
    result

    and: "operations are captured in traces"
    assertTraces(1) {
      hazelcastTrace(it, "Set.contains $randomName")
    }
  }

  def "set double value"() {
    setup:
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)

    when:
    def clientSet = client.getSet(randomName)
    clientSet.add("hello")
    def result = clientSet.add("hello")

    then:
    !result

    and: "operations are captured in traces"
    assertTraces(2) {
      hazelcastTrace(it, "Set.add $randomName")
      hazelcastTrace(it, "Set.add $randomName")
    }

  }

  def "list"() {
    setup:
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)

    when:
    def serverList = h1.getList(randomName)
    serverList.add("foo")

    def clientList = client.getList(randomName)
    def result = clientList.contains("foo")

    then:
    result

    and: "operations are captured in traces"
    assertTraces(1) {
      hazelcastTrace(it, "List.contains $randomName")
    }
  }

  def "list error"() {
    setup:
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)

    when:
    def serverList = h1.getList(randomName)
    serverList.add("foo")

    def clientList = client.getList(randomName)
    clientList.get(2)

    then:
    def ex = thrown(IndexOutOfBoundsException)
    assertTraces(1) {
      trace(1) {
        span {
          serviceName "hazelcast-sdk"
          operationName "hazelcast.invoke"
          resourceName "List.get $randomName"
          parent()
          spanType DDSpanTypes.HTTP_CLIENT
          errored true
          tags {
            "$Tags.COMPONENT" "hazelcast-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "hazelcast.service" "List"
            "hazelcast.name" randomName
            "hazelcast.operation" "List.get"
            "hazelcast.instance" client.getName()
            "hazelcast.correlationId" Long
            errorTags(ex)
            defaultTags()
          }
        }
      }
    }
  }

  def "lock"() {
    setup:
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)

    when:
    def serverLock = h1.getLock(randomName)
    serverLock.lock()

    def clientLock = client.getLock(randomName)
    def alreadyLocked = clientLock.isLocked()

    then:
    assert alreadyLocked

    and: "operations are captured in traces"
    assertTraces(1) {
      hazelcastTrace(it, "Lock.isLocked $randomName")
    }

    cleanup:
    serverLock?.destroy()
  }
}
