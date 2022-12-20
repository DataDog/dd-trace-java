package datadog.trace.instrumentation.aerospike4

import com.aerospike.client.AerospikeClient
import com.aerospike.client.Bin
import com.aerospike.client.Key
import spock.lang.Shared

class AerospikeClientTest extends AerospikeBaseTest {

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
