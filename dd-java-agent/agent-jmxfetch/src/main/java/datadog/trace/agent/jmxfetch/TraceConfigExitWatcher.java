package datadog.trace.agent.jmxfetch;

import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import org.datadog.jmxfetch.ExitWatcher;

public final class TraceConfigExitWatcher extends ExitWatcher {
  @Override
  public boolean shouldExit() {
    // only stop JMXFetch when the registered tracer says that metrics have been disabled
    return AgentTracer.isRegistered() && !AgentTracer.traceConfig().isRuntimeMetricsEnabled();
  }
}
