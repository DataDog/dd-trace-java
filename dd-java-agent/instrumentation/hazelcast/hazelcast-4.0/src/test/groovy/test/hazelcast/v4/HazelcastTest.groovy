package test.hazelcast.v4

import com.hazelcast.client.HazelcastClient
import com.hazelcast.client.config.ClientConfig
import com.hazelcast.config.Config
import com.hazelcast.config.cp.SemaphoreConfig
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.query.Predicates
import com.hazelcast.topic.Message
import com.hazelcast.topic.MessageListener
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
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

abstract class HazelcastTest extends VersionedNamingTestBase {

  @Shared
  HazelcastInstance h1, client
  @Shared
  String randomName

  final resourceNamePattern = ~/^(?<operation>(?<service>[A-Z]\w+)\.[A-Z]\w+)(?: (?<name>.+))?$/

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

    def serverConfig = new Config()

    serverConfig.networkConfig.port = port
    serverConfig.networkConfig.portAutoIncrement = false
    serverConfig.networkConfig.join.multicastConfig.enabled = false
    configureServer(serverConfig)

    h1 = Hazelcast.newHazelcastInstance(serverConfig)

    def clientConfig = new ClientConfig()
    clientConfig.networkConfig.addAddress("127.0.0.1:$port")
    client = HazelcastClient.newHazelcastClient(clientConfig)

    // Start using client and sleep to avoid initial join event
    def list = client.getList("null")
    list.add(1)
    list.remove(0)
    sleep(1000)
  }

  @Override
  def cleanupSpec() {
    try {
      Hazelcast.shutdownAll()
    } catch (Exception e) {
      e.printStackTrace()
    }
  }

  def setup() {
    TEST_WRITER.setFilter(defaultFilter)
    randomName = randomResourceName()
  }

  void hazelcastEventTrace(ListWriterAssert writer) {
    writer.trace(1) {
      span {
        serviceName service()
        resourceName "Event.Handle"
        operationName operation()
        spanType DDSpanTypes.HTTP_CLIENT
        errored false
        parent()
        tags {
          "$Tags.COMPONENT" "hazelcast-sdk"
          "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
          "hazelcast.operation" "Event.Handle"
          "hazelcast.service" "Event"
          "hazelcast.correlationId" Long
          defaultTagsNoPeerService()
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
        "hazelcast.service" matcher.group("service")
        "hazelcast.instance" client.name
        "hazelcast.correlationId" Long
        peerServiceFrom("hazelcast.instance")
        defaultTags()
      }
    }
  }

  def randomResourceName(int length = 8) {
    RandomString.make(length)
  }

  void configureServer(Config config) {
    // Setup for semaphore test since this needs to be done at server start
    def semaphore = new SemaphoreConfig("test")
    semaphore.setInitialPermits(3)
    config.getCPSubsystemConfig().addSemaphoreConfig(semaphore)
  }

  def "map"() {
    setup:

    when:
    def serverMap = h1.getMap(randomName)
    serverMap.put("foo", "bar")

    def clientMap = client.getMap(randomName)
    def result = clientMap.get("foo")

    then: "result matches"
    result == "bar"

    and: "captured expected traces"
    assertTraces(1) {
      hazelcastTrace(it, "Map.Get ${randomName}")
    }
  }

  def "map predicate"() {
    setup:

    when:
    def serverMap = h1.getMap(randomName)
    serverMap.put("foo", "bar")

    def clientMap = client.getMap(randomName)
    def result = clientMap.values(Predicates.equal("__key", "foo"))

    then: "result matches expected value"
    result as Set == ["bar"] as Set

    and: "captured expected traces"

    assertTraces(1) {
      hazelcastTrace(it, "Map.ValuesWithPredicate ${randomName}")
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

    assertTraces(1) {
      sortSpansByStart()
      trace(2) {
        basicSpan(it, "test")
        hazelcastSpan(it, "Map.Get ${randomName}", false)
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

    assertTraces(1) {
      hazelcastTrace(it, "MultiMap.Get ${randomName}")
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

    assertTraces(1) {
      hazelcastTrace(it, "Queue.Take ${randomName}")
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

    assertTraces(3) {
      hazelcastTrace(it, "Topic.AddMessageListener")
      hazelcastTrace(it, "Topic.Publish ${randomName}")
      hazelcastEventTrace(it)
    }

    cleanup:
    serverTopic?.destroy()
    clientTopic?.destroy()
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
    assertTraces(1) {
      hazelcastTrace(it, "Set.Contains ${randomName}")
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
    assertTraces(2) {
      hazelcastTrace(it, "Set.Add ${randomName}")
      hazelcastTrace(it, "Set.Add ${randomName}")
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
    assertTraces(1) {
      hazelcastTrace(it, "List.Contains ${randomName}")
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
          resourceName "List.Get ${randomName}"
          parent()
          spanType DDSpanTypes.HTTP_CLIENT
          errored true
          tags {
            "$Tags.COMPONENT" "hazelcast-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "hazelcast.service" "List"
            "hazelcast.name" randomName
            "hazelcast.operation" "List.Get"
            "hazelcast.instance" client.getName()
            "hazelcast.correlationId" Long
            errorTags(ex)
            defaultTags()
          }
        }
      }
    }
  }

  def "semaphore"() {
    given: "reference to the semaphore retrieved"

    // Depending on server configuration for test semaphore
    def permits = 3
    def semaphore = client.getCPSubsystem().getSemaphore("test")

    when: "we attempt to acquire a semaphore"
    def aquired = semaphore.tryAcquire()

    then: "we acquire it successfully"
    aquired

    and: "the available permit count is decreased by one"
    semaphore.availablePermits() == --permits

    and: "the sempahore operations are recorded in the trace"
    /* Note this could be finicky between versions since the CP* traces are an
     implementation detail */
    assertTraces(5) {
      hazelcastTrace(it, "CPGroup.CreateCPGroup test")
      hazelcastTrace(it, "Semaphore.GetSemaphoreType test")
      hazelcastTrace(it, "CPSession.CreateSession sessionManager")
      hazelcastTrace(it, "Semaphore.Acquire test")
      hazelcastTrace(it, "Semaphore.AvailablePermits test")
    }

    cleanup:
    def cleanupSpan = runUnderTrace("cleanup") {
      semaphore?.release()
      activeSpan()
    }
    TEST_WRITER.waitUntilReported(cleanupSpan as DDSpan)
  }

  def "submit callable"() {
    given: "setup list"

    client.getList("sum").addAll(Arrays.asList(1, 2, 3, 4, 5))

    and: "get executor service"
    def clientExecutor = client.getExecutorService(randomName)

    when: "submit task"
    def future = clientExecutor.submit(new SumTask())

    and: "wait for the results"
    def result = future.get()

    then: "check results"
    result == 15

    and: "confirm we received the expected traces"
    assertTraces(2) {
      hazelcastTrace(it, "List.AddAll sum")
      hazelcastTrace(it, "ExecutorService.SubmitToPartition ${randomName}")
    }
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
