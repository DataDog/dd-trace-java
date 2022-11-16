package test.hazelcast.v3

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
    setup:

    when:
    def serverMap = h1.getMap("test")
    serverMap.put("foo", "bar")

    def clientMap = client.getMap("test")
    def result = clientMap.get("foo")

    then:
    result == "bar"

    assertTraces(1) {
      hazelcastTrace(it, "map[test].get")
    }
  }

  def "map predicate"() {
    setup:

    when:
    def serverMap = h1.getMap("test")
    serverMap.put("foo", "bar")

    def clientMap = client.getMap("test")
    def result = clientMap.values(Predicates.equal("__key", "foo"))

    then:
    result as Set == ["bar"] as Set

    assertTraces(1) {
      hazelcastTrace(it, "map[test].values")
    }
  }

  def "map async"() {
    setup:

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

    assertTraces(1) {
      sortSpansByStart()
      trace(2) {
        basicSpan(it, "test")
        hazelcastSpan(it, "map[test].getAsync", false)
      }
    }
  }

  def "multimap"() {
    setup:

    when:
    def serverMultiMap = h1.getMultiMap("test")
    serverMultiMap.put("foo", "bar")
    serverMultiMap.put("foo", "baz")

    def clientMultiMap = client.getMultiMap("test")
    def result = clientMultiMap.get("foo")

    then:
    result as Set == Arrays.asList("bar", "baz") as Set

    and: "traces reflect the operations"
    assertTraces(1) {
      hazelcastTrace(it, "multiMap[test].get")
    }
  }

  def "queue"() {
    setup:

    when:
    def serverQueue = h1.getQueue("test")
    serverQueue.offer("foo")

    def clientQueue = client.getQueue("test")
    def result = clientQueue.take()

    then:
    result == "foo"

    and: "traces reflect the operations"
    assertTraces(1) {
      hazelcastTrace(it, "queue[test].take")
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
    listener.onMessage(_ as Message) >> { Message<String> message -> receivedMessage.set(message) }

    clientTopic.addMessageListener(listener)

    when:
    clientTopic.publish("hello")

    then:
    with(receivedMessage.get()) { Message<String> message ->
      message.messageObject == "hello"
    }

    and: "traces reflect the operations"
    assertTraces(1) {
      hazelcastTrace(it, "topic[test].publish")
    }

    cleanup:
    serverTopic?.destroy()
    clientTopic?.destroy()
  }

  def "set"() {
    setup:

    when:
    def serverSet = h1.getSet("test")
    serverSet.add("foo")

    def clientSet = client.getSet("test")
    def result = clientSet.contains("foo")

    then:
    result

    and: "traces reflect the operations"
    assertTraces(1) {
      hazelcastTrace(it, "set[test].contains")
    }
  }

  def "list"() {
    setup:

    when:
    def serverList = h1.getList("test")
    serverList.add("foo")

    def clientList = client.getList("test")
    def result = clientList.contains("foo")

    then:
    result

    and: "traces reflect the operations"
    assertTraces(1) {
      hazelcastTrace(it, "list[test].contains")
    }
  }

  def "lock"() {
    setup:

    when:
    def serverLock = h1.getLock("test")
    serverLock.lock()

    def clientLock = client.getLock("test")
    def alreadyLocked = clientLock.isLocked()

    then:
    assert alreadyLocked

    and: "traces reflect the operations"
    assertTraces(1) {
      hazelcastTrace(it, "lock[test].isLocked")
    }

    cleanup:
    serverLock?.destroy()
  }

  def "list error"() {
    setup:

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
          resourceName "list[${randomName}].get"
          parent()
          spanType DDSpanTypes.HTTP_CLIENT
          errored true
          tags {
            "$Tags.COMPONENT" "hazelcast-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "hazelcast.service" "hz:impl:listService"
            "hazelcast.name" "list[${randomName}]"
            "hazelcast.operation" "get"
            "hazelcast.instance" client.getName()
            errorTags(ex)
            defaultTags()
          }
        }
      }
    }
  }
}
