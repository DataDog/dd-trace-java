package test.hazelcast.v3

import com.hazelcast.client.HazelcastClient
import com.hazelcast.client.config.ClientConfig
import com.hazelcast.config.Config
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.core.Message
import com.hazelcast.core.MessageListener
import com.hazelcast.query.Predicates
import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.agent.test.utils.PortUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import net.bytebuddy.utility.RandomString
import spock.lang.Shared
import spock.util.concurrent.BlockingVariable

import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

abstract class HazelcastTest extends VersionedNamingTestBase {

  @Shared
  HazelcastInstance h1, client
  @Shared
  String randomName

  final resourceNamePattern = ~/^(?<name>(?<service>\w+)\[[^]]+])\.(?<operation>\w+)$/

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.integration.hazelcast_legacy.enabled", "true")
  }

  @Override
  def setupSpec() {
    def port = PortUtils.randomOpenPort()
    def groupName = RandomString.make(8)
    def groupPassword = RandomString.make(8)

    def serverConfig = new Config()
    serverConfig.groupConfig.name = groupName
    serverConfig.groupConfig.password = groupPassword

    serverConfig.networkConfig.port = port
    serverConfig.networkConfig.portAutoIncrement = false
    serverConfig.networkConfig.join.multicastConfig.enabled = false
    configureServer(serverConfig)

    h1 = Hazelcast.newHazelcastInstance(serverConfig)

    def clientConfig = new ClientConfig()
    clientConfig.groupConfig.name = groupName
    clientConfig.groupConfig.password = groupPassword
    clientConfig.networkConfig.addAddress("127.0.0.1:$port")
    client = HazelcastClient.newHazelcastClient(clientConfig)
  }

  @Override
  def cleanupSpec() {
    try {
      Hazelcast.shutdownAll()
    } catch (Exception e) {
      e.printStackTrace()
    }
  }

  void configureServer(Config config) {
  }

  def setup() {
    randomName = randomResourceName()
  }


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
          serviceName service()
          operationName operation()
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

  void hazelcastTrace(ListWriterAssert writer, String name) {
    writer.trace(1) {
      hazelcastSpan(it, name)
    }
  }

  def hazelcastSpan(TraceAssert trace, String name, boolean isParent = true) {
    def matcher = name =~ resourceNamePattern
    assert matcher.matches()

    trace.span {
      serviceName service()
      resourceName name
      operationName operation()
      spanType DDSpanTypes.HTTP_CLIENT
      errored false
      measured true
      if (isParent) {
        parent()
      } else {
        childOfPrevious()
      }
      tags {
        "$Tags.COMPONENT" "hazelcast-sdk"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
        "hazelcast.name" matcher.group("name")
        "hazelcast.operation" matcher.group("operation")
        "hazelcast.service" "hz:impl:${matcher.group("service")}Service"
        "hazelcast.instance" client.name
        peerServiceFrom("hazelcast.instance")
        defaultTags()
      }
    }
  }

  def randomResourceName(int length = 8) {
    RandomString.make(length)
  }
}

class HazelcastV0ForkedTest extends HazelcastTest {

  @Override
  int version() {
    return 0
  }

  @Override
  String service() {
    return "hazelcast-sdk"
  }

  @Override
  String operation() {
    return "hazelcast.invoke"
  }
}

class HazelcastV1ForkedTest extends HazelcastTest {

  @Override
  int version() {
    return 1
  }

  @Override
  String service() {
    return datadog.trace.api.Config.get().getServiceName()
  }

  @Override
  String operation() {
    return "hazelcast.command"
  }
}
