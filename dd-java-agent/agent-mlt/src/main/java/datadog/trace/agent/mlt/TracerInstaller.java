package datadog.trace.agent.mlt;

import com.datadog.mlt.sampler.JMXSessionFactory;
import datadog.trace.mlt.MethodLevelTracer;

public final class TracerInstaller {
  public static void install() {
    MethodLevelTracer.initialize(new JMXSessionFactory());
  }
}
