package datadog.trace.bootstrap.ebpf;

import datadog.environment.OperatingSystem;
import datadog.trace.api.Config;
import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ProcessContext {
  private static final Logger log = LoggerFactory.getLogger(ProcessContext.class);

  private static final ByteBuffer buffer = ByteBuffer.allocateDirect(16);
  private static final ByteBuffer payload = ByteBuffer.allocateDirect(1024);
  private static final String MAGIC = "OTL-PROC";

  public static void publish(Config config) {
    if (!OperatingSystem.isLinux()) {
      // This is ebpf specific - only on Linux for now
      return;
    }
    try {
      String service = config.getServiceName();
      String env = config.getEnv();
      String runtimeId = config.getRuntimeId();

      Field f = Buffer.class.getDeclaredField("address");
      f.setAccessible(true);

      if (service.length() + env.length() + runtimeId.length() + 4 * 3 > 1023) {
        log.warn("Unable to set process context. Out of process info space (1024 bytes).");
        return;
      }
      payload.putLong(2L); // the payload version
      payload.putInt(service.length());
      payload.put(service.getBytes(StandardCharsets.UTF_8));
      payload.putInt(env.length());
      payload.put(env.getBytes(StandardCharsets.UTF_8));
      payload.putInt(runtimeId.length());
      payload.put(runtimeId.getBytes(StandardCharsets.UTF_8));

      buffer.put(MAGIC.getBytes());
      buffer.putLong((long) f.get(payload));
    } catch (Exception e) {
      log.error("Error publishing process context", e);
    }
  }
}
