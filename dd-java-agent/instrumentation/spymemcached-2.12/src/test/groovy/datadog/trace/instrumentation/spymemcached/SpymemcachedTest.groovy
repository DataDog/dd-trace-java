package datadog.trace.instrumentation.spymemcached

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.TraceAssert
import datadog.trace.api.DDTags
import io.opentracing.tag.Tags
import net.spy.memcached.ConnectionFactory
import net.spy.memcached.ConnectionFactoryBuilder
import net.spy.memcached.DefaultConnectionFactory
import net.spy.memcached.MemcachedClient
import net.spy.memcached.internal.CheckedOperationTimeoutException
import net.spy.memcached.ops.Operation
import net.spy.memcached.ops.OperationQueueFactory
import org.testcontainers.containers.GenericContainer
import spock.lang.Shared

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.locks.ReentrantLock

import static datadog.trace.agent.test.ListWriterAssert.assertTraces
import static DDTracingCompletionListener.COMPONENT_NAME
import static DDTracingCompletionListener.OPERATION_NAME
import static DDTracingCompletionListener.SERVICE_NAME
import static DDTracingCompletionListener.SPAN_TYPE

class SpymemcachedTest extends AgentTestRunner {

  @Shared
  def keyPrefix = "SpymemcachedTest-"
  @Shared
  def defaultMemcachedPort = 11211
  @Shared
  def timingOutMemcachedOpTimeout = 1000

  /*
    Note: type here has to stay undefined, otherwise tests will fail in CI in Java 7 because
    'testcontainers' are built for Java 8 and Java 7 cannot load this class.
   */
  @Shared
  def memcachedContainer
  @Shared
  InetSocketAddress memcachedAddress = new InetSocketAddress("127.0.0.1", defaultMemcachedPort)

  def setupSpec() {

    /*
      CI will provide us with memcached container running along side our build.
      When building locally, however, we need to take matters into our own hands
      and we use 'testcontainers' for this.
     */
    if ("true" != System.getenv("CI")) {
      memcachedContainer = new GenericContainer('memcached:latest')
        .withExposedPorts(defaultMemcachedPort)
      memcachedContainer.start()
      memcachedAddress = new InetSocketAddress(
        memcachedContainer.containerIpAddress,
        memcachedContainer.getMappedPort(defaultMemcachedPort)
      )
    }
  }

  def cleanupSpec() {
    if (memcachedContainer) {
      memcachedContainer.stop()
    }
  }

  ReentrantLock queueLock
  MemcachedClient memcached
  MemcachedClient locableMemcached
  MemcachedClient timingoutMemcached

  def setup() {
    queueLock = new ReentrantLock()

    memcached = new MemcachedClient(memcachedAddress)

    def lockableQueueFactory = new OperationQueueFactory() {
      @Override
      BlockingQueue<Operation> create() {
        return getLockableQueue(queueLock)
      }
    }

    ConnectionFactory lockableConnectionFactory = (new ConnectionFactoryBuilder())
      .setOpQueueFactory(lockableQueueFactory).build()
    locableMemcached = new MemcachedClient(lockableConnectionFactory, Arrays.asList(memcachedAddress))

    ConnectionFactory timingoutConnectionFactory = (new ConnectionFactoryBuilder())
      .setOpQueueFactory(lockableQueueFactory).setOpTimeout(timingOutMemcachedOpTimeout).build()
    timingoutMemcached = new MemcachedClient(timingoutConnectionFactory, Arrays.asList(memcachedAddress))

    // Add some keys to test on later:
    assert memcached.set(key("test-get"), 3600, "get test").get()
    assert memcached.set(key("test-get-2"), 3600, "get test 2").get()
    assert memcached.set(key("test-incr"), 3600, 100).get()
    assert memcached.set(key("test-decr"), 3600, 200).get()
    // Note: this is kind of brittle: parameter of this function has to match number of set calls above
    TEST_WRITER.waitForTraces(4)
    TEST_WRITER.clear()
  }

