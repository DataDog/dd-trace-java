package datadog.trace.core.servicediscovery;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import datadog.common.container.ContainerInfo;
import datadog.communication.ddagent.TracerVersion;
import datadog.communication.serialization.GrowableBuffer;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.api.Config;
import datadog.trace.api.ProcessTags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.common.writer.ddagent.SimpleUtf8Cache;
import java.nio.ByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServiceDiscovery {
  private static final Logger log = LoggerFactory.getLogger(ServiceDiscovery.class);

  private static final byte[] SCHEMA_VERSION = "schema_version".getBytes(ISO_8859_1);
  private static final byte[] RUNTIME_ID = "runtime_id".getBytes(ISO_8859_1);
  private static final byte[] LANG = "tracer_language".getBytes(ISO_8859_1);
  private static final byte[] TRACER_VERSION = "tracer_version".getBytes(ISO_8859_1);
  private static final byte[] HOSTNAME = "hostname".getBytes(ISO_8859_1);
  private static final byte[] SERVICE = "service_name".getBytes(ISO_8859_1);
  private static final byte[] ENV = "service_env".getBytes(ISO_8859_1);
  private static final byte[] SERVICE_VERSION = "service_version".getBytes(ISO_8859_1);
  private static final byte[] PROCESS_TAGS = "process_tags".getBytes(ISO_8859_1);
  private static final byte[] CONTAINER_ID = "container_id".getBytes(ISO_8859_1);
  private static final byte[] JAVA_LANG = "java".getBytes(ISO_8859_1);

  private final ForeignMemoryWriter foreignMemoryWriter;

  public ServiceDiscovery(ForeignMemoryWriter foreignMemoryWriter) {
    this.foreignMemoryWriter = foreignMemoryWriter;
  }

  public void writeTracerMetadata(Config config) {
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

    try {
      foreignMemoryWriter.write(payload);
    } catch (Throwable t) {
      log.debug("service discovery memfd write failed", t);
    }
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
    GrowableBuffer buffer = new GrowableBuffer(1028);
    MsgPackWriter writer = new MsgPackWriter(buffer);

    int mapElements = 4;
    mapElements += (runtimeID != null && !runtimeID.isEmpty()) ? 1 : 0;
    mapElements += (service != null && !service.isEmpty()) ? 1 : 0;
    mapElements += (env != null && !env.isEmpty()) ? 1 : 0;
    mapElements += (serviceVersion != null && !serviceVersion.isEmpty()) ? 1 : 0;
    mapElements += (processTags != null && processTags.length() > 0) ? 1 : 0;
    mapElements += (containerID != null && !containerID.isEmpty()) ? 1 : 0;

    SimpleUtf8Cache encodingCache = new SimpleUtf8Cache(256);

    writer.startMap(mapElements);

    writer.writeBinary(SCHEMA_VERSION);
    writer.writeInt(2);

    writer.writeBinary(LANG);
    writer.writeBinary(JAVA_LANG);

    writer.writeBinary(TRACER_VERSION);
    writer.writeString(tracerVersion, encodingCache);

    writer.writeBinary(HOSTNAME);
    writer.writeString(hostname, encodingCache);

    if (runtimeID != null && !runtimeID.isEmpty()) {
      writer.writeBinary(RUNTIME_ID);
      writer.writeString(runtimeID, encodingCache);
    }
    if (service != null && !service.isEmpty()) {
      writer.writeBinary(SERVICE);
      writer.writeString(service, encodingCache);
    }
    if (env != null && !env.isEmpty()) {
      writer.writeBinary(ENV);
      writer.writeString(env, encodingCache);
    }
    if (serviceVersion != null && !serviceVersion.isEmpty()) {
      writer.writeBinary(SERVICE_VERSION);
      writer.writeString(serviceVersion, encodingCache);
    }
    if (processTags != null && processTags.length() > 0) {
      writer.writeBinary(PROCESS_TAGS);
      writer.writeUTF8(processTags);
    }
    if (containerID != null && !containerID.isEmpty()) {
      writer.writeBinary(CONTAINER_ID);
      writer.writeString(containerID, encodingCache);
    }

    ByteBuffer byteBuffer = buffer.slice();
    byte[] bytes = new byte[byteBuffer.remaining()];
    byteBuffer.duplicate().get(bytes);
    return bytes;
  }
}
