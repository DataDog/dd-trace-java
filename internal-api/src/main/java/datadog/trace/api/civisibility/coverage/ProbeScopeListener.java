package datadog.trace.api.civisibility.coverage;

import datadog.trace.api.WithGlobalTracer;
import datadog.trace.api.scopemanager.ScopeListener;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;

/**
 * Tells {@link ProbeRecorder} when a scope becomes active or closes, so that code run on a test's
 * thread under another test's context, such as a continuation that a finished test registered, is
 * not credited to the test whose window is open on the thread.
 */
public final class ProbeScopeListener implements ScopeListener, WithGlobalTracer.Callback {

  @Override
  public void afterScopeActivated() {
    ProbeRecorder.scopeActivated();
  }

  @Override
  public void afterScopeClosed() {
    ProbeRecorder.scopeClosed();
  }

  @Override
  public void withTracer(TracerAPI tracer) {
    tracer.addScopeListener(this);
  }
}
