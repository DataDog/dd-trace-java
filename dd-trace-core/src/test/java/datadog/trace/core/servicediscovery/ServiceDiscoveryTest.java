package datadog.trace.core.servicediscovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.msgpack.core.MessagePack;
import org.msgpack.value.MapValue;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class ServiceDiscoveryTest {
  @Test
  void encodePayloadWithAllOptionalFields() throws IOException {
    String tracerVersion = "1.2.3";
    String hostname = "test-host";
    String runtimeID = "rid-123";
    String service = "orders";
    String env = "prod";
    String serviceVersion = "1.1.1";
    UTF8BytesString processTags = UTF8BytesString.create("key1:val1,key2:val2");
    String containerID = "containerID";
    boolean appLogsCollectionEnabled = true;

    byte[] out =
        ServiceDiscovery.encodePayload(
            tracerVersion,
            hostname,
            appLogsCollectionEnabled,
            runtimeID,
            service,
            env,
            serviceVersion,
            processTags,
            containerID);
    MapValue map = MessagePack.newDefaultUnpacker(out).unpackValue().asMapValue();

    assertEquals(11, map.size());
    assertEquals(
        "{\"schema_version\":2,\"tracer_language\":\"java\",\"tracer_version\":\"1.2.3\",\"hostname\":\"test-host\",\"logs_collected\":true,\"runtime_id\":\"rid-123\",\"service_name\":\"orders\",\"service_env\":\"prod\",\"service_version\":\"1.1.1\",\"process_tags\":\"key1:val1,key2:val2\",\"container_id\":\"containerID\"}",
        map.toString());
  }

  @Test
  void encodePayloadOnlyRequiredFields() throws IOException {
    String tracerVersion = "1.2.3";
    String hostname = "my_host";

    byte[] out =
        ServiceDiscovery.encodePayload(
            tracerVersion, hostname, false, null, null, null, null, null, null);
    MapValue map = MessagePack.newDefaultUnpacker(out).unpackValue().asMapValue();

    assertEquals(5, map.size());
    assertEquals(
        "{\"schema_version\":2,\"tracer_language\":\"java\",\"tracer_version\":\"1.2.3\",\"hostname\":\"my_host\",\"logs_collected\":false}",
        map.toString());
  }

  @Test
  void generateFileName() {
    String name = ServiceDiscovery.generateFileName();

    String prefix = "datadog-tracer-info-";
    assertTrue(name.startsWith(prefix));
    assertEquals(prefix.length() + 8, name.length());
  }
}
