package datadog.trace.instrumentation.aerospike4

import com.aerospike.client.AerospikeClient
import com.aerospike.client.AerospikeException
import com.aerospike.client.Bin
import com.aerospike.client.Key
import com.aerospike.client.async.EventLoops
import com.aerospike.client.async.NioEventLoops
import com.aerospike.client.listener.WriteListener
import com.aerospike.client.policy.ClientPolicy
import datadog.trace.api.Config
import spock.lang.Shared

import static org.junit.jupiter.api.Assertions.fail

abstract class AerospikeAsyncClientTest extends AerospikeBaseTest {

  @Shared
  AerospikeClient client

  @Shared
  EventLoops eventLoops = new NioEventLoops(1)

  def setup() throws Exception {
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

