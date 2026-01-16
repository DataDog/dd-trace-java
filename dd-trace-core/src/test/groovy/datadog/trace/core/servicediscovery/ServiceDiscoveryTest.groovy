package datadog.trace.core.servicediscovery

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString
import datadog.trace.core.test.DDCoreSpecification
import spock.lang.Timeout
import org.msgpack.core.MessagePack
import org.msgpack.value.MapValue


@Timeout(10)
class ServiceDiscoveryTest extends DDCoreSpecification {
  def "encodePayload with all optional fields"() {
    given:
    String tracerVersion = "1.2.3"
    String hostname = "test-host"
    String runtimeID = "rid-123"
    String service = "orders"
    String env = "prod"
    String serviceVersion = "1.1.1"
    UTF8BytesString processTags = UTF8BytesString.create("key1:val1,key2:val2")
    String containerID = "containerID"
    boolean appLogsCollectionEnabled = true

    when:
    byte[] out = ServiceDiscovery.encodePayload(tracerVersion, hostname, appLogsCollectionEnabled, runtimeID, service, env, serviceVersion, processTags, containerID)
    MapValue map = MessagePack.newDefaultUnpacker(out).unpackValue().asMapValue()

    then:
    map.size() == 11
    and:
    map.toString() == '{"schema_version":2,"tracer_language":"java","tracer_version":"1.2.3","hostname":"test-host","logs_collected":true,"runtime_id":"rid-123","service_name":"orders","service_env":"prod","service_version":"1.1.1","process_tags":"key1:val1,key2:val2","container_id":"containerID"}'
  }

  def "encodePayload only required fields"() {
    given:
    String tracerVersion = "1.2.3"
    String hostname = "my_host"

    when:
    byte[] out = ServiceDiscovery.encodePayload(tracerVersion, hostname, false, null, null, null, null, null, null)
    MapValue map = MessagePack.newDefaultUnpacker(out).unpackValue().asMapValue()

    then:
    map.size() == 5
    and:
    map.toString() == '{"schema_version":2,"tracer_language":"java","tracer_version":"1.2.3","hostname":"my_host","logs_collected":false}'
  }
  def "generateFileName"() {
    when:
    String name = ServiceDiscovery.generateFileName()

    then:
    name.startsWith("datadog-tracer-info-")
    name.length() == "datadog-tracer-info-".length() + 8
  }
}
