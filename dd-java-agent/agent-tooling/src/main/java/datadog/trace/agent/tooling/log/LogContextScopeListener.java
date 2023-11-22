package datadog.trace.agent.tooling.log;

import datadog.trace.api.Config;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.WithGlobalTracer;
import datadog.trace.api.scopemanager.ExtendedScopeListener;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;
import datadog.trace.bootstrap.instrumentation.api.Tags;

/**
 * A scope listener that receives the MDC/ThreadContext put and receive methods and update the trace
 * and span reference anytime a new scope is activated or closed.
 */
public abstract class LogContextScopeListener
    implements ExtendedScopeListener, WithGlobalTracer.Callback {

  @Override
  public void afterScopeActivated() {}

  @Override
  public void afterScopeActivated(
      DDTraceId traceId, long localRootSpanId, long spanId, TraceConfig traceConfig) {
    if (traceConfig != null && traceConfig.isLogsInjectionEnabled()) {
      if (InstrumenterConfig.get().isLogs128bTraceIdEnabled() && traceId.toHighOrderLong() != 0) {
        add(CorrelationIdentifier.getTraceIdKey(), traceId.toHexString());
      } else {
        add(CorrelationIdentifier.getTraceIdKey(), traceId.toString());
      }

      add(CorrelationIdentifier.getSpanIdKey(), DDSpanId.toString(spanId));

      String env = Config.get().getEnv();
      if (null != env && !env.isEmpty()) {
        add(Tags.DD_ENV, env);
      }
      String serviceName = Config.get().getServiceName();
      if (null != serviceName && !serviceName.isEmpty()) {
        add(Tags.DD_SERVICE, serviceName);
      }
      String version = Config.get().getVersion();
      if (null != version && !version.isEmpty()) {
        add(Tags.DD_VERSION, version);
      }
    }
  }

  @Override
  public void afterScopeClosed() {
    remove(CorrelationIdentifier.getTraceIdKey());
    remove(CorrelationIdentifier.getSpanIdKey());
    remove(Tags.DD_ENV);
    remove(Tags.DD_SERVICE);
    remove(Tags.DD_VERSION);
  }

  public abstract void add(String key, String value);

  public abstract void remove(String key);

  @Override
  public void withTracer(TracerAPI tracer) {
    tracer.addScopeListener(this);
  }
}
