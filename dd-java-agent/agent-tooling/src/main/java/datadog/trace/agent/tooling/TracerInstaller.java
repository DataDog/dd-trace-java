package datadog.trace.agent.tooling;

import datadog.trace.api.Config;
import datadog.trace.api.GlobalTracer;
import datadog.trace.bootstrap.security.Engine;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.scopemanager.ContinuableScopeManager;
import datadog.trace.security.EngineImpl;
import datadog.trace.security.EngineRule;
import datadog.trace.security.PowerwafCallback;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Map;

@Slf4j
public class TracerInstaller {
  /** Register a global tracer if no global tracer is already registered. */
  public static synchronized void installGlobalTracer() {
    if (Config.get().isTraceEnabled()) {
      if (!(GlobalTracer.get() instanceof CoreTracer)) {
        installGlobalTracer(CoreTracer.builder().build());
      } else {
        log.debug("GlobalTracer already registered.");
      }
    } else {
      log.debug("Tracing is disabled, not installing GlobalTracer.");
    }
  }

  public static void installGlobalTracer(final CoreTracer tracer) {
    try {
      GlobalTracer.registerIfAbsent(tracer);
      AgentTracer.registerIfAbsent(tracer);

      EngineImpl engine = new EngineImpl((ContinuableScopeManager) tracer.scopeManager);
      engine.addSubscription(new PowerwafCallback(new EngineRule() {
        @Override
        public String getName() {
          return "pwaf_rule";
        }

        @Override
        public Map<String, Object> getData() {
          return Collections.emptyMap();
        }

        @Override
        public boolean isBlock() {
          return true;
        }
      }));
      Engine.INSTANCE = engine;

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
