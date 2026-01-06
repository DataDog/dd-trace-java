package datadog.trace.agent.tooling;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.environment.JavaVirtualMachine;
import datadog.environment.OperatingSystem;
import datadog.trace.api.Config;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.Platform;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.servicediscovery.ForeignMemoryWriter;
import datadog.trace.core.servicediscovery.ServiceDiscovery;
import datadog.trace.core.servicediscovery.ServiceDiscoveryFactory;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TracerInstaller {
  private static final Logger log = LoggerFactory.getLogger(TracerInstaller.class);

  /** Register a global tracer if no global tracer is already registered. */
  public static synchronized void installGlobalTracer(
      SharedCommunicationObjects sharedCommunicationObjects,
      ProfilingContextIntegration profilingContextIntegration) {
    if (Config.get().isTraceEnabled() || Config.get().isCiVisibilityEnabled()) {
      if (!(GlobalTracer.get() instanceof CoreTracer)) {
        CoreTracer tracer =
            CoreTracer.builder()
                .sharedCommunicationObjects(sharedCommunicationObjects)
                .profilingContextIntegration(profilingContextIntegration)
                .reportInTracerFlare()
                .pollForTracingConfiguration()
                .serviceDiscoveryFactory(serviceDiscoveryFactory())
                .build();
        installGlobalTracer(tracer);
      } else {
        log.debug("GlobalTracer already registered.");
      }
    } else {
      log.debug("Tracing is disabled, not installing GlobalTracer.");
    }
  }

  private static ServiceDiscoveryFactory serviceDiscoveryFactory() {
    if (!Config.get().isServiceDiscoveryEnabled()) {
      return null;
    }
    if (!OperatingSystem.isLinux()) {
      log.debug("service discovery not supported outside linux");
      return null;
    }
    // make sure this branch is not considered possible for graalvm artifact
    if (Platform.isNativeImageBuilder() || Platform.isNativeImage()) {
      log.debug("service discovery not supported on native images");
      return null;
    }
    return TracerInstaller::initServiceDiscovery;
  }

  @SuppressForbidden // intentional use of Class.forName
  private static ServiceDiscovery initServiceDiscovery() {
    try {
      // use reflection to load MemFDUnixWriter so it doesn't get picked up when we
      // transitively look for all tracer class dependencies to install in GraalVM via
      // VMRuntimeInstrumentation
      final Class<?> memFdClass;
      if (JavaVirtualMachine.isJavaVersionAtLeast(25)) {
        memFdClass =
            Class.forName("datadog.trace.agent.tooling.servicediscovery.ForeignMemoryWriterImpl");
      } else {
        memFdClass = Class.forName("datadog.trace.agent.tooling.servicediscovery.MemFDUnixWriter");
      }
      ForeignMemoryWriter memFd = (ForeignMemoryWriter) memFdClass.getConstructor().newInstance();
      return new ServiceDiscovery(memFd);
    } catch (Throwable e) {
      log.debug("service discovery not supported", e);
      return null;
    }
  }

  public static void installGlobalTracer(final CoreTracer tracer) {
    try {
      GlobalTracer.registerIfAbsent(tracer);
      AgentTracer.registerIfAbsent(tracer);

      log.debug("Global tracer installed");
    } catch (final RuntimeException re) {
      log.warn("Failed to register tracer: {}", tracer, re);
    }
  }

  public static void forceInstallGlobalTracer(CoreTracer tracer) {
    try {
      log.warn("Overriding installed global tracer.  This is not intended for production use");

      GlobalTracer.forceRegister(tracer);
      AgentTracer.forceRegister(tracer);

      log.debug("Global tracer installed");
    } catch (final RuntimeException re) {
      log.warn("Failed to register tracer: {}", tracer, re);
    }
  }
}
