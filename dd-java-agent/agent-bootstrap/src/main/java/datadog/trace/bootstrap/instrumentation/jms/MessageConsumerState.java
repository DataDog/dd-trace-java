package datadog.trace.bootstrap.instrumentation.jms;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;

/** Tracks message scopes and spans when consuming messages with {@code receive}. */
public final class MessageConsumerState {

  private final SessionState sessionState;
  private final CharSequence resourceName;
  private final boolean propagationDisabled;

  public MessageConsumerState(
      SessionState sessionState, CharSequence resourceName, boolean propagationDisabled) {
    this.sessionState = sessionState;
    this.resourceName = resourceName;
    this.propagationDisabled = propagationDisabled;
  }

  public SessionState getSessionState() {
    return sessionState;
  }

  public CharSequence getResourceName() {
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
