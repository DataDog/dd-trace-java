package datadog.trace.instrumentation.spymemcached

import com.google.common.util.concurrent.MoreExecutors
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import net.spy.memcached.CASResponse
import net.spy.memcached.ConnectionFactory
import net.spy.memcached.ConnectionFactoryBuilder
import net.spy.memcached.DefaultConnectionFactory
import net.spy.memcached.MemcachedClient
import net.spy.memcached.internal.CheckedOperationTimeoutException
import net.spy.memcached.ops.Operation
import net.spy.memcached.ops.OperationQueueFactory
import org.testcontainers.containers.GenericContainer
import spock.lang.Shared

import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.locks.ReentrantLock

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE
import static datadog.trace.instrumentation.spymemcached.MemcacheClientDecorator.*
import static datadog.trace.instrumentation.spymemcached.MemcacheClientDecorator.COMPONENT_NAME
import static net.spy.memcached.ConnectionFactoryBuilder.Protocol.BINARY

abstract class SpymemcachedTest extends VersionedNamingTestBase {

  @Shared
  def parentOperation = "parent-span"
  @Shared
  def expiration = 3600
  @Shared
  def keyPrefix = "SpymemcachedTest-" + (Math.abs(new Random().nextInt())) + "-"
  @Shared
  def defaultMemcachedPort = 11211
  @Shared
  def timingOutMemcachedOpTimeout = 1000

