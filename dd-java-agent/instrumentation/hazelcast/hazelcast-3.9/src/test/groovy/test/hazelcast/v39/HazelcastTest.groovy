package test.hazelcast.v39

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
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.DDSpan
import net.bytebuddy.utility.RandomString
import spock.lang.Shared
import spock.util.concurrent.BlockingVariable

import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

abstract class HazelcastTest extends VersionedNamingTestBase {

  @Shared
  String randomName
  @Shared
  HazelcastInstance h1, client

  final resourceNamePattern = ~/^(?<operation>(?<service>[A-Z]\w+)\.[a-z]\w+)(?: (?<name>.+))?$/

  /** Filter our Client operations. They can happen at seemingly random times and yield inconsistent test results. */
  final ListWriter.Filter defaultFilter = new ListWriter.Filter() {
    @Override
    boolean accept(List<DDSpan> trace) {
      return !(trace.size() == 1 && trace.get(0).getResourceName().startsWithAny("Client."))
    }
  }

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.integration.hazelcast.enabled", "true")
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
    Hazelcast.shutdownAll()
  }

  def setup() {
    TEST_WRITER.setFilter(defaultFilter)
    randomName = randomResourceName()
  }


  def "map"() {
    setup:

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
        "hazelcast.service" matcher.group("service")
        "hazelcast.instance" client.name
        "hazelcast.correlationId" Long
        peerServiceFrom("hazelcast.instance")
        defaultTags()
      }
    }
  }

  void configureServer(Config config) {}

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
