package datadog.trace.agent.jmxfetch;

import datadog.trace.api.TraceConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import org.datadog.jmxfetch.ExitWatcher;

public final class TraceConfigExitWatcher extends ExitWatcher {
  @Override
  public boolean shouldExit() {
    TraceConfig traceConfig = AgentTracer.traceConfig();
    // assume JMXFetch shouldn't exit if tracer hasn't been installed yet
    return null != traceConfig && !traceConfig.isRuntimeMetricsEnabled();
  }
}