  @Shared
  def memcachedContainer
  @Shared
  InetSocketAddress memcachedAddress = new InetSocketAddress("127.0.0.1", defaultMemcachedPort)

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    // This setting should have no effect since decorator returns null for the instance.
    injectSysConfig(DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "true")
  }

  def setupSpec() {
    memcachedContainer = new GenericContainer('memcached:1.6.14-alpine')
      .withExposedPorts(defaultMemcachedPort)
      .withStartupTimeout(Duration.ofSeconds(120))
    memcachedContainer.start()
    memcachedAddress = new InetSocketAddress(
      memcachedContainer.getHost(),
      memcachedContainer.getMappedPort(defaultMemcachedPort)
      )
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

    // Use direct executor service so our listeners finish in deterministic order
    ExecutorService listenerExecutorService = MoreExecutors.newDirectExecutorService()

    ConnectionFactory connectionFactory = (new ConnectionFactoryBuilder())
      .setListenerExecutorService(listenerExecutorService)
      .setProtocol(BINARY)
      .build()
    memcached = new MemcachedClient(connectionFactory, Arrays.asList(memcachedAddress))

    def lockableQueueFactory = new OperationQueueFactory() {
        @Override
        BlockingQueue<Operation> create() {
          return getLockableQueue(queueLock)
        }
      }

    ConnectionFactory lockableConnectionFactory = (new ConnectionFactoryBuilder())
      .setListenerExecutorService(listenerExecutorService)
      .setProtocol(BINARY)
      .setOpQueueFactory(lockableQueueFactory)
      .build()
    locableMemcached = new MemcachedClient(lockableConnectionFactory, Arrays.asList(memcachedAddress))

    ConnectionFactory timingoutConnectionFactory = (new ConnectionFactoryBuilder())
      .setListenerExecutorService(listenerExecutorService)
      .setProtocol(BINARY)
      .setOpQueueFactory(lockableQueueFactory)
      .setOpTimeout(timingOutMemcachedOpTimeout)
      .build()
    timingoutMemcached = new MemcachedClient(timingoutConnectionFactory, Arrays.asList(memcachedAddress))

    // Add some keys to test on later:
    def valuesToSet = [
      "test-get"    : "get test",
      "test-get-2"  : "get test 2",
      "test-append" : "append test",
      "test-prepend": "prepend test",
      "test-delete" : "delete test",
      "test-replace": "replace test",
      "test-touch"  : "touch test",
      "test-cas"    : "cas test",
      "test-decr"   : "200",
      "test-incr"   : "100"
    ]
    runUnderTrace("setup") {
      valuesToSet.each { k, v -> assert memcached.set(key(k), expiration, v).get() }
    }
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.clear()
  }

  def "test get hit"() {
    setup:

    when:
    runUnderTrace(parentOperation) {
      assert "get test" == memcached.get(key("test-get"))
    }

    then:
    assertTraces(1) {
      trace(2) {
        getParentSpan(it, 0)
        getSpan(it, 1, "get", null, "hit")
      }
    }
  }

  def "test get miss"() {
    setup:

    when:
    runUnderTrace(parentOperation) {
      assert null == memcached.get(key("test-get-key-that-doesn't-exist"))
    }

    then:
    assertTraces(1) {
      trace(2) {
        getParentSpan(it, 0)
        getSpan(it, 1, "get", null, "miss")
      }
    }
  }

  def "test get cancel"() {
    setup:

    when:
    runUnderTrace(parentOperation) {
      queueLock.lock()
      locableMemcached.asyncGet(key("test-get")).cancel(true)
      queueLock.unlock()
    }

    then:
    assertTraces(1) {
      trace(2) {
        getParentSpan(it, 0)
        getSpan(it, 1, "get", "canceled")
      }
    }
  }

  def "test get timeout"() {
    setup:

    when:
    /*
     Not using runUnderTrace since timeouts happen in separate thread
     and direct executor doesn't help to make sure that parent span finishes last.
     Instead run without parent span to have only 1 span to test with.
     */
    try {
      queueLock.lock()
      timingoutMemcached.asyncGet(key("test-get"))
      Thread.sleep(timingOutMemcachedOpTimeout + 1000)
    } finally {
      queueLock.unlock()
    }

    then:
    assertTraces(1) {
      trace(1) {
        getSpan(it, 0, "get", "timeout")
      }
    }
  }

  def "test bulk get"() {
    setup:

    when:
    runUnderTrace(parentOperation) {
      def expected = [(key("test-get")): "get test", (key("test-get-2")): "get test 2"]
      assert expected == memcached.getBulk(key("test-get"), key("test-get-2"))
    }

    then:
    assertTraces(1) {
      trace(2) {
        getParentSpan(it, 0)
        getSpan(it, 1, "getBulk", null, null)
      }
    }
  }

  def "test set"() {
    setup:

    when:
    runUnderTrace(parentOperation) {
      assert memcached.set(key("test-set"), expiration, "bar").get()
    }

    then:
    assertTraces(1) {
      trace(2) {
        getParentSpan(it, 0)
        getSpan(it, 1, "set")
      }
    }
  }

  def "test set cancel"() {
    setup:

    when:
    runUnderTrace(parentOperation) {
      queueLock.lock()
      assert locableMemcached.set(key("test-set-cancel"), expiration, "bar").cancel()
      queueLock.unlock()
    }

    then:
    assertTraces(1) {
      trace(2) {
        getParentSpan(it, 0)
        getSpan(it, 1, "set", "canceled")
      }
    }
  }

  def "test add"() {
    setup:

    when:
    runUnderTrace(parentOperation) {
      assert memcached.add(key("test-add"), expiration, "add bar").get()
      assert "add bar" == memcached.get(key("test-add"))
    }

    then:
    assertTraces(1) {
      trace(3) {
        getParentSpan(it, 0)
        getSpan(it, 1, "get", null, "hit")
        getSpan(it, 2, "add")
      }
    }
  }

  def "test second add"() {
    setup:

    when:
    runUnderTrace(parentOperation) {
      assert memcached.add(key("test-add-2"), expiration, "add bar").get()
      assert !memcached.add(key("test-add-2"), expiration, "add bar 123").get()
    }

    then:
    assertTraces(1) {
      trace(3) {
        getParentSpan(it, 0)
        getSpan(it, 1, "add")
        getSpan(it, 2, "add")
      }
    }
  }

  def "test delete"() {
    setup:

    when:
    runUnderTrace(parentOperation) {
      assert memcached.delete(key("test-delete")).get()
      assert null == memcached.get(key("test-delete"))
    }

    then:
    assertTraces(1) {
      trace(3) {
        getParentSpan(it, 0)
        getSpan(it, 1, "get", null, "miss")
        getSpan(it, 2, "delete")
      }
    }
  }

  def "test delete non existent"() {
    setup:

    when:
    runUnderTrace(parentOperation) {
      assert !memcached.delete(key("test-delete-non-existent")).get()
    }

    then:
    assertTraces(1) {
      trace(2) {
        getParentSpan(it, 0)
        getSpan(it, 1, "delete")
      }
    }
  }

  def "test replace"() {
    setup:

    when:
    runUnderTrace(parentOperation) {
      assert memcached.replace(key("test-replace"), expiration, "new value").get()
      assert "new value" == memcached.get(key("test-replace"))
    }

    then:
    assertTraces(1) {
      trace(3) {
        getParentSpan(it, 0)
        getSpan(it, 1, "get", null, "hit")
        getSpan(it, 2, "replace")
      }
    }
  }

  def "test replace non existent"() {
    setup:

    when:
    runUnderTrace(parentOperation) {
      assert !memcached.replace(key("test-replace-non-existent"), expiration, "new value").get()
    }

    then:
    assertTraces(1) {
      trace(2) {
        getParentSpan(it, 0)
        getSpan(it, 1, "replace")
      }
    }
  }

  def "test append"() {
    setup:

    when:
    runUnderTrace(parentOperation) {
      def cas = memcached.gets(key("test-append"))
      assert memcached.append(cas.cas, key("test-append"), " appended").get()
      assert "append test appended" == memcached.get(key("test-append"))
    }

    then:
    assertTraces(1) {
      trace(4) {
        getParentSpan(it, 0)
        getSpan(it, 1, "get", null, "hit")
        getSpan(it, 2, "append")
        getSpan(it, 3, "gets")
      }
    }
  }

  def "test prepend"() {
    setup:

    when:
    runUnderTrace(parentOperation) {
      def cas = memcached.gets(key("test-prepend"))
      assert memcached.prepend(cas.cas, key("test-prepend"), "prepended ").get()
      assert "prepended prepend test" == memcached.get(key("test-prepend"))
    }

    then:
    assertTraces(1) {
      trace(4) {
        getParentSpan(it, 0)
        getSpan(it, 1, "get", null, "hit")
        getSpan(it, 2, "prepend")
        getSpan(it, 3, "gets")
      }
    }
  }

  def "test cas"() {
    setup:

    when:
    runUnderTrace(parentOperation) {
      def cas = memcached.gets(key("test-cas"))
      assert CASResponse.OK == memcached.cas(key("test-cas"), cas.cas, expiration, "cas bar")
    }

    then:
    assertTraces(1) {
      trace(3) {
        getParentSpan(it, 0)
        getSpan(it, 1, "cas")
        getSpan(it, 2, "gets")
      }
    }
  }

  def "test cas not found"() {
    setup:

    when:
    runUnderTrace(parentOperation) {
      assert CASResponse.NOT_FOUND == memcached.cas(key("test-cas-doesnt-exist"), 1234, expiration, "cas bar")
    }

    then:
    assertTraces(1) {
      trace(2) {
        getParentSpan(it, 0)
        getSpan(it, 1, "cas")
      }
    }
  }

  def "test touch"() {
    setup:

    when:
    runUnderTrace(parentOperation) {
      assert memcached.touch(key("test-touch"), expiration).get()
    }

    then:
    assertTraces(1) {
      trace(2) {
        getParentSpan(it, 0)
        getSpan(it, 1, "touch")
      }
    }
  }

  def "test touch non existent"() {
    setup:

    when:
    runUnderTrace(parentOperation) {
      assert !memcached.touch(key("test-touch-non-existent"), expiration).get()
    }

    then:
    assertTraces(1) {
      trace(2) {
        getParentSpan(it, 0)
        getSpan(it, 1, "touch")
      }
    }
  }

  def "test get and touch"() {
    setup:

    when:
    runUnderTrace(parentOperation) {
      assert "touch test" == memcached.getAndTouch(key("test-touch"), expiration).value
    }

    then:
    assertTraces(1) {
      trace(2) {
        getParentSpan(it, 0)
        getSpan(it, 1, "getAndTouch")
      }
    }
  }

  def "test get and touch non existent"() {
    setup:

    when:
    runUnderTrace(parentOperation) {
      assert null == memcached.getAndTouch(key("test-touch-non-existent"), expiration)
    }

    then:
    assertTraces(1) {
      trace(2) {
        getParentSpan(it, 0)
        getSpan(it, 1, "getAndTouch")
      }
    }
  }

  def "test decr"() {
    setup:

    when:
    runUnderTrace(parentOperation) {
      /*
       Memcached is funny in the way it handles incr/decr operations:
       it needs values to be strings (with digits in them) and it returns actual long from decr/incr
       */
      assert 195 == memcached.decr(key("test-decr"), 5)
      assert "195" == memcached.get(key("test-decr"))
    }

    then:
    assertTraces(1) {
      trace(3) {
        getParentSpan(it, 0)
        getSpan(it, 1, "get", null, "hit")
        getSpan(it, 2, "decr")
      }
    }
  }

  def "test decr non existent"() {
    setup:

    when:
    runUnderTrace(parentOperation) {
      assert -1 == memcached.decr(key("test-decr-non-existent"), 5)
    }

    then:
    assertTraces(1) {
      trace(2) {
        getParentSpan(it, 0)
        getSpan(it, 1, "decr")
      }
    }
  }

  def "test decr exception"() {
    setup:

    when:
    memcached.decr(key("long key: " + longString()), 5)

    then:
    thrown IllegalArgumentException
    assertTraces(1) {
      trace(1) {
        getSpan(it, 0, "decr", "long key", null, false)
      }
    }
  }

  def "test incr"() {
    setup:

    when:
    runUnderTrace(parentOperation) {
      /*
       Memcached is funny in the way it handles incr/decr operations:
       it needs values to be strings (with digits in them) and it returns actual long from decr/incr
       */
      assert 105 == memcached.incr(key("test-incr"), 5)
      assert "105" == memcached.get(key("test-incr"))
    }

    then:
    assertTraces(1) {
      trace(3) {
        getParentSpan(it, 0)
        getSpan(it, 1, "get", null, "hit")
        getSpan(it, 2, "incr")
      }
    }
  }

  def "test incr non existent"() {
    setup:

    when:
    runUnderTrace(parentOperation) {
      assert -1 == memcached.incr(key("test-incr-non-existent"), 5)
    }

    then:
    assertTraces(1) {
      trace(2) {
        getParentSpan(it, 0)
        getSpan(it, 1, "incr")
      }
    }
  }

  def "test incr exception"() {
    setup:

    when:
    memcached.incr(key("long key: " + longString()), 5)

    then:
    thrown IllegalArgumentException
    assertTraces(1) {
      trace(1) {
        getSpan(it, 0, "incr", "long key", null, false)
      }
    }
  }

  def key(String k) {
    keyPrefix + k
  }

  def longString(char c = 's' as char) {
    char[] chars = new char[250]
    Arrays.fill(chars, 's' as char)
    return new String(chars)
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

  def getParentSpan(TraceAssert trace, int index) {
    return trace.span {
      operationName parentOperation
      parent()
      errored false
      tags {
        defaultTags()
      }
    }
  }

  def getSpan(TraceAssert trace, int index, String resource, String error = null, String result = null, boolean expectPeerInfo = true) {
    return trace.span {
      if (index > 0) {
        childOf(trace.span(0))
      }

      serviceName service()
      operationName operation()
      resourceName resource
      spanType DDSpanTypes.MEMCACHED
      errored(error != null && error != "canceled")
      measured true
      tags {
        "$Tags.COMPONENT" COMPONENT_NAME
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
        "$Tags.DB_TYPE" DB_TYPE

        if (error == "canceled") {
          "${CompletionListener.DB_COMMAND_CANCELLED}" true
        }

        if (result == "hit") {
          "${CompletionListener.MEMCACHED_RESULT}" CompletionListener.HIT
        }

        if (result == "miss") {
          "${CompletionListener.MEMCACHED_RESULT}" CompletionListener.MISS
        }

        if (error == "timeout") {
          errorTags(
            CheckedOperationTimeoutException,
            "Operation timed out. - failing node: ${memcachedAddress.address}:${memcachedAddress.port}")
        }

        if (error == "long key") {
          errorTags(
            IllegalArgumentException,
            "Key is too long (maxlen = 250)")
        }
        if (expectPeerInfo) {
          "$Tags.PEER_HOSTNAME" memcachedAddress.getHostName()
          "$Tags.PEER_HOST_IPV4" InetAddress.getByName(memcachedAddress.getHostName()).getHostAddress()
          "$Tags.PEER_PORT" memcachedAddress.getPort()
          peerServiceFrom(Tags.PEER_HOSTNAME)
          defaultTags()
        } else {
          defaultTagsNoPeerService()
        }

      }
    }
  }
}

class SpymemcachedV0Test extends SpymemcachedTest {

  @Override
  int version() {
    return 0
  }

  @Override
  String service() {
    return "memcached"
  }

  @Override
  String operation() {
    return "memcached.query"
  }
}

class SpymemcachedV1ForkedTest extends SpymemcachedTest {

  @Override
  int version() {
    return 1
  }

  @Override
  String service() {
    return Config.get().getServiceName()
  }

  @Override
  String operation() {
    return "memcached.command"
  }
}
