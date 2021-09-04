package datadog.trace.bootstrap.instrumentation.jms;

public final class MessageProducerState {

  private final SessionState sessionState;
  private final CharSequence resourceName;
  private final boolean propagationDisabled;

  public MessageProducerState(
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

  /** Retrieves details about the current message batch being produced in this session. */
  public MessageBatchState currentBatchState() {
    return sessionState.currentBatchState(); // tracked per-session
  }
}
