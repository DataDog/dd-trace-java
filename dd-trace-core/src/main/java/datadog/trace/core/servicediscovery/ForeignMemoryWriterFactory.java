package datadog.trace.core.servicediscovery;

import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;

import datadog.environment.JavaVirtualMachine;
import datadog.environment.OperatingSystem;
import datadog.environment.SystemProperties;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ForeignMemoryWriterFactory implements Supplier<ForeignMemoryWriter> {
  private static final Logger log = LoggerFactory.getLogger(ForeignMemoryWriterFactory.class);

  @Override
  public ForeignMemoryWriter get() {
    switch (OperatingSystem.type()) {
      case LINUX:
        return createForLinux();
      default:
        return null;
    }
  }

  @SuppressForbidden // intentional Class.forName to force loading
  private ForeignMemoryWriter createForLinux() {
    try {
      // first check if the arch is supported
      if (OperatingSystem.architecture() == OperatingSystem.Architecture.UNKNOWN) {
        log.debug(
            SEND_TELEMETRY,
            "service discovery not supported for arch={}",
            SystemProperties.get("os.arch"));
        return null;
      }
      final Class<?> memFdClass;
      if (JavaVirtualMachine.isJavaVersionAtLeast(22)) {
        memFdClass =
            Class.forName("datadog.trace.agent.tooling.servicediscovery.MemFDUnixWriterFFM");
      } else {
        memFdClass =
            Class.forName("datadog.trace.agent.tooling.servicediscovery.MemFDUnixWriterJNA");
      }
      return (ForeignMemoryWriter) memFdClass.newInstance();
    } catch (Throwable t) {
      log.debug("Unable to instantiate foreign memory writer", t);
      return null;
    }
  }
}
