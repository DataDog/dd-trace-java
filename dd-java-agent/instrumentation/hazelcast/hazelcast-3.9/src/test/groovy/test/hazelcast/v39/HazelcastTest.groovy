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

    assertTraces(1) {
      trace(1) {
        span {
          serviceName "hazelcast-sdk"
          operationName "hazelcast.sdk"
          resourceName "Map.get test"
          parent()
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          tags {
            "$Tags.COMPONENT" "hazelcast-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "hazelcast.name" "test"
            "hazelcast.operation" "Map.get"
            "hazelcast.service" "Map"
            "hazelcast.instance" client.getName()
            defaultTags()
          }
        }
      }
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

    assertTraces(1) {
      trace(1) {
        span {
          serviceName "hazelcast-sdk"
          operationName "hazelcast.sdk"
          resourceName "Map.valuesWithPredicate test"
          parent()
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          tags {
            "$Tags.COMPONENT" "hazelcast-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "hazelcast.name" "test"
            "hazelcast.operation" "Map.valuesWithPredicate"
            "hazelcast.service" "Map"
            "hazelcast.instance" client.getName()
            defaultTags()
          }
        }
      }
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

    assertTraces(1) {
      trace(2) {
        basicSpan(it, "test")
        span {
          serviceName "hazelcast-sdk"
          operationName "hazelcast.sdk"
          resourceName "Map.get test"
          childOfPrevious()
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          tags {
            "$Tags.COMPONENT" "hazelcast-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "hazelcast.name" "test"
            "hazelcast.operation" "Map.get"
            "hazelcast.service" "Map"
            "hazelcast.instance" client.getName()
            defaultTags()
          }
        }
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

    assertTraces(1) {
      trace(1) {
        span {
          serviceName "hazelcast-sdk"
          operationName "hazelcast.sdk"
          resourceName "MultiMap.get test"
          parent()
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          tags {
            "$Tags.COMPONENT" "hazelcast-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "hazelcast.name" "test"
            "hazelcast.operation" "MultiMap.get"
            "hazelcast.service" "MultiMap"
            "hazelcast.instance" client.getName()
            defaultTags()
          }
        }
      }
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

    assertTraces(1) {
      trace(1) {
        span {
          serviceName "hazelcast-sdk"
          operationName "hazelcast.sdk"
          resourceName "Queue.take test"
          parent()
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          tags {
            "$Tags.COMPONENT" "hazelcast-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "hazelcast.name" "test"
            "hazelcast.operation" "Queue.take"
            "hazelcast.service" "Queue"
            "hazelcast.instance" client.getName()
            defaultTags()
          }
        }
      }
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

    assertTraces(2) {
      trace(1) {
        span {
          serviceName "hazelcast-sdk"
          operationName "hazelcast.sdk"
          resourceName "Topic.addMessageListener"
          parent()
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          tags {
            "$Tags.COMPONENT" "hazelcast-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "hazelcast.operation" "Topic.addMessageListener"
            "hazelcast.service" "Topic"
            "hazelcast.instance" client.getName()
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName "hazelcast-sdk"
          operationName "hazelcast.sdk"
          resourceName "Topic.publish test"
          parent()
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          tags {
            "$Tags.COMPONENT" "hazelcast-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "hazelcast.name" "test"
            "hazelcast.operation" "Topic.publish"
            "hazelcast.service" "Topic"
            "hazelcast.instance" client.getName()
            defaultTags()
          }
        }
      }
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

    assertTraces(4) {
      trace(1) {
        span {
          serviceName "hazelcast-sdk"
          operationName "hazelcast.sdk"
          resourceName "Ringbuffer.tailSequence _hz_rb_test"
          parent()
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          tags {
            "$Tags.COMPONENT" "hazelcast-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "hazelcast.service" "Ringbuffer"
            "hazelcast.name" "_hz_rb_test"
            "hazelcast.operation" "Ringbuffer.tailSequence"
            "hazelcast.instance" client.getName()
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName "hazelcast-sdk"
          operationName "hazelcast.sdk"
          resourceName "Ringbuffer.capacity _hz_rb_test"
          parent()
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          tags {
            "$Tags.COMPONENT" "hazelcast-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "hazelcast.service" "Ringbuffer"
            "hazelcast.name" "_hz_rb_test"
            "hazelcast.operation" "Ringbuffer.capacity"
            "hazelcast.instance" client.getName()
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName "hazelcast-sdk"
          operationName "hazelcast.sdk"
          resourceName "Ringbuffer.readMany _hz_rb_test"
          parent()
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          tags {
            "$Tags.COMPONENT" "hazelcast-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "hazelcast.service" "Ringbuffer"
            "hazelcast.name" "_hz_rb_test"
            "hazelcast.operation" "Ringbuffer.readMany"
            "hazelcast.instance" client.getName()
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName "hazelcast-sdk"
          operationName "hazelcast.sdk"
          resourceName "Ringbuffer.add _hz_rb_test"
          parent()
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          tags {
            "$Tags.COMPONENT" "hazelcast-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "hazelcast.service" "Ringbuffer"
            "hazelcast.name" "_hz_rb_test"
            "hazelcast.operation" "Ringbuffer.add"
            "hazelcast.instance" client.getName()
            defaultTags()
          }
        }
      }
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
    assertTraces(1) {
      trace(1) {
        span {
          serviceName "hazelcast-sdk"
          operationName "hazelcast.sdk"
          resourceName "Set.contains test"
          parent()
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          tags {
            "$Tags.COMPONENT" "hazelcast-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "hazelcast.service" "Set"
            "hazelcast.name" "test"
            "hazelcast.operation" "Set.contains"
            "hazelcast.instance" client.getName()
            defaultTags()
          }
        }
      }
    }
  }

  def "set double value"() {
    when:
    def clientSet = client.getSet("test1")
    clientSet.add("hello")
    def result = clientSet.add("hello")

    then:
    !result
    assertTraces(2) {
      trace(1) {
        span {
          serviceName "hazelcast-sdk"
          operationName "hazelcast.sdk"
          resourceName "Set.add test1"
          parent()
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          tags {
            "$Tags.COMPONENT" "hazelcast-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "hazelcast.service" "Set"
            "hazelcast.name" "test1"
            "hazelcast.operation" "Set.add"
            "hazelcast.instance" client.getName()
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName "hazelcast-sdk"
          operationName "hazelcast.sdk"
          resourceName "Set.add test1"
          parent()
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          tags {
            "$Tags.COMPONENT" "hazelcast-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "hazelcast.service" "Set"
            "hazelcast.name" "test1"
            "hazelcast.operation" "Set.add"
            "hazelcast.instance" client.getName()
            defaultTags()
          }
        }
      }
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
    assertTraces(1) {
      trace(1) {
        span {
          serviceName "hazelcast-sdk"
          operationName "hazelcast.sdk"
          resourceName "List.contains test"
          parent()
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          tags {
            "$Tags.COMPONENT" "hazelcast-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "hazelcast.service" "List"
            "hazelcast.name" "test"
            "hazelcast.operation" "List.contains"
            "hazelcast.instance" client.getName()
            defaultTags()
          }
        }
      }
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

    assertTraces(1) {
      trace(1) {
        span {
          serviceName "hazelcast-sdk"
          operationName "hazelcast.sdk"
          resourceName "Lock.isLocked test"
          parent()
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          tags {
            "$Tags.COMPONENT" "hazelcast-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "hazelcast.service" "Lock"
            "hazelcast.name" "test"
            "hazelcast.operation" "Lock.isLocked"
            "hazelcast.instance" client.getName()
            defaultTags()
          }
        }
      }
    }

    cleanup:
    serverLock?.destroy()
  }
}
