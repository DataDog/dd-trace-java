import datadog.trace.agent.test.naming.VersionedNamingTestBase
import io.grpc.Metadata
import static datadog.trace.instrumentation.grpc.client.GrpcInjectAdapter.SETTER

class GrpcInjectAdapterTest extends VersionedNamingTestBase {
  def "carrier set is called only once per unique ot-baggage-* key"() {
    setup:
    def carrier = new Metadata()

    def baggage = [
      ["ot-baggage-foo", "v1"],
      ["ot-baggage-foo", "v2"],
      ["ot-baggage-bar", "v3"]
    ]

    when:
    baggage.each { pair ->
      def (key, value) = pair
      SETTER.set(carrier, key, value)
    }

    then:
    carrier.headerCount() == 2
    carrier.get(getKey("ot-baggage-foo")) == "v1" // first value wins
    carrier.get(getKey("ot-baggage-bar")) == "v3"
  }

  def "carrier set is can set repeated keys that are not ot-baggage-*"() {
    setup:
    def carrier = new Metadata()

    def baggage = [["foo", "v1"], ["foo", "v2"], ["bar", "v3"]]

    when:
    baggage.each { pair ->
      def (key, value) = pair
      SETTER.set(carrier, key, value)
    }

    then:
    carrier.headerCount() == 3
    carrier.get(getKey("foo")) == "v2" // last value wins
    carrier.get(getKey("bar")) == "v3"
  }

  Metadata.Key<String> getKey(String key){
    Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER)
  }


  @Override
  int version() {
    return 0
  }

  @Override
  String service() {
    return null
  }

  @Override
  String operation() {
    return null
  }
}
