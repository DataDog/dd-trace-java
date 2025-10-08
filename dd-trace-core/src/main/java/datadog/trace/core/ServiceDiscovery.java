package datadog.trace.core;

import datadog.communication.serialization.GrowableBuffer;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.api.Config;
import datadog.trace.common.writer.ddagent.SimpleUtf8Cache;
import java.nio.ByteBuffer;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ServiceDiscovery {
  private static final Logger log = LoggerFactory.getLogger(ServiceDiscovery.class);

  static void writeTracerMetadata(Config config) {
    byte[] payload = ServiceDiscovery.encodePayload();

    try {
      Class<?> cls = Class.forName("datadog.trace.agent.tooling.ServiceDiscoveryWriter");
      cls.getMethod("writeMemFD", byte[].class).invoke((Object) payload);
    } catch (Throwable t) {
      log.debug("service discovery memfd write failed", t);
    }
  }

  static byte[] encodePayload() {

    GrowableBuffer buffer = new GrowableBuffer(1028);
    MsgPackWriter writer = new MsgPackWriter(buffer);
    Map<CharSequence, Object> m = Map.of("schema_version", 2, "service_name", "banana");
    SimpleUtf8Cache encodingCache = new SimpleUtf8Cache();
    writer.writeMap(m, encodingCache);

    ByteBuffer byteBuffer = buffer.slice();
    byte[] bytes = new byte[byteBuffer.remaining()];
    byteBuffer.duplicate().get(bytes);
    return bytes;
  }
}
