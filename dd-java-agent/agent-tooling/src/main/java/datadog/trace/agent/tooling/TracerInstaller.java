package datadog.trace.agent.tooling;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.environment.OperatingSystem;
import datadog.trace.agent.tooling.servicediscovery.MemFDUnixWriter;
import datadog.trace.api.Config;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.Platform;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.servicediscovery.ServiceDiscovery;
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
                .serviceDiscovery(getServiceDiscovery())
                .build();
        installGlobalTracer(tracer);
      } else {
        log.debug("GlobalTracer already registered.");
      }
    } else {
      log.debug("Tracing is disabled, not installing GlobalTracer.");
    }
  }

  private static ServiceDiscovery getServiceDiscovery() {
    if (!OperatingSystem.isLinux()) {
      log.debug("service discovery not supported outside linux");
      return null;
    }
    // make sure this branch is not considered possible for graalvm artifact
    if (!Platform.isNativeImageBuilder() && !Platform.isNativeImage()) {
      return new ServiceDiscovery(new MemFDUnixWriter());
    }
    log.debug("service discovery not supported on native images");
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
