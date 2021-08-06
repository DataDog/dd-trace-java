package datadog.trace.bootstrap.instrumentation.jms;

/** Tracks message batching via the session when producing messages with {@code send}. */
public final class MessageProducerState {
  private final SessionState sessionState;

  public MessageProducerState(SessionState sessionState) {
    this.sessionState = sessionState;
  }

  public SessionState getSessionState() {
    return sessionState;
  }
}
