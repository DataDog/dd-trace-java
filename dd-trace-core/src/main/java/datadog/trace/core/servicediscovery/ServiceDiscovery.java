package datadog.trace.core.servicediscovery;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import datadog.common.container.ContainerInfo;
import datadog.communication.ddagent.TracerVersion;
import datadog.communication.serialization.GrowableBuffer;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.api.Config;
import datadog.trace.api.ProcessTags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.nio.ByteBuffer;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServiceDiscovery {
  private static final Logger log = LoggerFactory.getLogger(ServiceDiscovery.class);

  private final ForeignMemoryWriter foreignMemoryWriter;

  public ServiceDiscovery(ForeignMemoryWriter foreignMemoryWriter) {
    this.foreignMemoryWriter = foreignMemoryWriter;
  }

  public void writeTracerMetadata(Config config) {
    try {
      byte[] payload =
          ServiceDiscovery.encodePayload(
              TracerVersion.TRACER_VERSION,
              config.getHostName(),
              config.getRuntimeId(),
              config.getServiceName(),
              config.getEnv(),
              config.getVersion(),
              ProcessTags.getTagsForSerialization(),
              ContainerInfo.get().getContainerId());

      foreignMemoryWriter.write(generateFileName(), payload);
    } catch (Throwable t) {
      log.debug("service discovery memfd write failed", t);
    }
  }

  private static String generateFileName() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    return "datadog-tracer-info-" + suffix;
  }

  static byte[] encodePayload(
      String tracerVersion,
      String hostname,
      String runtimeID,
      String service,
      String env,
      String serviceVersion,
      UTF8BytesString processTags,
      String containerID) {
    GrowableBuffer buffer = new GrowableBuffer(1024);
    MsgPackWriter writer = new MsgPackWriter(buffer);

    int mapElements = 4;
    mapElements += (runtimeID != null && !runtimeID.isEmpty()) ? 1 : 0;
    mapElements += (service != null && !service.isEmpty()) ? 1 : 0;
    mapElements += (env != null && !env.isEmpty()) ? 1 : 0;
    mapElements += (serviceVersion != null && !serviceVersion.isEmpty()) ? 1 : 0;
    mapElements += (processTags != null && processTags.length() > 0) ? 1 : 0;
    mapElements += (containerID != null && !containerID.isEmpty()) ? 1 : 0;

    writer.startMap(mapElements);

    writer.writeUTF8("schema_version".getBytes(ISO_8859_1));
    writer.writeInt(2);

    writer.writeUTF8("tracer_language".getBytes(ISO_8859_1));
    writer.writeUTF8("java".getBytes(ISO_8859_1));

    writer.writeUTF8("tracer_version".getBytes(ISO_8859_1));
    writer.writeUTF8(tracerVersion.getBytes(ISO_8859_1));

    writer.writeUTF8("hostname".getBytes(ISO_8859_1));
    writer.writeUTF8(hostname.getBytes(ISO_8859_1));

    if (runtimeID != null && !runtimeID.isEmpty()) {
      writer.writeUTF8("runtime_id".getBytes(ISO_8859_1));
      writer.writeUTF8(runtimeID.getBytes(ISO_8859_1));
    }
    if (service != null && !service.isEmpty()) {
      writer.writeUTF8("service_name".getBytes(ISO_8859_1));
      writer.writeUTF8(service.getBytes(ISO_8859_1));
    }
    if (env != null && !env.isEmpty()) {
      writer.writeUTF8("service_env".getBytes(ISO_8859_1));
      writer.writeUTF8(env.getBytes(ISO_8859_1));
    }
    if (serviceVersion != null && !serviceVersion.isEmpty()) {
      writer.writeUTF8("service_version".getBytes(ISO_8859_1));
      writer.writeUTF8(serviceVersion.getBytes(ISO_8859_1));
    }
    if (processTags != null && processTags.length() > 0) {
      writer.writeUTF8("process_tags".getBytes(ISO_8859_1));
      writer.writeUTF8(processTags);
    }
    if (containerID != null && !containerID.isEmpty()) {
      writer.writeUTF8("container_id".getBytes(ISO_8859_1));
      writer.writeUTF8(containerID.getBytes(ISO_8859_1));
    }

    ByteBuffer byteBuffer = buffer.slice();
    byte[] bytes = new byte[byteBuffer.remaining()];
    byteBuffer.get(bytes);
    return bytes;
  }
}
