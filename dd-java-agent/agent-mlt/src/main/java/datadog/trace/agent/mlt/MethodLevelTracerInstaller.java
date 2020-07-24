package datadog.trace.agent.mlt;

import com.datadog.mlt.sampler.JMXSessionFactory;
import datadog.trace.api.Config;
import datadog.trace.mlt.MethodLevelTracer;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class MethodLevelTracerInstaller {
  private static volatile boolean installed;

  public static void install() {
    if (!installed) {
      final Config config = Config.get();
      if (!config.isMethodTraceEnabled()) {
        log.info("Method Tracing: disabled");
        return;
      }
      MethodLevelTracer.initialize(new JMXSessionFactory());
      installed = true;
    }
  }
}
