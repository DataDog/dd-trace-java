package datadog.trace.bootstrap;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

/** Coordinates a resource-method span with terminal calls on its asynchronous response. */
public final class ResourceMethodSpanState {

  private final AgentSpan span;
  private int terminalCalls;
  private boolean resourceExited;
  private boolean resourceFailed;
  private boolean responseResolved;
  private boolean terminalOutcome;
  private boolean finishClaimed;

  public ResourceMethodSpanState(final AgentSpan span) {
    this.span = span;
  }

  public AgentSpan span() {
    return span;
  }

  /** Registers a terminal call unless finishing has already been claimed. */
  public synchronized boolean beginTerminalCall() {
    if (finishClaimed) {
      return false;
    }
    terminalCalls++;
    return true;
  }

  /** Claims the first successful or exceptional terminal outcome for decoration. */
  public synchronized boolean claimTerminalOutcome() {
    if (finishClaimed || terminalOutcome) {
      return false;
    }
    terminalOutcome = true;
    return true;
  }

  /** Completes a terminal call and returns whether its caller must finish the span. */
  public synchronized boolean endTerminalCall(final boolean responseResolved) {
    if (terminalCalls > 0) {
      terminalCalls--;
    }
    this.responseResolved |= responseResolved;
    return claimFinishIfReady();
  }

  /** Records resource-method exit and returns whether its caller must finish the span. */
  public synchronized boolean exitResource(
      final boolean responseResolved, final boolean resourceFailed) {
    resourceExited = true;
    this.responseResolved |= responseResolved;
    this.resourceFailed |= resourceFailed;
    return claimFinishIfReady();
  }

  private boolean claimFinishIfReady() {
    if (!finishClaimed
        && resourceExited
        && terminalCalls == 0
        && (resourceFailed || responseResolved || terminalOutcome)) {
      finishClaimed = true;
      return true;
    }
    return false;
  }
}
