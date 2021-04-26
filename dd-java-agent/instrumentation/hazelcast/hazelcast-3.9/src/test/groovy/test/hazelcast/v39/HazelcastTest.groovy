package test.hazelcast.v39

import com.hazelcast.core.Message
import com.hazelcast.core.MessageListener
import com.hazelcast.query.Predicates
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import spock.util.concurrent.BlockingVariable

import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class HazelcastTest extends AbstractHazelcastTest {

  def "map"() {
    when:
    def serverMap = h1.getMap("test")
    serverMap.put("foo", "bar")

    def clientMap = client.getMap("test")
    def result = clientMap.get("foo")

    then:
    result == "bar"

    and: "operations are captured in traces"
    assertTraces(1) {
      hazelcastTrace(it, "Map.get test")
    }
  }

  def "map predicate"() {
    when:
    def serverMap = h1.getMap("test")
    serverMap.put("foo", "bar")

    def clientMap = client.getMap("test")
    def result = clientMap.values(Predicates.equal("__key", "foo"))

    then:
    result as Set == ["bar"] as Set

    and: "operations are captured in traces"
    assertTraces(1) {
      hazelcastTrace(it, "Map.valuesWithPredicate test")
    }
  }

  def "map async"() {
    when:
    def serverMap = h1.getMap("test")
    serverMap.put("foo", "bar")

    def clientMap = client.getMap("test")

    def result = runUnderTrace("test") {
      def future = clientMap.getAsync("foo")
      return future.get(5, TimeUnit.SECONDS)
    }

    then:
    result == "bar"

    and: "operations are captured in traces"
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "test")
        hazelcastSpan(it, "Map.get test", false)
      }
    }
  }

  def "multimap"() {
    when:
    def serverMultiMap = h1.getMultiMap("test")
    serverMultiMap.put("foo", "bar")
    serverMultiMap.put("foo", "baz")

    def clientMultiMap = client.getMultiMap("test")
    def result = clientMultiMap.get("foo")

    then:
    result as Set == Arrays.asList("bar", "baz") as Set

    and: "operations are captured in traces"
    assertTraces(1) {
      hazelcastTrace(it, "MultiMap.get test")
    }
  }

  def "queue"() {
    when:
    def serverQueue = h1.getQueue("test")
    serverQueue.offer("foo")

    def clientQueue = client.getQueue("test")
    def result = clientQueue.take()

    then:
    result == "foo"

    and: "operations are captured in traces"
    assertTraces(1) {
      hazelcastTrace(it, "Queue.take test")
    }

    cleanup:
    serverQueue?.destroy()
  }


  def "topic"() {
    given:
    def serverTopic = h1.getTopic("test")

    and:
    def clientTopic = client.getTopic("test")
    def receivedMessage = new BlockingVariable<Message>(5, TimeUnit.SECONDS)
    def listener = Stub(MessageListener)
    listener.onMessage(_ as Message) >> { Message<String> message -> receivedMessage.set(message)}

    clientTopic.addMessageListener(listener)

    when:
    clientTopic.publish("hello")

    then:
    with (receivedMessage.get()) { Message<String> message ->
      message.messageObject == "hello"
    }

    and: "operations are captured in traces"
    assertTraces(2) {
      hazelcastTrace(it, "Topic.addMessageListener")
      hazelcastTrace(it, "Topic.publish test")
    }

    cleanup:
    serverTopic?.destroy()
    clientTopic?.destroy()
  }

  def "reliable topic"() {
    given:
    def clientTopic = client.getReliableTopic("test")
    def receivedMessage = new BlockingVariable<Message>(5, TimeUnit.SECONDS)
    def listener = Stub(MessageListener)
    listener.onMessage(_ as Message) >> { Message<String> message -> receivedMessage.set(message)}

    clientTopic.addMessageListener(listener)

    when:
    clientTopic.publish("hello")

    then:
    with (receivedMessage.get()) { Message<String> message ->
      message.messageObject == "hello"
    }

    and: "operations are captured in traces"
    // warning: operations and order may vary significantly across version
    assertTraces(4) {
      hazelcastTrace(it, "Ringbuffer.tailSequence _hz_rb_test")
      hazelcastTrace(it, "Ringbuffer.capacity _hz_rb_test")
      hazelcastTrace(it, "Ringbuffer.readMany _hz_rb_test")
      hazelcastTrace(it, "Ringbuffer.add _hz_rb_test")
    }
  }

  def "set"() {
    when:
    def serverSet = h1.getSet("test")
    serverSet.add("foo")

    def clientSet = client.getSet("test")
    def result = clientSet.contains("foo")

    then:
    result

    and: "operations are captured in traces"
    assertTraces(1) {
      hazelcastTrace(it, "Set.contains test")
    }
  }

  def "set double value"() {
    when:
    def clientSet = client.getSet("test1")
    clientSet.add("hello")
    def result = clientSet.add("hello")

    then:
    !result

    and: "operations are captured in traces"
    assertTraces(2) {
      hazelcastTrace(it, "Set.add test1")
      hazelcastTrace(it, "Set.add test1")
    }

  }

  def "list"() {
    when:
    def serverList = h1.getList("test")
    serverList.add("foo")

    def clientList = client.getList("test")
    def result = clientList.contains("foo")

    then:
    result

    and: "operations are captured in traces"
    assertTraces(1) {
      hazelcastTrace(it, "List.contains test")
    }
  }

  def "list error"() {
    when:
    def serverList = h1.getList("test")
    serverList.add("foo")

    def clientList = client.getList("test")
    clientList.get(2)

    then:
    def ex = thrown(IndexOutOfBoundsException)
    assertTraces(1) {
      trace(1) {
        span {
          serviceName "hazelcast-sdk"
          operationName "hazelcast.sdk"
          resourceName "List.get test"
          parent()
          spanType DDSpanTypes.HTTP_CLIENT
          errored true
          tags {
            "$Tags.COMPONENT" "hazelcast-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "hazelcast.service" "List"
            "hazelcast.name" "test"
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
    when:
    def serverLock = h1.getLock("test")
    serverLock.lock()

    def clientLock = client.getLock("test")
    def alreadyLocked = clientLock.isLocked()

    then:
    assert alreadyLocked

    and: "operations are captured in traces"
    assertTraces(1) {
      hazelcastTrace(it, "Lock.isLocked test")
    }

    cleanup:
    serverLock?.destroy()
  }
}
