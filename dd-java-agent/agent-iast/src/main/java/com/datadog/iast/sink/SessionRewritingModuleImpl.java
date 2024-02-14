package com.datadog.iast.sink;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Location;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.sink.SessionRewritingModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

public class SessionRewritingModuleImpl extends SinkModuleBase implements SessionRewritingModule {

  static final String EVIDENCE_VALUE =
      "URL rewriting may be used by the container for session tracking";

  public SessionRewritingModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void checkSessionTrackingModes(@NotNull Set<String> sessionTrackingModes) {
    if (!sessionTrackingModes.contains("URL")) {
      return;
    }

    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    // overhead is not checked here as it's called once per application context
    reporter.report(
        span,
        new Vulnerability(
            VulnerabilityType.SESSION_REWRITING,
            Location.forSpanAndStack(span, getCurrentStackTrace()),
            new Evidence(EVIDENCE_VALUE)));
  }
}
