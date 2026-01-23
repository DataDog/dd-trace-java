package datadog.trace.agent.tooling;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.environment.OperatingSystem;
import datadog.trace.api.Config;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.Platform;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.servicediscovery.ForeignMemoryWriter;
import datadog.trace.core.servicediscovery.ForeignMemoryWriterFactory;
import datadog.trace.core.servicediscovery.ServiceDiscovery;
import datadog.trace.core.servicediscovery.ServiceDiscoveryFactory;
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

  private static ServiceDiscovery initServiceDiscovery() {
    final ForeignMemoryWriter writer = new ForeignMemoryWriterFactory().get();
    if (writer != null) {
      return new ServiceDiscovery(writer);
    }
    return null;
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
