package datadog.trace.bootstrap.instrumentation.jms;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

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

  /** Gets the current time-in-queue span; returns {@code null} if this is a new batch. */
  public AgentSpan getTimeInQueueSpan(long batchId) {
    return sessionState.getTimeInQueueSpan(batchId); // tracked per-session-thread
  }

  /** Starts tracking a new time-in-queue span. */
  public void setTimeInQueueSpan(long batchId, AgentSpan span) {
    sessionState.setTimeInQueueSpan(batchId, span); // tracked per-session-thread
  }

  /** Finishes the current time-in-queue span and optionally stops tracking it. */
  public void finishTimeInQueueSpan(boolean clear) {
    sessionState.finishTimeInQueueSpan(clear); // tracked per-session-thread
  }
}
