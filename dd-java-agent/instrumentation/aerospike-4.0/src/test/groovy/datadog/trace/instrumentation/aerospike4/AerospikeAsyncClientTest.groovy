package datadog.trace.instrumentation.aerospike4

import com.aerospike.client.AerospikeClient
import com.aerospike.client.AerospikeException
import com.aerospike.client.Bin
import com.aerospike.client.Key
import com.aerospike.client.Record
import com.aerospike.client.async.EventLoops
import com.aerospike.client.async.NioEventLoops
import com.aerospike.client.listener.RecordListener
import com.aerospike.client.listener.WriteListener
import com.aerospike.client.policy.ClientPolicy
import com.aerospike.client.policy.Policy
import com.aerospike.client.policy.WritePolicy
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import spock.lang.Shared

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import static java.util.concurrent.TimeUnit.SECONDS
import static org.junit.jupiter.api.Assertions.fail

abstract class AerospikeAsyncClientTest extends AerospikeBaseTest {

  @Shared
  AerospikeClient client

  EventLoops eventLoops

  def setup() throws Exception {
    eventLoops = new NioEventLoops(1)
    ClientPolicy clientPolicy = new ClientPolicy()
    clientPolicy.eventLoops = eventLoops
    client = new AerospikeClient(clientPolicy, aerospikeHost, aerospikePort)
  }

  def cleanup() throws Exception {
    client.close()
    eventLoops.close()
  }

  def "test put then get"() {
    setup:
    def key = new Key("test", "set", "key")
    def bin = new Bin("name", "value")

    when:
    client.put(eventLoops.next(), new WriteListener() {

        @Override
        void onSuccess(Key putKey) {
          client.get(eventLoops.next(), null, null, putKey)
        }

        @Override
        void onFailure(AerospikeException error) {
          fail(error.toString())
        }
      }, null, key, bin)

    then:
    assertTraces(1) {
      sortSpansByStart()
      trace(2) {
        aerospikeSpan(it, 1, "AerospikeClient.put")
        aerospikeSpan(it, 0, "AerospikeClient.get", span(0))
      }
    }
  }

  def "immediate async validation failure releases its continuation and caller parent"() {
    setup:
    def callbacks = new AtomicInteger()
    def listener = new RecordListener() {
        @Override
        void onSuccess(Key key, Record record) {
          callbacks.incrementAndGet()
        }

        @Override
        void onFailure(AerospikeException error) {
          callbacks.incrementAndGet()
        }
      }
    def eventLoop = eventLoops.next()
    def drained = new CountDownLatch(1)

    when:
    runUnderTrace("rejected-async-get") {
      def caller = activeSpan()
      try {
        // Command construction dereferences the key before event-loop submission.
        client.get(eventLoop, listener, (Policy) null, (Key) null)
      } finally {
        assert activeSpan().is(caller)
      }
    }

    then:
    def failure = thrown(NullPointerException)
    failure.class == NullPointerException
    failure.stackTrace[0].className.startsWith("com.aerospike.client.")
    callbacks.get() == 0
    activeSpan() == null
    assertTraces(1) {
      trace(2) {
        sortSpansByStart()
        basicSpan(it, "rejected-async-get", null, failure)
        span {
          serviceName service()
          operationName operation()
          resourceName "AerospikeClient.get"
          spanType DDSpanTypes.AEROSPIKE
          childOf span(0)
          topLevel(service() != Config.get().getServiceName())
          errored true
          tags {
            "$Tags.COMPONENT" "java-aerospike"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "aerospike"
            "$Tags.DB_INSTANCE" null
            "$Tags.PEER_HOSTNAME" null
            "$Tags.PEER_HOST_IPV4" null
            "$Tags.PEER_PORT" null
            errorTags(failure)
            defaultTagsNoPeerService()
          }
        }
      }
    }

    when:
    TEST_WRITER.clear()
    def independent = new Key("test", "set", "after-validation-error")
    client.put((WritePolicy) null, independent, new Bin("name", "recovered"))
    def recovered = client.get((Policy) null, independent)
    eventLoop.execute { drained.countDown() }

    then:
    drained.await(10, SECONDS)
    callbacks.get() == 0
    recovered.bins == [name: "recovered"]
    recovered.generation == 1
    activeSpan() == null
    assertTraces(2) {
      sortSpansByStart()
      trace(1) {
        aerospikeSpan(it, 0, "AerospikeClient.put")
      }
      trace(1) {
        aerospikeSpan(it, 0, "AerospikeClient.get")
      }
    }
  }
}

class AerospikeAsyncClientV0ForkedTest extends AerospikeAsyncClientTest {
  @Override
  int version() {
    return 0
  }

  @Override
  String service() {
    return "aerospike"
  }

  @Override
  String operation() {
    return "aerospike.query"
  }
}

class AerospikeAsyncClientV1ForkedTest extends AerospikeAsyncClientTest {
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
    return "aerospike.query"
  }
}

