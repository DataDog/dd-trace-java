package datadog.trace.instrumentation.aerospike4

import com.aerospike.client.AerospikeClient
import com.aerospike.client.Bin
import com.aerospike.client.Key
import datadog.trace.api.Config
import spock.lang.Shared

abstract class AerospikeClientTest extends AerospikeBaseTest {

  @Shared
  AerospikeClient client

  def setup() throws Exception {
    client = new AerospikeClient(aerospikeHost, aerospikePort)
  }

  def cleanup() throws Exception {
    client.close()
  }

  def "test put then get"() {
    setup:
    def key = new Key("test", "set", "key")
    def bin = new Bin("name", "value")

    when:
    client.put(null, key, bin)
    client.get(null, key)

    then:
    assertTraces(2) {
      trace(1) {
        aerospikeSpan(it, 0, "AerospikeClient.put")
      }
      trace(1) {
        aerospikeSpan(it, 0, "AerospikeClient.get")
      }
    }
  }
}

class AerospikeClientV0ForkedTest extends AerospikeClientTest {
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

class AerospikeClientV1ForkedTest extends AerospikeClientTest {
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
