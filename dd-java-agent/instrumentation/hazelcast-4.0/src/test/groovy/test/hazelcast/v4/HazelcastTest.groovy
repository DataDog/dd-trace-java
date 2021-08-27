package test.hazelcast.v4

import com.hazelcast.config.Config
import com.hazelcast.config.cp.SemaphoreConfig
import com.hazelcast.query.Predicates
import com.hazelcast.topic.Message
import com.hazelcast.topic.MessageListener
import datadog.trace.agent.test.checkpoints.CheckpointValidator
import datadog.trace.agent.test.checkpoints.CheckpointValidationMode
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import spock.util.concurrent.BlockingVariable

import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

class HazelcastTest extends AbstractHazelcastTest {

  @Override
  void configureServer(Config config) {
    super.configureServer(config)

    // Setup for semaphore test since this needs to be done at server start
    def semaphore = new SemaphoreConfig("test")
    semaphore.setInitialPermits(3)
    config.getCPSubsystemConfig().addSemaphoreConfig(semaphore)
  }

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

    then: "result matches"
    result == "bar"

    and: "captured expected traces"
    assertTraces(1) {
      hazelcastTrace(it, "Map.Get ${randomName}")
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

    then: "result matches expected value"
    result as Set == ["bar"] as Set

    and: "captured expected traces"

    assertTraces(1) {
      hazelcastTrace(it, "Map.ValuesWithPredicate ${randomName}")
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

    assertTraces(1) {
      hazelcastTrace(it, "MultiMap.Get ${randomName}")
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

    assertTraces(1) {
      hazelcastTrace(it, "Queue.Take ${randomName}")
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
    assertTraces(1) {
      hazelcastTrace(it, "Set.Contains ${randomName}")
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
    assertTraces(2) {
      hazelcastTrace(it, "Set.Add ${randomName}")
      hazelcastTrace(it, "Set.Add ${randomName}")
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
    assertTraces(1) {
      hazelcastTrace(it, "List.Contains ${randomName}")
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
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)

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
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)

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
