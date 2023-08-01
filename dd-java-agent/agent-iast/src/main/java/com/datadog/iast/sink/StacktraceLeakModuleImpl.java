package com.datadog.iast.sink;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.Config;
import datadog.trace.api.iast.sink.StacktraceLeakModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;

public class StacktraceLeakModuleImpl extends SinkModuleBase implements StacktraceLeakModule {
  @Override
  public void onStacktraceLeak(Throwable throwable) {
    if (throwable != null) {
      final AgentSpan span = AgentTracer.activeSpan();
      String serviceName = Config.get().getServiceName();
      reporter.report(
          span,
          new Vulnerability(VulnerabilityType.STACKTRACE_LEAK, null, new Evidence(serviceName)));
    }
  }
}
