package datadog.trace.instrumentation.spymemcached;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SyncCompletionListener extends CompletionListener<Void> {
  private static final Logger log = LoggerFactory.getLogger(SyncCompletionListener.class);
  private final AgentScope scope;

  public SyncCompletionListener(final AgentSpan span, final String methodName) {
    super(span, methodName);
    scope = AgentTracer.activateSpan(span);
  }

  @Override
  protected void processResult(final AgentSpan span, final Void future)
      throws ExecutionException, InterruptedException {
    log.error("processResult was called on SyncCompletionListener. This should never happen. ");
  }

  public void done(final Throwable thrown) {
    try {
      closeSyncSpan(thrown);
    } finally {
      scope.close();
    }
  }
}
