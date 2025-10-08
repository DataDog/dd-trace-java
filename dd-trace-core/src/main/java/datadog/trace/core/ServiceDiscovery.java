package datadog.trace.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ServiceDiscovery {
  private static final Logger log = LoggerFactory.getLogger(ServiceDiscovery.class);

  static void writeTracerMetadata() {

    byte[] payload = ServiceDiscovery.encodePayload();

    try {
      Class<?> cls = Class.forName("datadog.trace.agent.tooling.ServiceDiscoveryWriter");
      cls.getMethod("writeMemFD", byte[].class).invoke((Object) payload);
    } catch (Throwable t) {
      log.debug("Service discovery memfd write failed", t);
    }
  }

  static byte[] encodePayload() {
    return new byte[] {0x1};
  }
}