  def "test get hit"() {
    when:
    def res = memcached.get(key("test-get"))

    then:
    res == "get test"
    assertTraces(TEST_WRITER, 1) {
      trace(0, 1) {
        getSpan(it, 0, "get", null,"hit")
      }
    }
  }

  def "test get miss"() {
    when:
    def res = memcached.get(key("test-get-key-that-doesn't-exist"))

    then:
    res == null
    assertTraces(TEST_WRITER, 1) {
      trace(0, 1) {
        getSpan(it, 0, "get", null,"miss")
      }
    }
  }

  def "test get cancel"() {
    when:
    queueLock.lock()
    locableMemcached.asyncGet(key("test-get")).cancel(true)
    queueLock.unlock()

    then:
    assertTraces(TEST_WRITER, 1) {
      trace(0, 1) {
          getSpan(it, 0, "get", "canceled")
      }
    }
  }

  def "test get timeout"() {
    when:
    try {
      queueLock.lock()
      timingoutMemcached.asyncGet(key("test-get"))
      Thread.sleep(timingOutMemcachedOpTimeout + 1000)
    } finally {
      queueLock.unlock()
    }
    then:
    assertTraces(TEST_WRITER, 1) {
      trace(0, 1) {
        getSpan(it, 0, "get", "timeout")
      }
    }
  }

  def "test bulk get"() {
    when:
    def res = memcached.getBulk(key("test-get"), key("test-get-2"))

    then:
    res == [(key("test-get")): "get test", (key("test-get-2")): "get test 2"]
    assertTraces(TEST_WRITER, 1) {
      trace(0, 1) {
        getSpan(it, 0, "getBulk", null,null)
      }
    }
  }

  def "test set"() {
    when:
    def res = memcached.set(key("test-set"), 3600, "bar").get()

    then:
    res == true
    assertTraces(TEST_WRITER, 1) {
      trace(0, 1) {
        getSpan(it, 0, "set")
      }
    }
  }

  def "test set cancel"() {
    when:
    queueLock.lock()
    def res = locableMemcached.set(key("test-set-cancel"), 3600, "bar").cancel()
    queueLock.unlock()

    then:
    res == true
    assertTraces(TEST_WRITER, 1) {
      trace(0, 1) {
        getSpan(it, 0, "set", "canceled")
      }
    }
  }

  def key(String k) {
    keyPrefix + k
  }

  def getLockableQueue(ReentrantLock queueLock) {
    return new ArrayBlockingQueue<Operation>(DefaultConnectionFactory.DEFAULT_OP_QUEUE_LEN) {

      @Override
      int drainTo(Collection<? super Operation> c, int maxElements) {
        try {
          queueLock.lock()
          return super.drainTo(c, maxElements)
        } finally {
          queueLock.unlock()
        }
      }
    }
  }

  def getSpan(TraceAssert trace, int index, String operation, String error=null, String result=null) {
    return trace.span(index) {
      serviceName SERVICE_NAME
      operationName OPERATION_NAME
      resourceName operation
      spanType SPAN_TYPE
      errored (error != null && error != "canceled")

      tags {
        defaultTags()
        "${DDTags.SPAN_TYPE}" SPAN_TYPE
        "${Tags.COMPONENT.getKey()}" COMPONENT_NAME
        "${Tags.SPAN_KIND.getKey()}" Tags.SPAN_KIND_CLIENT
        "${Tags.DB_TYPE.getKey()}" DDTracingCompletionListener.DB_TYPE

        if (error == "canceled") {
          "${DDTracingCompletionListener.DB_COMMAND_CANCELLED}" true
        }

        if (error == "timeout") {
          errorTags(
            CheckedOperationTimeoutException,
            "Operation timed out. - failing node: ${memcachedAddress.address}:${memcachedAddress.port}")
        }

        if (result == "hit") {
          "${DDTracingCompletionListener.MEMCACHED_RESULT}" DDTracingCompletionListener.HIT
        }

        if (result == "miss") {
          "${DDTracingCompletionListener.MEMCACHED_RESULT}" DDTracingCompletionListener.MISS
        }
      }
    }
  }
}
