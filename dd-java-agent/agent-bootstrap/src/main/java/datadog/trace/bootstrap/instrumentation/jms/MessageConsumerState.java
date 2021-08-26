package datadog.trace.bootstrap.instrumentation.jms;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;

/** Tracks message scopes and spans when consuming messages with {@code receive}. */
public final class MessageConsumerState {

  private final SessionState sessionState;
  private final UTF8BytesString resourceName;
  private final boolean propagationDisabled;

  public MessageConsumerState(
      SessionState sessionState, UTF8BytesString resourceName, boolean propagationDisabled) {
    this.sessionState = sessionState;
    this.resourceName = resourceName;
    this.propagationDisabled = propagationDisabled;
  }

  public SessionState getSessionState() {
    return sessionState;
  }

  public UTF8BytesString getResourceName() {
    return resourceName;
  }

  public boolean isPropagationDisabled() {
    return propagationDisabled;
  }

  /** Closes the given message scope when the next message is consumed or the consumer is closed. */
  public void closeOnIteration(AgentScope newScope) {
    sessionState.closeOnIteration(newScope); // tracked per-session-thread
  }

  /** Closes the scope previously registered by closeOnIteration, assumes same calling thread. */
  public void closePreviousMessageScope() {
    sessionState.closePreviousMessageScope(); // tracked per-session-thread
  }
}
